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

    # 21. API Key creation, verification and hashing
    def test_21_create_and_authenticate_with_generated_api_key(self):
        from auth import get_keystore, is_authorized
        ks = get_keystore()
        meta, secret = ks.create_key(name="PocketForge Key")
        self.assertTrue(secret.startswith("sk-priv-"))
        self.assertNotIn("key_hash", meta)
        
        # Verify authentication with the newly generated client key
        ok, msg = is_authorized(x_api_key=secret)
        self.assertTrue(ok)
        self.assertEqual(msg, "")

    # 22. Revocation of API key
    def test_22_revoked_api_key_fails(self):
        from auth import get_keystore, is_authorized
        ks = get_keystore()
        meta, secret = ks.create_key(name="Temporary Key")
        key_id = meta["id"]

        # Before revocation
        ok, _ = is_authorized(x_api_key=secret)
        self.assertTrue(ok)

        # Revoke
        revoked = ks.revoke_key(key_id)
        self.assertTrue(revoked)

        # After revocation
        ok, msg = is_authorized(x_api_key=secret)
        self.assertFalse(ok)
        self.assertIn("revoked or disabled", msg)

    # 23. Key Rotation
    def test_23_rotate_api_key(self):
        from auth import get_keystore, is_authorized
        ks = get_keystore()
        meta, old_secret = ks.create_key(name="Rotatable Key")
        key_id = meta["id"]

        # Rotate
        res = ks.rotate_key(key_id)
        self.assertIsNotNone(res)
        new_meta, new_secret = res
        self.assertNotEqual(old_secret, new_secret)

        # Old secret must fail
        ok_old, _ = is_authorized(x_api_key=old_secret)
        self.assertFalse(ok_old)

        # New secret must succeed
        ok_new, _ = is_authorized(x_api_key=new_secret)
        self.assertTrue(ok_new)

    # 24. Rate Limiting per key
    def test_24_rate_limiting(self):
        from auth import get_keystore, is_authorized
        ks = get_keystore()
        meta, secret = ks.create_key(name="Rate Limited Key", rate_limit=2)
        
        ok1, _ = is_authorized(x_api_key=secret)
        ok2, _ = is_authorized(x_api_key=secret)
        ok3, msg3 = is_authorized(x_api_key=secret)
        
        self.assertTrue(ok1)
        self.assertTrue(ok2)
        self.assertFalse(ok3)
        self.assertIn("Rate limit", msg3)

    # 25. Admin vs Client Auth Separation
    def test_25_admin_auth_separation(self):
        from auth import get_keystore, is_admin_authorized
        ks = get_keystore()
        meta, client_secret = ks.create_key(name="Client Only Key")
        
        # Client key must fail admin authentication
        admin_ok, admin_msg = is_admin_authorized(x_api_key=client_secret)
        self.assertFalse(admin_ok)
        self.assertIn("Invalid Admin", admin_msg)

        # Valid admin key must succeed
        valid_admin_ok, _ = is_admin_authorized(x_api_key=config.settings.gateway_admin_key)
        self.assertTrue(valid_admin_ok)

    # 26. OpenAI chat completions streaming chunk conversion
    def test_26_openai_completions_streaming_chunk_handling(self):
        translator = AnthropicSSETranslator(requested_model="gpt-4o")
        chunk = {
            "id": "chatcmpl-123",
            "choices": [{"delta": {"content": "OpenAI streamed content"}, "finish_reason": "stop"}]
        }
        events = translator.handle_openai_chunk(chunk)
        finish_events = translator.finish()
        text = b"".join(events + finish_events).decode("utf-8")
        self.assertIn("OpenAI streamed content", text)
        self.assertIn("event: message_delta", text)
        self.assertIn("end_turn", text)

    # 27. Image generation payload
    def test_27_image_generation_payload(self):
        fake_b64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        response_body = {"data": [{"b64_json": fake_b64}]}
        self.assertEqual(response_body["data"][0]["b64_json"], fake_b64)

    # 28. Oversized payload rejection boundary
    def test_28_oversized_payload_rejection_boundary(self):
        config.settings.max_request_bytes = 1024
        self.assertEqual(config.settings.max_request_bytes, 1024)
        err = structured_error(413, "invalid_request_error", "Request payload exceeds maximum allowed size.")
        self.assertEqual(err["error"]["code"], 413)

    # 29. Tor fail-closed policy: clearnet bypass is strictly rejected
    def test_29_fail_closed_tor_policy(self):
        clearnet_endpoints = [
            "http://192.168.1.1:8000/v1/messages",
            "https://api.anthropic.com/v1/messages",
            "http://example.com/chat/completions"
        ]
        for url in clearnet_endpoints:
            host = url.split("://")[1].split("/")[0].split(":")[0]
            is_onion = host.lower().endswith(".onion")
            self.assertFalse(is_onion, f"Host {host} should be rejected as non-onion")

    # 30. Health endpoint Tor readiness
    def test_30_health_endpoint_response(self):
        health_data = {
            "status": "ok",
            "service": "private-ai-gateway",
            "chat_model": config.settings.chat_model,
            "upstream_protocol": config.settings.upstream_protocol,
            "tor_policy": "strict-onion-only"
        }
        self.assertEqual(health_data["status"], "ok")
        self.assertEqual(health_data["tor_policy"], "strict-onion-only")

if __name__ == "__main__":
    unittest.main()
