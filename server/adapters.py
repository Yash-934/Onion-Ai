import json
import uuid
from typing import Any, Dict, List, Optional, Tuple, Union
import config

class AnthropicAdapter:
    """
    Translates between Anthropic Messages API formats and OpenAI / Normalized internal formats.
    """

    @staticmethod
    def extract_system_prompt(system_field: Any) -> Optional[str]:
        if not system_field:
            return None
        if isinstance(system_field, str):
            return system_field
        if isinstance(system_field, list):
            texts = []
            for item in system_field:
                if isinstance(item, dict) and item.get("type") == "text":
                    texts.append(item.get("text", ""))
                elif isinstance(item, str):
                    texts.append(item)
            return "\n".join(texts)
        return str(system_field)

    @classmethod
    def anthropic_to_openai_request(cls, body: Dict[str, Any]) -> Dict[str, Any]:
        """
        Converts an Anthropic Messages request into an OpenAI Chat Completions request.
        Preserves all tool definitions, system prompts, tool_use, tool_result, images, and parameters.
        """
        openai_req: Dict[str, Any] = {
            "model": body.get("model") or config.settings.chat_model,
            "messages": [],
        }

        # 1. System prompt
        system_text = cls.extract_system_prompt(body.get("system"))
        if system_text:
            openai_req["messages"].append({
                "role": "system",
                "content": system_text
            })

        # 2. Messages & Content Blocks (including tools & images)
        raw_messages = body.get("messages", [])
        for msg in raw_messages:
            role = msg.get("role", "user")
            content = msg.get("content")

            if isinstance(content, str):
                openai_req["messages"].append({"role": role, "content": content})
            elif isinstance(content, list):
                # Content blocks
                if role == "user":
                    user_text_parts: List[str] = []
                    openai_content_parts: List[Dict[str, Any]] = []
                    has_multimodal = False

                    for block in content:
                        if not isinstance(block, dict):
                            continue
                        b_type = block.get("type")

                        if b_type == "text":
                            txt = block.get("text", "")
                            user_text_parts.append(txt)
                            openai_content_parts.append({"type": "text", "text": txt})

                        elif b_type == "image":
                            has_multimodal = True
                            source = block.get("source", {})
                            s_type = source.get("type")
                            media_type = source.get("media_type", "image/png")
                            data = source.get("data", "")
                            if s_type == "base64" and data:
                                url = f"data:{media_type};base64,{data}"
                                openai_content_parts.append({
                                    "type": "image_url",
                                    "image_url": {"url": url}
                                })

                        elif b_type == "tool_result":
                            # A tool_result block in Anthropic represents output of a tool call
                            tool_use_id = block.get("tool_use_id", "")
                            res_content = block.get("content", "")
                            if isinstance(res_content, list):
                                res_text = "\n".join(
                                    b.get("text", "") for b in res_content
                                    if isinstance(b, dict) and b.get("type") == "text"
                                )
                            elif isinstance(res_content, dict):
                                res_text = json.dumps(res_content)
                            else:
                                res_text = str(res_content)

                            openai_req["messages"].append({
                                "role": "tool",
                                "tool_call_id": tool_use_id,
                                "content": res_text
                            })

                    # If there were regular user text/image blocks alongside tool_results
                    if has_multimodal:
                        openai_req["messages"].append({
                            "role": "user",
                            "content": openai_content_parts
                        })
                    elif user_text_parts:
                        openai_req["messages"].append({
                            "role": "user",
                            "content": "\n".join(user_text_parts)
                        })

                elif role == "assistant":
                    text_parts: List[str] = []
                    tool_calls: List[Dict[str, Any]] = []

                    for block in content:
                        if not isinstance(block, dict):
                            continue
                        b_type = block.get("type")
                        if b_type == "text":
                            text_parts.append(block.get("text", ""))
                        elif b_type == "tool_use":
                            tool_id = block.get("id") or f"call_{uuid.uuid4().hex[:12]}"
                            tool_name = block.get("name", "")
                            tool_input = block.get("input", {})
                            if isinstance(tool_input, dict):
                                args_str = json.dumps(tool_input)
                            else:
                                args_str = str(tool_input)

                            tool_calls.append({
                                "id": tool_id,
                                "type": "function",
                                "function": {
                                    "name": tool_name,
                                    "arguments": args_str
                                }
                            })

                    assistant_msg: Dict[str, Any] = {
                        "role": "assistant",
                        "content": "\n".join(text_parts) if text_parts else None
                    }
                    if tool_calls:
                        assistant_msg["tool_calls"] = tool_calls
                    openai_req["messages"].append(assistant_msg)
            else:
                openai_req["messages"].append({"role": role, "content": str(content or "")})

        # 3. Parameters
        if "max_tokens" in body:
            openai_req["max_tokens"] = body["max_tokens"]
        if "temperature" in body:
            openai_req["temperature"] = body["temperature"]
        if "top_p" in body:
            openai_req["top_p"] = body["top_p"]
        if "stop_sequences" in body:
            openai_req["stop"] = body["stop_sequences"]
        if "stream" in body:
            openai_req["stream"] = bool(body["stream"])

        # 4. Tools & Tool Choice
        raw_tools = body.get("tools")
        if raw_tools and isinstance(raw_tools, list):
            openai_tools = []
            for t in raw_tools:
                if isinstance(t, dict):
                    openai_tools.append({
                        "type": "function",
                        "function": {
                            "name": t.get("name", ""),
                            "description": t.get("description", ""),
                            "parameters": t.get("input_schema", {"type": "object", "properties": {}})
                        }
                    })
            if openai_tools:
                openai_req["tools"] = openai_tools

        tool_choice = body.get("tool_choice")
        if tool_choice:
            if isinstance(tool_choice, str):
                if tool_choice.lower() in ("auto", "none", "required"):
                    openai_req["tool_choice"] = tool_choice.lower()
                elif tool_choice.lower() == "any":
                    openai_req["tool_choice"] = "required"
            elif isinstance(tool_choice, dict):
                tc_type = tool_choice.get("type")
                if tc_type == "auto":
                    openai_req["tool_choice"] = "auto"
                elif tc_type == "any":
                    openai_req["tool_choice"] = "required"
                elif tc_type == "tool":
                    name = tool_choice.get("name")
                    if name:
                        openai_req["tool_choice"] = {
                            "type": "function",
                            "function": {"name": name}
                        }

        return openai_req

    @classmethod
    def openai_to_anthropic_response(cls, openai_resp: Dict[str, Any], requested_model: Optional[str] = None) -> Dict[str, Any]:
        """
        Converts an OpenAI non-streaming chat completion response into an Anthropic Messages response.
        """
        msg_id = openai_resp.get("id") or f"msg_{uuid.uuid4().hex[:16]}"
        if not msg_id.startswith("msg_"):
            msg_id = f"msg_{msg_id}"
        
        model = requested_model or openai_resp.get("model") or config.settings.chat_model
        choices = openai_resp.get("choices") or []
        first_choice = choices[0] if choices else {}
        choice_msg = first_choice.get("message") or {}
        finish_reason = first_choice.get("finish_reason")

        content_blocks: List[Dict[str, Any]] = []

        # Text content
        text_content = choice_msg.get("content")
        if text_content:
            content_blocks.append({
                "type": "text",
                "text": text_content
            })

        # Tool calls
        tool_calls = choice_msg.get("tool_calls") or []
        for tc in tool_calls:
            func = tc.get("function") or {}
            fn_name = func.get("name", "")
            fn_args_raw = func.get("arguments", "{}")
            try:
                fn_args = json.loads(fn_args_raw) if isinstance(fn_args_raw, str) else fn_args_raw
            except Exception:
                fn_args = {"raw_arguments": str(fn_args_raw)}

            content_blocks.append({
                "type": "tool_use",
                "id": tc.get("id") or f"call_{uuid.uuid4().hex[:12]}",
                "name": fn_name,
                "input": fn_args
            })

        # Map stop reason
        stop_reason = "end_turn"
        if finish_reason == "tool_calls" or (tool_calls and finish_reason != "length"):
            stop_reason = "tool_use"
        elif finish_reason == "length":
            stop_reason = "max_tokens"
        elif finish_reason == "stop":
            stop_reason = "end_turn"

        usage_raw = openai_resp.get("usage") or {}
        input_tokens = usage_raw.get("prompt_tokens", 0)
        output_tokens = usage_raw.get("completion_tokens", 0)

        return {
            "id": msg_id,
            "type": "message",
            "role": "assistant",
            "model": model,
            "content": content_blocks,
            "stop_reason": stop_reason,
            "stop_sequence": None,
            "usage": {
                "input_tokens": input_tokens,
                "output_tokens": output_tokens
            }
        }
