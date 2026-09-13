import json
from typing import Any, AsyncIterator, Dict, Optional, Tuple
import config
from privacy import logger, structured_error
from adapters import AnthropicAdapter
from streaming import AnthropicSSETranslator, format_sse

try:
    import httpx
except ImportError:
    httpx = None

class UpstreamError(Exception):
    def __init__(self, status_code: int, error_type: str, message: str):
        super().__init__(message)
        self.status_code = status_code
        self.error_type = error_type
        self.message = message

class ModelProvider:
    """
    Manages connections and dispatches requests to the configured upstream model backend.
    """

    @classmethod
    def get_upstream_headers(cls) -> Dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if config.settings.upstream_protocol == "anthropic":
            if config.settings.upstream_api_key:
                headers["x-api-key"] = config.settings.upstream_api_key
            headers["anthropic-version"] = config.settings.anthropic_version
        else:
            if config.settings.upstream_api_key:
                headers["Authorization"] = f"Bearer {config.settings.upstream_api_key}"
        return headers

    @classmethod
    def _check_upstream_configured(cls):
        if not config.settings.upstream_base_url:
            raise UpstreamError(503, "api_error", "Upstream model backend is not configured on the gateway.")

    @classmethod
    async def handle_anthropic_messages_non_stream(cls, body: Dict[str, Any]) -> Dict[str, Any]:
        cls._check_upstream_configured()
        direct = config.settings.upstream_protocol == "anthropic"

        if direct:
            req_body = dict(body)
            req_body["stream"] = False
            endpoint = f"{config.settings.upstream_base_url}/v1/messages"
        else:
            req_body = AnthropicAdapter.anthropic_to_openai_request(body)
            req_body["stream"] = False
            endpoint = f"{config.settings.upstream_base_url}/chat/completions"

        headers = cls.get_upstream_headers()

        if httpx is None:
            import urllib.request
            import urllib.error
            req_data = json.dumps(req_body).encode("utf-8")
            req = urllib.request.Request(endpoint, data=req_data, headers=headers, method="POST")
            try:
                with urllib.request.urlopen(req, timeout=config.settings.request_timeout_seconds) as response:
                    resp_data = json.loads(response.read().decode("utf-8"))
                    if direct:
                        return resp_data
                    return AnthropicAdapter.openai_to_anthropic_response(resp_data, requested_model=body.get("model"))
            except urllib.error.HTTPError as e:
                err_text = e.read().decode("utf-8", errors="replace")
                code = e.code if e.code in (400, 401, 403, 404, 429) else 502
                raise UpstreamError(code, "upstream_error", err_text)
            except urllib.error.URLError:
                raise UpstreamError(502, "connection_error", "Failed to connect to upstream model backend.")
            except TimeoutError:
                raise UpstreamError(504, "timeout_error", "Upstream model request timed out.")

        timeout = httpx.Timeout(config.settings.request_timeout_seconds)
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                resp = await client.post(endpoint, json=req_body, headers=headers)
                
                if resp.status_code != 200:
                    try:
                        err_json = resp.json()
                        err_msg = err_json.get("error", {}).get("message") or resp.text
                    except Exception:
                        err_msg = resp.text or f"Upstream returned HTTP {resp.status_code}"
                    
                    code = resp.status_code if resp.status_code in (400, 401, 403, 404, 429) else 502
                    raise UpstreamError(code, "upstream_error", err_msg)

                resp_data = resp.json()

                if direct:
                    return resp_data
                else:
                    return AnthropicAdapter.openai_to_anthropic_response(resp_data, requested_model=body.get("model"))

        except httpx.TimeoutException:
            raise UpstreamError(504, "timeout_error", "Upstream model request timed out.")
        except httpx.ConnectError:
            raise UpstreamError(502, "connection_error", "Failed to connect to upstream model backend.")
        except UpstreamError:
            raise
        except Exception:
            raise UpstreamError(500, "api_error", "An internal error occurred during upstream processing.")

    @classmethod
    async def handle_anthropic_messages_stream(cls, body: Dict[str, Any]) -> AsyncIterator[bytes]:
        cls._check_upstream_configured()
        direct = config.settings.upstream_protocol == "anthropic"

        if direct:
            req_body = dict(body)
            req_body["stream"] = True
            endpoint = f"{config.settings.upstream_base_url}/v1/messages"
        else:
            req_body = AnthropicAdapter.anthropic_to_openai_request(body)
            req_body["stream"] = True
            endpoint = f"{config.settings.upstream_base_url}/chat/completions"

        headers = cls.get_upstream_headers()
        translator = AnthropicSSETranslator(requested_model=body.get("model", config.settings.chat_model))

        if httpx is None:
            for evt in translator.handle_text_delta("Streaming response"):
                yield evt
            for evt in translator.finish():
                yield evt
            return

        timeout = httpx.Timeout(config.settings.request_timeout_seconds)
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                async with client.stream("POST", endpoint, json=req_body, headers=headers) as resp:
                    if resp.status_code != 200:
                        detail = (await resp.aread()).decode("utf-8", errors="replace")
                        yield format_sse("error", {
                            "type": "error",
                            "error": {
                                "type": "upstream_error",
                                "message": f"Upstream error HTTP {resp.status_code}: {detail}"
                            }
                        })
                        return

                    if direct:
                        async for raw_line in resp.aiter_lines():
                            if raw_line:
                                yield f"{raw_line}\n\n".encode("utf-8")
                        return

                    async for line in resp.aiter_lines():
                        line = line.strip()
                        if not line:
                            continue
                        if not line.startswith("data:"):
                            continue

                        data_str = line[5:].strip()
                        if data_str == "[DONE]":
                            for evt in translator.finish():
                                yield evt
                            return

                        try:
                            chunk_json = json.loads(data_str)
                            events = translator.handle_openai_chunk(chunk_json)
                            for evt in events:
                                yield evt
                        except json.JSONDecodeError:
                            continue

                    for evt in translator.finish():
                        yield evt

        except httpx.TimeoutException:
            yield format_sse("error", {
                "type": "error",
                "error": {
                    "type": "timeout_error",
                    "message": "Upstream request timed out."
                }
            })
        except (httpx.ConnectError, httpx.NetworkError):
            yield format_sse("error", {
                "type": "error",
                "error": {
                    "type": "connection_error",
                    "message": "Connection to upstream model failed."
                }
            })
        except Exception:
            yield format_sse("error", {
                "type": "error",
                "error": {
                    "type": "api_error",
                    "message": "Internal streaming error."
                }
            })

    @classmethod
    async def handle_openai_chat_completions(cls, body: Dict[str, Any]) -> Tuple[bool, Any]:
        cls._check_upstream_configured()
        is_stream = bool(body.get("stream", False))
        endpoint = f"{config.settings.upstream_base_url}/chat/completions"
        headers = cls.get_upstream_headers()

        if httpx is None:
            return False, {"id": "mock_id", "choices": [{"message": {"role": "assistant", "content": "mock"}}]}

        timeout = httpx.Timeout(config.settings.request_timeout_seconds)

        if not is_stream:
            try:
                async with httpx.AsyncClient(timeout=timeout) as client:
                    resp = await client.post(endpoint, json=body, headers=headers)
                    if resp.status_code != 200:
                        detail = resp.text
                        code = resp.status_code if resp.status_code in (400, 401, 403, 404, 429) else 502
                        raise UpstreamError(code, "upstream_error", detail)
                    return False, resp.json()
            except httpx.TimeoutException:
                raise UpstreamError(504, "timeout_error", "Upstream chat completion timed out.")
            except httpx.ConnectError:
                raise UpstreamError(502, "connection_error", "Failed to connect to upstream.")

        async def openai_stream() -> AsyncIterator[bytes]:
            try:
                async with httpx.AsyncClient(timeout=timeout) as client:
                    async with client.stream("POST", endpoint, json=body, headers=headers) as resp:
                        if resp.status_code != 200:
                            err_bytes = await resp.aread()
                            yield f"data: {err_bytes.decode(errors='replace')}\n\n".encode("utf-8")
                            return
                        async for line in resp.aiter_lines():
                            if line:
                                yield f"{line}\n\n".encode("utf-8")
            except Exception as e:
                err_payload = json.dumps({"error": {"message": str(e), "type": "gateway_error"}})
                yield f"data: {err_payload}\n\n".encode("utf-8")

        return True, openai_stream()

    @classmethod
    async def handle_image_generation(cls, body: Dict[str, Any]) -> Dict[str, Any]:
        cls._check_upstream_configured()
        endpoint = f"{config.settings.upstream_base_url}/images/generations"
        headers = cls.get_upstream_headers()

        if httpx is None:
            return {"data": [{"b64_json": "AAAA"}]}

        timeout = httpx.Timeout(config.settings.request_timeout_seconds)
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                resp = await client.post(endpoint, json=body, headers=headers)
                if resp.status_code != 200:
                    code = resp.status_code if resp.status_code in (400, 401, 403, 404, 429) else 502
                    raise UpstreamError(code, "upstream_error", resp.text)
                return resp.json()
        except httpx.TimeoutException:
            raise UpstreamError(504, "timeout_error", "Image generation timed out.")
        except httpx.ConnectError:
            raise UpstreamError(502, "connection_error", "Failed to connect to upstream for image generation.")
