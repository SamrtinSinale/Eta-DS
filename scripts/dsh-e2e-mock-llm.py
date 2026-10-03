#!/usr/bin/env python3
"""端到端冒烟测试用的假模型：按 Anthropic Messages 协议（SSE）回应。

dsh 的 `llm-deepseek` 走的是 Messages 协议：`POST {baseURL}/messages`，请求体
`{model, stream: true, messages, max_tokens, thinking, output_config}`，响应是
`message_start / content_block_start / content_block_delta / content_block_stop /
message_delta / message_stop` 这串 SSE 事件（见 dsh-llm-deepseek/lib/index.js）。

行为（判据是**最后一条消息**：续接后的请求里带着上一回合的 tool_result，不能被它带偏）：
  - 最后一条消息不是 tool_result → 要求调用 bash（写一个标记文件并把它 echo 出来）
  - 最后一条消息是 tool_result → 回一句文本并 stop_reason=end_turn，收尾

用法：dsh-e2e-mock-llm.py <port>
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from itertools import count

MARKER = "heta-smoke-ok"
COMMAND = f"echo {MARKER} > /workspace/smoke.txt && echo {MARKER}"

# 把每次请求原样落盘（JSONL）。第二回合要靠它断言两件事：
#   1. 续接之后模型的 system 里是不是**新**人格（systemPromptUpdate: in-history 的语义）
#   2. 历史是不是原样带着（而不是被压成摘要）
DUMP_PATH = os.environ.get("DSH_E2E_MOCK_DUMP", "")
_request_numbers = count(1)


def sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def tool_use_stream() -> list[str]:
    payload = json.dumps({"command": COMMAND, "description": "Write smoke marker file"})
    return [
        sse("message_start", {"type": "message_start", "message": {
            "id": "msg_smoke_1", "type": "message", "role": "assistant", "model": "mock",
            "content": [], "stop_reason": None, "stop_sequence": None,
            "usage": {"input_tokens": 10, "output_tokens": 0},
        }}),
        sse("content_block_start", {"type": "content_block_start", "index": 0,
                                    "content_block": {"type": "tool_use", "id": "toolu_smoke_1",
                                                      "name": "bash", "input": {}}}),
        sse("content_block_delta", {"type": "content_block_delta", "index": 0,
                                    "delta": {"type": "input_json_delta", "partial_json": payload}}),
        sse("content_block_stop", {"type": "content_block_stop", "index": 0}),
        sse("message_delta", {"type": "message_delta",
                              "delta": {"stop_reason": "tool_use", "stop_sequence": None},
                              "usage": {"output_tokens": 20}}),
        sse("message_stop", {"type": "message_stop"}),
    ]


def text_stream() -> list[str]:
    return [
        sse("message_start", {"type": "message_start", "message": {
            "id": "msg_smoke_2", "type": "message", "role": "assistant", "model": "mock",
            "content": [], "stop_reason": None, "stop_sequence": None,
            "usage": {"input_tokens": 10, "output_tokens": 0},
        }}),
        sse("content_block_start", {"type": "content_block_start", "index": 0,
                                    "content_block": {"type": "text", "text": ""}}),
        sse("content_block_delta", {"type": "content_block_delta", "index": 0,
                                    "delta": {"type": "text_delta", "text": "smoke 完成"}}),
        sse("content_block_stop", {"type": "content_block_stop", "index": 0}),
        sse("message_delta", {"type": "message_delta",
                              "delta": {"stop_reason": "end_turn", "stop_sequence": None},
                              "usage": {"output_tokens": 5}}),
        sse("message_stop", {"type": "message_stop"}),
    ]


def awaiting_tool_result(body: dict) -> bool:
    """**最后一条消息**里有没有 tool_result —— 也就是"工具刚跑完，该收尾了吗"。

    不能看"整段历史里有没有 tool_result"：续接（session/resume）后的请求必然带着上一回合的
    结果，那样第二回合一开始就会被判成该收尾，工具链路根本没被走一遍。
    """
    messages = body.get("messages") or []
    if not messages:
        return False
    content = messages[-1].get("content")
    if not isinstance(content, list):
        return False
    return any(isinstance(block, dict) and block.get("type") == "tool_result" for block in content)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args) -> None:  # 安静点
        pass

    def do_POST(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler 的命名)
        length = int(self.headers.get("content-length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        if not self.path.endswith("/messages"):
            self.send_error(404, "only /messages is mocked")
            return
        try:
            body = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            self.send_error(400, "bad json")
            return

        if DUMP_PATH:
            with open(DUMP_PATH, "a", encoding="utf-8") as handle:
                handle.write(json.dumps({"n": next(_request_numbers), "body": body},
                                        ensure_ascii=False) + "\n")

        finishing = awaiting_tool_result(body)
        chunks = text_stream() if finishing else tool_use_stream()
        print(f"[mock-llm] {'收尾' if finishing else '要求调用 bash'} "
              f"(messages={len(body.get('messages') or [])})", flush=True)
        self.send_response(200)
        self.send_header("content-type", "text/event-stream")
        self.send_header("cache-control", "no-cache")
        self.send_header("connection", "close")
        self.end_headers()
        for chunk in chunks:
            self.wfile.write(chunk.encode())
            self.wfile.flush()


def main() -> int:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"[mock-llm] listening on 127.0.0.1:{port}", flush=True)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
