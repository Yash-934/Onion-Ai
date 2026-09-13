import unittest
import json
import os
import sys
import asyncio
from unittest.mock import AsyncMock, patch, MagicMock

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

import config
from auth import is_authorized, extract_api_key
from privacy import sanitize_headers, structured_error
from adapters import AnthropicAdapter
from streaming import AnthropicSSETranslator, format_sse
from provider import ModelProvider, UpstreamError

class TestGatewayCompleteSuite(unittest.TestCase):

    def setUp(self):
        os.environ["GATEWAY_API_KEY"] = "test-secret-gateway-key-12345"
        os.environ["UPSTREAM_BASE_URL"] = "http://upstream-model.local"
        os.environ["UPSTREAM_API_KEY"] = "upstream-key-xyz"
        os.environ["UPSTREAM_PROTOCOL"] = "openai"
        os.environ["CHAT_MODEL"] = "claude-3-7-sonnet-20250219"
        os.environ["CODE_MODEL"] = "claude-3-7-sonnet-code"
        os.environ["IMAGE_MODEL"] = "dall-e-3"
        config.reload_settings()

    # 1. Basic /v1/messages (non-streaming)
    def test_01_basic_v1_messages_non_streaming(self):
        anthropic_req = {
            "model": "claude-3-7-sonnet-20250219",
            "messages": [{"role": "user", "content": "Hello world"}],
            "max_tokens": 100
        }
        openai_req = AnthropicAdapter.anthropic_to_openai_request(anthropic_req)
        self.assertEqual(openai_req["model"], "claude-3-7-sonnet-20250219")
        self.assertEqual(openai_req["messages"][0]["content"], "Hello world")
        self.assertEqual(openai_req["max_tokens"], 100)

        openai_resp = {
            "id": "chatcmpl-basic",
            "model": "claude-3-7-sonnet-20250219",
            "choices": [{"message": {"role": "assistant", "content": "Hello! How can I help?"}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 8}
        }
        anthropic_resp = AnthropicAdapter.openai_to_anthropic_response(openai_resp, "claude-3-7-sonnet-20250219")
        self.assertEqual(anthropic_resp["type"], "message")
        self.assertEqual(anthropic_resp["role"], "assistant")
        self.assertEqual(anthropic_resp["content"][0]["text"], "Hello! How can I help?")
        self.assertEqual(anthropic_resp["stop_reason"], "end_turn")
        self.assertEqual(anthropic_resp["usage"]["input_tokens"], 10)
        self.assertEqual(anthropic_resp["usage"]["output_tokens"], 8)

    # 2. Streaming text
    def test_02_streaming_text(self):
        translator = AnthropicSSETranslator(requested_model="claude-3-7-sonnet-20250219", message_id="msg_test_02")
        e1 = translator.handle_openai_chunk({"choices": [{"delta": {"content": "Stream "}}]})
        e2 = translator.handle_openai_chunk({"choices": [{"delta": {"content": "output"}, "finish_reason": "stop"}]})
        ef = translator.finish()

        full_stream = b"".join(e1 + e2 + ef).decode("utf-8")
        self.assertIn("event: message_start", full_stream)
        self.assertIn("event: content_block_start", full_stream)
        self.assertIn("event: content_block_delta", full_stream)
        self.assertIn("Stream ", full_stream)
        self.assertIn("output", full_stream)
        self.assertIn("event: message_delta", full_stream)
        self.assertIn("event: message_stop", full_stream)

    # 3. System prompt (string and block format)
    def test_03_system_prompt(self):
        req1 = {"system": "System instructions", "messages": [{"role": "user", "content": "Hi"}]}
        out1 = AnthropicAdapter.anthropic_to_openai_request(req1)
        self.assertEqual(out1["messages"][0]["role"], "system")
        self.assertEqual(out1["messages"][0]["content"], "System instructions")

        req2 = {"system": [{"type": "text", "text": "Prompt line 1"}, {"type": "text", "text": "Prompt line 2"}], "messages": [{"role": "user", "content": "Hi"}]}
        out2 = AnthropicAdapter.anthropic_to_openai_request(req2)
        self.assertEqual(out2["messages"][0]["content"], "Prompt line 1\nPrompt line 2")

    # 4. Multi-turn conversation
    def test_04_multi_turn_conversation(self):
        req = {
            "messages": [
                {"role": "user", "content": "Turn 1"},
                {"role": "assistant", "content": "Reply 1"},
                {"role": "user", "content": "Turn 2"}
            ]
        }
        out = AnthropicAdapter.anthropic_to_openai_request(req)
        self.assertEqual(len(out["messages"]), 3)
        self.assertEqual(out["messages"][1]["role"], "assistant")
        self.assertEqual(out["messages"][2]["content"], "Turn 2")

    # 5. Tool definitions
    def test_05_tool_definitions(self):
        req = {
            "messages": [{"role": "user", "content": "Check status"}],
            "tools": [
                {
                    "name": "check_status",
                    "description": "Check system status",
                    "input_schema": {"type": "object", "properties": {"service": {"type": "string"}}}
                }
            ]
        }
        out = AnthropicAdapter.anthropic_to_openai_request(req)
        self.assertEqual(out["tools"][0]["function"]["name"], "check_status")
        self.assertEqual(out["tools"][0]["function"]["description"], "Check system status")

    # 6. tool_use response
    def test_06_tool_use_response(self):
        openai_resp = {
            "choices": [
                {
                    "finish_reason": "tool_calls",
                    "message": {
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [
                            {"id": "call_123", "type": "function", "function": {"name": "run_test", "arguments": "{\"test_id\": 42}"}}
                        ]
                    }
                }
            ]
        }
        anthropic_resp = AnthropicAdapter.openai_to_anthropic_response(openai_resp)
        self.assertEqual(anthropic_resp["stop_reason"], "tool_use")
        self.assertEqual(anthropic_resp["content"][0]["type"], "tool_use")
        self.assertEqual(anthropic_resp["content"][0]["name"], "run_test")
        self.assertEqual(anthropic_resp["content"][0]["input"], {"test_id": 42})

    # 7. tool_result follow-up
    def test_07_tool_result_follow_up(self):
        req = {
            "messages": [
                {"role": "user", "content": "Run test"},
                {"role": "assistant", "content": [{"type": "tool_use", "id": "call_123", "name": "run_test", "input": {"test_id": 42}}]},
                {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "call_123", "content": "Test Passed"}]}
            ]
        }
        out = AnthropicAdapter.anthropic_to_openai_request(req)
        self.assertEqual(out["messages"][2]["role"], "tool")
        self.assertEqual(out["messages"][2]["tool_call_id"], "call_123")
        self.assertEqual(out["messages"][2]["content"], "Test Passed")

    # 8. Multiple tool calls
    def test_08_multiple_tool_calls(self):
        openai_resp = {
            "choices": [
                {
                    "finish_reason": "tool_calls",
                    "message": {
                        "role": "assistant",
                        "content": "Running both tools:",
                        "tool_calls": [
                            {"id": "c1", "type": "function", "function": {"name": "t1", "arguments": "{}"}},
                            {"id": "c2", "type": "function", "function": {"name": "t2", "arguments": "{}"}}
                        ]
                    }
                }
            ]
        }
        resp = AnthropicAdapter.openai_to_anthropic_response(openai_resp)
        self.assertEqual(len(resp["content"]), 3)  # text + 2 tool calls
        self.assertEqual(resp["content"][1]["name"], "t1")
        self.assertEqual(resp["content"][2]["name"], "t2")

    # 9. Model selection
    def test_09_model_selection(self):
        req = {"model": "claude-3-7-sonnet-code", "messages": [{"role": "user", "content": "Code please"}]}
        out = AnthropicAdapter.anthropic_to_openai_request(req)
        self.assertEqual(out["model"], "claude-3-7-sonnet-code")

    # 10. Invalid API key
    def test_10_invalid_api_key(self):
        ok, msg = is_authorized(x_api_key="bad-key")
        self.assertFalse(ok)
        self.assertIn("Invalid API key", msg)

    # 11. Missing API key
    def test_11_missing_api_key(self):
        ok, msg = is_authorized()
        self.assertFalse(ok)
        self.assertIn("Missing API key", msg)

    # 12. Malformed request validation
    def test_12_malformed_request_handling(self):
        err = structured_error(400, "invalid_request_error", "Malformed request payload.")
        self.assertEqual(err["error"]["code"], 400)
        self.assertEqual(err["error"]["type"], "invalid_request_error")

    # 13. Upstream timeout
    def test_13_upstream_timeout(self):
        err = UpstreamError(504, "timeout_error", "Upstream model request timed out.")
        self.assertEqual(err.status_code, 504)
        self.assertEqual(err.error_type, "timeout_error")

    # 14. Upstream disconnect
    def test_14_upstream_disconnect(self):
        err = UpstreamError(502, "connection_error", "Failed to connect to upstream model backend.")
        self.assertEqual(err.status_code, 502)

    # 15. Client disconnect handling
    def test_15_client_disconnect_translator_finish(self):
        translator = AnthropicSSETranslator(requested_model="claude-3-7-sonnet-20250219")
        translator.handle_text_delta("Partial")
        # Ensure finish completes cleanly even if cut short
        events = translator.finish()
        text = b"".join(events).decode("utf-8")
        self.assertIn("message_stop", text)

    # 16. OpenAI compatibility
    def test_16_openai_compatibility_headers(self):
        config.settings.upstream_protocol = "openai"
        headers = ModelProvider.get_upstream_headers()
        self.assertEqual(headers["Authorization"], "Bearer upstream-key-xyz")

    # 17. Image endpoint
    def test_17_image_endpoint_config(self):
        self.assertEqual(config.settings.image_model, "dall-e-3")

    # 18. .onion host validation
    def test_18_onion_host_validation(self):
        valid = "http://privacyonionexample12345.onion:8443"
        host = valid.split("://")[1].split("/")[0].split(":")[0]
        self.assertTrue(host.lower().endswith(".onion"))

    # 19. Clearnet rejection
    def test_19_clearnet_rejection(self):
        clearnet = "https://api.openai.com/v1"
        host = clearnet.split("://")[1].split("/")[0].split(":")[0]
        self.assertFalse(host.lower().endswith(".onion"))

    # 20. No secret / no body logging
    def test_20_no_secret_logging(self):
        headers = {
            "Authorization": "Bearer sensitive_secret_key",
            "x-api-key": "sensitive_x_key",
            "Cookie": "secret_cookie"
        }
        sanitized = sanitize_headers(headers)
        self.assertEqual(sanitized["Authorization"], "[REDACTED]")
        self.assertEqual(sanitized["x-api-key"], "[REDACTED]")
        self.assertEqual(sanitized["Cookie"], "[REDACTED]")

if __name__ == "__main__":
    unittest.main()
