import json
import uuid
from typing import Any, AsyncIterator, Dict, List, Optional
import config

def format_sse(event: str, data: Dict[str, Any]) -> bytes:
    """
    Format an SSE event conforming to the Anthropic SSE specification.
    """
    payload = json.dumps(data, separators=(',', ':'), ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n".encode("utf-8")

class AnthropicSSETranslator:
    """
    Maintains state across an upstream OpenAI stream or generic chunks and translates
    them into a strict sequence of Anthropic SSE events.
    """

    def __init__(self, requested_model: str, message_id: Optional[str] = None):
        self.requested_model = requested_model or config.settings.chat_model
        self.message_id = message_id or f"msg_{uuid.uuid4().hex[:16]}"
        if not self.message_id.startswith("msg_"):
            self.message_id = f"msg_{self.message_id}"
        
        self.message_started = False
        self.current_content_block_index = -1
        self.text_block_active = False
        
        # Tracking tool calls: map openai tool index / id -> anthropic content block index
        self.active_tool_calls: Dict[int, Dict[str, Any]] = {}
        self.tool_index_to_block_index: Dict[int, int] = {}
        
        self.stop_reason: Optional[str] = None
        self.output_tokens = 0
        self.input_tokens = 0

    def start_message_events(self, input_tokens: int = 0) -> List[bytes]:
        if self.message_started:
            return []
        self.message_started = True
        self.input_tokens = input_tokens
        event = {
            "type": "message_start",
            "message": {
                "id": self.message_id,
                "type": "message",
                "role": "assistant",
                "model": self.requested_model,
                "content": [],
                "stop_reason": None,
                "stop_sequence": None,
                "usage": {
                    "input_tokens": self.input_tokens,
                    "output_tokens": 0
                }
            }
        }
        return [format_sse("message_start", event)]

    def handle_text_delta(self, text: str) -> List[bytes]:
        events = []
        if not self.message_started:
            events.extend(self.start_message_events())

        if not self.text_block_active:
            self.current_content_block_index += 1
            self.text_block_active = True
            events.append(format_sse("content_block_start", {
                "type": "content_block_start",
                "index": self.current_content_block_index,
                "content_block": {
                    "type": "text",
                    "text": ""
                }
            }))

        events.append(format_sse("content_block_delta", {
            "type": "content_block_delta",
            "index": self.current_content_block_index,
            "delta": {
                "type": "text_delta",
                "text": text
            }
        }))
        self.output_tokens += max(1, len(text) // 4)
        return events

    def close_text_block_if_active(self) -> List[bytes]:
        events = []
        if self.text_block_active:
            events.append(format_sse("content_block_stop", {
                "type": "content_block_stop",
                "index": self.current_content_block_index
            }))
            self.text_block_active = False
        return events

    def handle_tool_call_delta(self, tc_chunk: Dict[str, Any]) -> List[bytes]:
        events = []
        if not self.message_started:
            events.extend(self.start_message_events())

        # Close any open text block first
        events.extend(self.close_text_block_if_active())

        raw_idx = tc_chunk.get("index", 0)
        tool_id = tc_chunk.get("id")
        func_chunk = tc_chunk.get("function") or {}
        fn_name = func_chunk.get("name")
        fn_args_chunk = func_chunk.get("arguments")

        if raw_idx not in self.active_tool_calls:
            # New tool call block
            self.current_content_block_index += 1
            block_idx = self.current_content_block_index
            self.tool_index_to_block_index[raw_idx] = block_idx
            
            call_id = tool_id or f"call_{uuid.uuid4().hex[:12]}"
            name = fn_name or "tool"
            
            self.active_tool_calls[raw_idx] = {
                "id": call_id,
                "name": name,
                "block_index": block_idx
            }

            events.append(format_sse("content_block_start", {
                "type": "content_block_start",
                "index": block_idx,
                "content_block": {
                    "type": "tool_use",
                    "id": call_id,
                    "name": name,
                    "input": {}
                }
            }))

        block_idx = self.tool_index_to_block_index[raw_idx]

        if fn_args_chunk:
            events.append(format_sse("content_block_delta", {
                "type": "content_block_delta",
                "index": block_idx,
                "delta": {
                    "type": "input_json_delta",
                    "partial_json": fn_args_chunk
                }
            }))
            self.output_tokens += max(1, len(fn_args_chunk) // 4)

        return events

    def handle_openai_chunk(self, chunk: Dict[str, Any]) -> List[bytes]:
        events = []
        choices = chunk.get("choices") or []
        if not choices:
            # Check for usage in chunk
            usage = chunk.get("usage")
            if usage and isinstance(usage, dict):
                self.output_tokens = usage.get("completion_tokens", self.output_tokens)
            return []

        choice = choices[0]
        delta = choice.get("delta") or {}
        finish_reason = choice.get("finish_reason")

        # 1. Text delta
        text = delta.get("content")
        if text:
            events.extend(self.handle_text_delta(text))

        # 2. Tool calls delta
        tool_calls = delta.get("tool_calls") or []
        for tc in tool_calls:
            events.extend(self.handle_tool_call_delta(tc))

        # 3. Finish reason tracking
        if finish_reason:
            if finish_reason == "tool_calls" or (self.active_tool_calls and finish_reason != "length"):
                self.stop_reason = "tool_use"
            elif finish_reason == "length":
                self.stop_reason = "max_tokens"
            elif finish_reason == "stop":
                self.stop_reason = "end_turn"
            else:
                self.stop_reason = "end_turn"

        return events

    def finish(self) -> List[bytes]:
        events = []
        if not self.message_started:
            events.extend(self.start_message_events())

        # Close open text block
        events.extend(self.close_text_block_if_active())

        # Close any open tool use blocks
        for raw_idx, tool_info in self.active_tool_calls.items():
            block_idx = tool_info["block_index"]
            events.append(format_sse("content_block_stop", {
                "type": "content_block_stop",
                "index": block_idx
            }))

        final_stop_reason = self.stop_reason or ("tool_use" if self.active_tool_calls else "end_turn")

        # message_delta
        events.append(format_sse("message_delta", {
            "type": "message_delta",
            "delta": {
                "stop_reason": final_stop_reason,
                "stop_sequence": None
            },
            "usage": {
                "output_tokens": self.output_tokens
            }
        }))

        # message_stop
        events.append(format_sse("message_stop", {
            "type": "message_stop"
        }))

        return events
