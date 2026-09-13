import unittest
import json
import os
import sys
import asyncio
from unittest.mock import AsyncMock, patch, MagicMock

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from config import settings, reload_settings
from auth import is_authorized
from adapters import AnthropicAdapter
from provider import ModelProvider, UpstreamError
from streaming import AnthropicSSETranslator

class TestPocketForgeAgentWorkflow(unittest.TestCase):
    """
    Simulates PocketForge / Claude Code Agentic Workflow:
    1. Inspect project -> Generates tool_use (view_file)
    2. PocketForge executes tool in its sandbox -> Sends tool_result
    3. Model receives tool_result -> Generates edit_file tool_use
    4. PocketForge executes edit_file -> Sends tool_result
    5. Model concludes task with final reasoning response.
    """

    def setUp(self):
        os.environ["GATEWAY_API_KEY"] = "pocketforge-test-key"
        os.environ["UPSTREAM_BASE_URL"] = "http://mock-upstream:8000"
        os.environ["UPSTREAM_API_KEY"] = "upstream-private-key"
        os.environ["UPSTREAM_PROTOCOL"] = "openai"
        os.environ["CHAT_MODEL"] = "claude-3-7-sonnet-20250219"
        reload_settings()

    def test_pocketforge_claude_code_agentic_loop(self):
        # STEP 1: PocketForge asks Claude Code to inspect project
        step1_request = {
            "model": "claude-3-7-sonnet-20250219",
            "system": "You are Claude Code, an agentic coding assistant.",
            "messages": [
                {"role": "user", "content": "Please inspect MainActivity.kt and fix the imports."}
            ],
            "tools": [
                {
                    "name": "view_file",
                    "description": "View file contents",
                    "input_schema": {
                        "type": "object",
                        "properties": {
                            "path": {"type": "string"}
                        },
                        "required": ["path"]
                    }
                },
                {
                    "name": "edit_file",
                    "description": "Edit file contents",
                    "input_schema": {
                        "type": "object",
                        "properties": {
                            "path": {"type": "string"},
                            "content": {"type": "string"}
                        },
                        "required": ["path", "content"]
                    }
                }
            ],
            "tool_choice": "auto"
        }

        # Adapter translates Anthropic step 1 request to upstream format
        upstream_step1 = AnthropicAdapter.anthropic_to_openai_request(step1_request)
        self.assertEqual(len(upstream_step1["tools"]), 2)
        self.assertEqual(upstream_step1["messages"][0]["role"], "system")
        self.assertEqual(upstream_step1["messages"][1]["role"], "user")

        # Mock upstream response (model decides to call view_file)
        upstream_step1_resp = {
            "id": "chatcmpl-step1",
            "model": "claude-3-7-sonnet-20250219",
            "choices": [
                {
                    "finish_reason": "tool_calls",
                    "message": {
                        "role": "assistant",
                        "content": "I will inspect MainActivity.kt.",
                        "tool_calls": [
                            {
                                "id": "call_view_001",
                                "type": "function",
                                "function": {
                                    "name": "view_file",
                                    "arguments": json.dumps({"path": "MainActivity.kt"})
                                }
                            }
                        ]
                    }
                }
            ],
            "usage": {"prompt_tokens": 120, "completion_tokens": 45}
        }

        anthropic_step1_resp = AnthropicAdapter.openai_to_anthropic_response(upstream_step1_resp)
        self.assertEqual(anthropic_step1_resp["stop_reason"], "tool_use")
        self.assertEqual(anthropic_step1_resp["content"][1]["type"], "tool_use")
        self.assertEqual(anthropic_step1_resp["content"][1]["name"], "view_file")
        self.assertEqual(anthropic_step1_resp["content"][1]["input"], {"path": "MainActivity.kt"})

        # STEP 2: PocketForge executes view_file in its local sandbox and sends tool_result
        sandbox_file_content = "package com.privateai.app\nimport android.app.Activity\nclass MainActivity..."

        step2_request = {
            "model": "claude-3-7-sonnet-20250219",
            "system": "You are Claude Code, an agentic coding assistant.",
            "messages": [
                {"role": "user", "content": "Please inspect MainActivity.kt and fix the imports."},
                {
                    "role": "assistant",
                    "content": [
                        {"type": "text", "text": "I will inspect MainActivity.kt."},
                        {"type": "tool_use", "id": "call_view_001", "name": "view_file", "input": {"path": "MainActivity.kt"}}
                    ]
                },
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "tool_result",
                            "tool_use_id": "call_view_001",
                            "content": sandbox_file_content
                        }
                    ]
                }
            ],
            "tools": step1_request["tools"]
        }

        upstream_step2 = AnthropicAdapter.anthropic_to_openai_request(step2_request)
        # Verify tool_result converted to role: "tool" with matching tool_call_id
        tool_msg = [m for m in upstream_step2["messages"] if m.get("role") == "tool"][0]
        self.assertEqual(tool_msg["tool_call_id"], "call_view_001")
        self.assertIn("MainActivity", tool_msg["content"])

        # STEP 3: Model responds with edit_file tool_use
        upstream_step2_resp = {
            "id": "chatcmpl-step2",
            "model": "claude-3-7-sonnet-20250219",
            "choices": [
                {
                    "finish_reason": "tool_calls",
                    "message": {
                        "role": "assistant",
                        "content": "Now I will add the missing import.",
                        "tool_calls": [
                            {
                                "id": "call_edit_002",
                                "type": "function",
                                "function": {
                                    "name": "edit_file",
                                    "arguments": json.dumps({"path": "MainActivity.kt", "content": "import android.app.AlertDialog\n..."})
                                }
                            }
                        ]
                    }
                }
            ],
            "usage": {"prompt_tokens": 250, "completion_tokens": 60}
        }

        anthropic_step2_resp = AnthropicAdapter.openai_to_anthropic_response(upstream_step2_resp)
        self.assertEqual(anthropic_step2_resp["stop_reason"], "tool_use")
        self.assertEqual(anthropic_step2_resp["content"][1]["name"], "edit_file")

        # STEP 4: PocketForge executes edit_file and sends success tool_result
        step3_request = {
            "model": "claude-3-7-sonnet-20250219",
            "messages": [
                {"role": "user", "content": "Please inspect MainActivity.kt and fix the imports."},
                {
                    "role": "assistant",
                    "content": [
                        {"type": "tool_use", "id": "call_view_001", "name": "view_file", "input": {"path": "MainActivity.kt"}}
                    ]
                },
                {
                    "role": "user",
                    "content": [
                        {"type": "tool_result", "tool_use_id": "call_view_001", "content": sandbox_file_content}
                    ]
                },
                {
                    "role": "assistant",
                    "content": [
                        {"type": "tool_use", "id": "call_edit_002", "name": "edit_file", "input": {"path": "MainActivity.kt", "content": "..."}}
                    ]
                },
                {
                    "role": "user",
                    "content": [
                        {"type": "tool_result", "tool_use_id": "call_edit_002", "content": "Successfully updated MainActivity.kt"}
                    ]
                }
            ]
        }

        # STEP 5: Model completes with final reasoning
        upstream_step3_resp = {
            "id": "chatcmpl-step3",
            "model": "claude-3-7-sonnet-20250219",
            "choices": [
                {
                    "finish_reason": "stop",
                    "message": {
                        "role": "assistant",
                        "content": "I have successfully fixed the missing imports in MainActivity.kt.",
                        "tool_calls": None
                    }
                }
            ],
            "usage": {"prompt_tokens": 340, "completion_tokens": 30}
        }

        anthropic_step3_resp = AnthropicAdapter.openai_to_anthropic_response(upstream_step3_resp)
        self.assertEqual(anthropic_step3_resp["stop_reason"], "end_turn")
        self.assertEqual(len(anthropic_step3_resp["content"]), 1)
        self.assertEqual(anthropic_step3_resp["content"][0]["text"], "I have successfully fixed the missing imports in MainActivity.kt.")

if __name__ == "__main__":
    unittest.main()
