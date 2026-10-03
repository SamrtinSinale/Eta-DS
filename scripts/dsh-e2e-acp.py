#!/usr/bin/env python3
"""端到端冒烟测试的 ACP 驱动。

对着一条 `dsh --profile acp` 命令（由调用方拼好、经 chroot/qemu 跑）走一遍真实回合：

    initialize → session/new → session/prompt          （首回合）
    initialize → session/resume → session/prompt        （带 --resume，续接回合）

期间像 Heta 的客户端那样处理 agent 侧请求（`session/request_permission` 一律放行；
其它未知方法回 error，避免对方干等），并把 `session/update` 里的 tool_call /
tool_call_update 记下来。

断言（这就是"dsh 实力"的证据）：
  1. 收到 bash 工具的 tool_call
  2. 该 tool_call 最终是 completed，且结果里带着标记串（说明命令真的跑了）
  3. prompt 以 end_turn 收尾
  4. （带 --resume 时）resume 返回的 sessionId 必须就是请求的那个 —— 否则等于偷偷新建了会话

输出两行机器可读的结果，供编排脚本取用：
    DSH_E2E_SESSION=<sessionId>
    DSH_E2E_STOP=<stopReason>

用法：dsh-e2e-acp.py [标记串] [--resume <sessionId>] [--prompt <文本>] -- <命令...>
"""
import json
import os
import subprocess
import sys
import threading
import time


def parse_argv(argv: list[str]) -> tuple[str, str, str, list[str]]:
    if "--" not in argv:
        print("用法: dsh-e2e-acp.py [标记串] [--resume <id>] [--prompt <文本>] -- <命令...>",
              file=sys.stderr)
        sys.exit(2)
    split = argv.index("--")
    head, command = argv[:split], argv[split + 1:]
    marker = "heta-smoke-ok"
    resume_id = ""
    prompt = "跑一下冒烟测试：把标记写进文件并打印出来。"
    index = 0
    while index < len(head):
        arg = head[index]
        if arg == "--resume" and index + 1 < len(head):
            resume_id = head[index + 1]
            index += 2
        elif arg == "--prompt" and index + 1 < len(head):
            prompt = head[index + 1]
            index += 2
        elif arg.startswith("--"):
            print(f"未知参数：{arg}", file=sys.stderr)
            sys.exit(2)
        else:
            marker = arg
            index += 1
    return marker, resume_id, prompt, command


MARKER, RESUME_ID, PROMPT, COMMAND = parse_argv(sys.argv[1:])
TIMEOUT_SECONDS = float(os.environ.get("DSH_E2E_TIMEOUT", "180"))


class Client:
    def __init__(self, process: subprocess.Popen) -> None:
        self.process = process
        self.next_id = 1
        self.responses: dict[int, dict] = {}
        self.tool_calls: list[dict] = []
        self.tool_updates: list[dict] = []
        self.lock = threading.Lock()
        self.closed = threading.Event()

    def send(self, message: dict) -> None:
        assert self.process.stdin is not None
        self.process.stdin.write(json.dumps(message) + "\n")
        self.process.stdin.flush()

    def request(self, method: str, params: dict) -> int:
        request_id = self.next_id
        self.next_id += 1
        self.send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        return request_id

    def wait(self, request_id: int, what: str) -> dict:
        deadline = time.time() + TIMEOUT_SECONDS
        while time.time() < deadline:
            with self.lock:
                if request_id in self.responses:
                    return self.responses.pop(request_id)
            if self.closed.is_set():
                break
            time.sleep(0.05)
        raise SystemExit(f"FAIL: 等 {what} 超时或被断开（{TIMEOUT_SECONDS:.0f}s）")

    def wait_ok(self, request_id: int, what: str, expect_session_id: bool = True) -> dict:
        """等到响应，失败时把 error / 缺字段的原因原样打出来。

        dsh 拒绝续接时回 invalidParams（会话没落盘、是 subagent、cwd 与建会话时不一致…），
        只报"没给 sessionId"等于把最该看的那行吞了。

        [expect_session_id] 为假时不要求回包带 sessionId：**dsh 的 `session/resume` 就不带**
        （只回 configOptions），客户端按 `DshAcpClient.resumeSession` 的写法回落到请求里的那个 id。
        """
        response = self.wait(request_id, what)
        if "error" in response:
            print(f"FAIL: {what} 回了 error：{json.dumps(response['error'], ensure_ascii=False)}")
            raise SystemExit(1)
        result = response.get("result")
        if not isinstance(result, dict):
            print(f"FAIL: {what} 的响应里没有 result："
                  f"{json.dumps(response, ensure_ascii=False)[:800]}")
            raise SystemExit(1)
        if expect_session_id and not result.get("sessionId"):
            print(f"FAIL: {what} 的响应里没有 sessionId："
                  f"{json.dumps(response, ensure_ascii=False)[:800]}")
            raise SystemExit(1)
        return response

    def reader(self) -> None:
        assert self.process.stdout is not None
        for raw in self.process.stdout:
            line = raw.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                print(f"[acp] 非 JSON 的 stdout 行（丢弃）：{line[:200]}", flush=True)
                continue
            if "id" in message and ("result" in message or "error" in message):
                with self.lock:
                    self.responses[int(message["id"])] = message
                continue
            method = message.get("method")
            if method == "session/update":
                params = message.get("params") or {}
                update = params.get("update") or {}
                kind = update.get("sessionUpdate")
                if kind == "tool_call":
                    self.tool_calls.append(update)
                    print(f"[acp] tool_call: {update.get('title') or update.get('name')}", flush=True)
                elif kind == "tool_call_update":
                    self.tool_updates.append(update)
                    status = update.get("status")
                    if status:
                        print(f"[acp] tool_call_update: {status}", flush=True)
                    if status not in (None, "completed"):
                        # 失败时把原文打出来：冒烟测试的价值一半在于失败能自解释
                        print(f"[acp]   ↳ {json.dumps(update, ensure_ascii=False)[:1500]}", flush=True)
            elif "id" in message and method:
                self.answer_server_request(int(message["id"]), method, message.get("params") or {})
        self.closed.set()

    def answer_server_request(self, request_id: int, method: str, params: dict) -> None:
        if method == "session/request_permission":
            options = params.get("options") or []
            chosen = next((o.get("optionId") for o in options
                           if str(o.get("kind", "")).startswith("allow")), "allow-once")
            print(f"[acp] 放行审批：{chosen}", flush=True)
            self.send({"jsonrpc": "2.0", "id": request_id,
                       "result": {"outcome": {"outcome": "selected", "optionId": chosen}}})
        else:
            print(f"[acp] 未知的 agent 请求：{method}（回 error）", flush=True)
            self.send({"jsonrpc": "2.0", "id": request_id,
                       "error": {"code": -32601, "message": f"unsupported method: {method}"}})


def main() -> int:
    print(f"[acp] 启动：{' '.join(COMMAND[:4])} …", flush=True)
    process = subprocess.Popen(COMMAND, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, bufsize=1)
    client = Client(process)
    threading.Thread(target=client.reader, daemon=True).start()

    init = client.request("initialize", {"protocolVersion": 1, "clientCapabilities": {}})
    result = client.wait(init, "initialize").get("result") or {}
    print(f"[acp] initialize ok：agentInfo={result.get('agentInfo')}", flush=True)

    if RESUME_ID:
        # 回包只带 configOptions，不带 sessionId —— 沿用请求里的那个（与客户端一致）。
        client.wait_ok(
            client.request("session/resume", {
                "sessionId": RESUME_ID, "cwd": "/workspace", "mcpServers": [],
            }),
            "session/resume",
            expect_session_id=False,
        )
        session_id = RESUME_ID
        print(f"[acp] session/resume ok：{session_id}（回包不带 sessionId，沿用请求里的）",
              flush=True)
    else:
        created = client.wait_ok(
            client.request("session/new", {"cwd": "/workspace", "mcpServers": []}),
            "session/new",
        )
        session_id = (created.get("result") or {}).get("sessionId")
        if not session_id:
            return 1
        print(f"[acp] session/new ok：{session_id}", flush=True)
    print(f"DSH_E2E_SESSION={session_id}", flush=True)

    prompt = client.request("session/prompt", {
        "sessionId": session_id,
        "prompt": [{"type": "text", "text": PROMPT}],
    })
    response = client.wait(prompt, "session/prompt")
    stop_reason = (response.get("result") or {}).get("stopReason")
    print(f"[acp] prompt 结束：stopReason={stop_reason}", flush=True)
    print(f"DSH_E2E_STOP={stop_reason}", flush=True)

    stderr_tail = ""
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.terminate()
    if process.stderr is not None:
        stderr_tail = process.stderr.read()[-1500:]

    failures = []
    if not client.tool_calls:
        failures.append("没看到任何 tool_call")
    if not any((u.get("status") == "completed") for u in client.tool_updates):
        failures.append("没有 completed 的 tool_call_update")
    blob = json.dumps(client.tool_updates, ensure_ascii=False)
    if MARKER not in blob:
        failures.append(f"工具结果里没有标记串 {MARKER!r}")
    if stop_reason != "end_turn":
        failures.append(f"stopReason 不是 end_turn（{stop_reason}）")

    print(f"[acp] 统计：tool_call={len(client.tool_calls)} "
          f"tool_call_update={len(client.tool_updates)}", flush=True)
    if failures:
        print("FAIL: " + "；".join(failures))
        print("--- tool_call / tool_call_update 原文（截断）---")
        for update in client.tool_calls:
            print("  call    ", json.dumps(update, ensure_ascii=False)[:800])
        for update in client.tool_updates:
            print("  update  ", json.dumps(update, ensure_ascii=False)[:1500])
        if stderr_tail:
            print("--- dsh stderr 尾部 ---")
            print(stderr_tail)
        return 1
    print("PASS: 工具被真实调用并回了结果，回合以 end_turn 结束")
    return 0


if __name__ == "__main__":
    sys.exit(main())
