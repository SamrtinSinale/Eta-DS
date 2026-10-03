#!/bin/bash
#
# dsh 运行时 + ACP + 工具调用的**端到端冒烟测试**。
#
# 它回答的是这个问题："Heta 里的 dsh 还能不能像官方一样真的调工具？"
# 之前所有验证都只到 `initialize` 为止（会话建立前的握手），工具链（子进程、pty、
# ripgrep、权限策略、回合循环）一条都没跑过。这里用一个假模型（按 Anthropic Messages
# 协议回一个 bash tool_use）把整条链路跑一遍，断言：
#
#   1. 收到 bash 工具的 tool_call
#   2. 该调用 completed，且结果里带着标记串（命令真的被执行了）
#   3. 回合以 end_turn 收尾
#   4. 标记文件真的出现在 chroot 的 /workspace 里（独立证据，不只看协议）
#
#   5. 跑完凭据文件已经不在（真脚本的 `rm -f`；`. file` 那步没生效就会打到真网关）
#
# 用的都是真东西：APK 里那份运行时资产、Kotlin 单测导出的**真 overlay 与真启动脚本**
# （含凭据文件机制、/dev 与 /proc 挂载、会话回收、`exec chroot`）、真 qemu + chroot。
# 端到端不自己拼启动命令 —— 拼就会漏掉真实路径，这一条上次已经吃过亏。
#
# 用法：sudo bash scripts/test-dsh-e2e.sh
#   QEMU_AARCH64        指定 qemu-aarch64-static（默认取 PATH 或 /usr/bin）
#   DSH_E2E_OVERLAY     指定 overlay 文件（默认取单测导出的 app/build/…）
#   DSH_E2E_STARTUP     指定启动脚本（默认取单测导出的 app/build/…）
#   DSH_E2E_ROOT        复用已经解包好的运行时目录（默认自己解包到临时目录）
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MARKER="heta-smoke-ok"
PORT="${DSH_E2E_PORT:-8765}"

if [ "$(id -u)" != 0 ]; then
  if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    exec sudo -E bash "$0" "$@"
  fi
  echo "SKIP: 端到端冒烟需要 root（要 chroot + 挂载）"
  exit 77
fi

QEMU="${QEMU_AARCH64:-$(command -v qemu-aarch64-static || true)}"
[ -n "$QEMU" ] && [ -x "$QEMU" ] || { echo "FAIL: 找不到 qemu-aarch64-static（apt-get install qemu-user-static）"; exit 1; }

ASSET="$REPO/app/src/main/assets/dsh-runtime.tar.xz"
[ -f "$ASSET" ] || { echo "FAIL: 缺少运行时资产 $ASSET"; exit 1; }

TMP="$(mktemp -d /tmp/dsh-e2e.XXXXXX)"
MOCK_PID=""
cleanup() {
  [ -n "$MOCK_PID" ] && kill "$MOCK_PID" 2>/dev/null
  # 和运行时脚本同一套匹配：mountinfo 里记的是解析后的路径，且必须是路径边界
  awk '{print $5}' /proc/self/mountinfo 2>/dev/null | while read -r m; do
    cm=$(readlink -f "$m" 2>/dev/null || echo "$m")
    case "$cm" in "$TMP"|"$TMP"/*) umount -l "$m" 2>/dev/null ;; esac
  done
  [ -z "${DSH_E2E_KEEP:-}" ] && rm -rf "$TMP"
}
trap cleanup EXIT

if [ -n "${DSH_E2E_ROOT:-}" ]; then
  ROOT="$DSH_E2E_ROOT"
else
  ROOT="$TMP/root"
  echo "① 解包运行时资产 → $ROOT"
  mkdir -p "$ROOT"
  tar -xJf "$ASSET" -C "$ROOT" || { echo "FAIL: 解包失败"; exit 1; }
fi
cp -f "$QEMU" "$ROOT/qemu-static"; chmod +x "$ROOT/qemu-static"

OVERLAY="${DSH_E2E_OVERLAY:-$(find "$REPO" -name dsh-e2e-overlay.patch.yml -print -quit 2>/dev/null)}"
[ -n "$OVERLAY" ] && [ -f "$OVERLAY" ] || {
  echo "FAIL: 没有 overlay（先跑单测导出：./gradlew :app:testDebugUnitTest）"
  exit 1
}
echo "② 用单测导出的真 overlay：$OVERLAY"
cp -f "$OVERLAY" "$ROOT/opt/dsh/heta-run-overlay.patch.yml"

rm -f "$ROOT/workspace/smoke.txt"
MOCK_DUMP="$TMP/mock-dump.jsonl"
echo "③ 起假模型（Anthropic Messages，127.0.0.1:$PORT）"
# 请求原样落盘：第二回合要靠它断言"system 里是不是新人格""历史是不是原样带着"。
DSH_E2E_MOCK_DUMP="$MOCK_DUMP" python3 "$REPO/scripts/dsh-e2e-mock-llm.py" "$PORT" \
  > "$TMP/mock.log" 2>&1 &
MOCK_PID=$!
for _ in $(seq 1 40); do
  grep -q "listening" "$TMP/mock.log" && break
  sleep 0.25
done
grep -q "listening" "$TMP/mock.log" || { echo "FAIL: 假模型没起来"; cat "$TMP/mock.log"; exit 1; }

STARTUP_SRC="${DSH_E2E_STARTUP:-$(find "$REPO" -name dsh-startup-script.sh -print -quit 2>/dev/null)}"
[ -n "$STARTUP_SRC" ] && [ -f "$STARTUP_SRC" ] || {
  echo "FAIL: 没有单测导出的启动脚本（先跑单测导出，或用 DSH_E2E_STARTUP 指定）"
  exit 1
}
DUMPED_ROOT="$(sed -n '1s/^# dsh-e2e-rootfs=//p' "$STARTUP_SRC")"
[ -n "$DUMPED_ROOT" ] || { echo "FAIL: 导出的启动脚本第一行没有 rootfs 路径"; exit 1; }
echo "④ 用单测导出的真启动脚本（导出的 rootfs：$DUMPED_ROOT → 本次：$ROOT）"
# 注意别"顺手"改脚本里的 PATH：它是 Android 优先（/system/bin:/system/xbin:/product/bin），
# 但**以 /usr/bin:/bin 结尾** —— 在 Ubuntu/CI 上靠的就是后段解析出 rm/find/mount/chroot。
# 哪天这里冒出 "rm: command not found"，先看 PATH 后段还在不在，别去动 E2E。
sed "s|$DUMPED_ROOT|$ROOT|g" "$STARTUP_SRC" > "$TMP/startup.sh"
grep -q "$ROOT" "$TMP/startup.sh" || { echo "FAIL: 启动脚本里的 rootfs 路径没替换掉"; exit 1; }

# 凭据文件：真脚本会 `set -a; . <它>; set +a; rm -f <它>`。baseUrl 指向假模型 ——
# 如果 source 那一步没生效，dsh 会打到真网关，冒烟直接红。
CRED="$ROOT/opt/dsh/heta-run-env.sh"
mkdir -p "$(dirname "$CRED")"
printf 'export DEEPSEEK_API_KEY=sk-smoke\nexport DEEPSEEK_BASE_URL=http://127.0.0.1:%s\n' "$PORT" > "$CRED"
chmod 600 "$CRED"

echo "⑤ 跑一个真回合（initialize → session/new → prompt → 工具调用）"
# 用 unshare 起一个假 root 命名空间：chroot、/dev、/proc 和 binfmt 都在里面做，不碰宿主。
#
# binfmt 这一步是必须的：bash 工具执行命令时会再 spawn 一个 node 子进程
# （dsh-subprocess-local 的 runner 用 process.execPath），而运行时里的 node 是 aarch64 ELF。
# 没有 binfmt，内核直接 ENOEXEC —— 第一次跑就是卡在这里。注册时用 F 标志（固定解释器 fd），
# 这样 chroot 之后解释器依然可用（chroot 里没有 /usr/bin/qemu-aarch64-static）。
MAGIC=':qemu-aarch64:M::\x7fELF\x02\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00\x02\x00\xb7\x00:\xff\xff\xff\xff\xff\xff\xff\x00\xff\xff\xff\xff\xff\xff\xff\xff\xfe\xff\xff\xff:/tmp/qemu-aarch64-static:F'
# 只准备"脚本之外"的东西：binfmt（bash 工具会再 spawn 一个 aarch64 node 子进程，没它 ENOEXEC）。
# /dev、/proc、技能库挂载和 exec chroot 都交给真脚本自己做 —— 那才是 App 实际跑的那条路。
INNER="cp -f '$QEMU' /tmp/qemu-aarch64-static; chmod +x /tmp/qemu-aarch64-static;"
INNER="$INNER mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc 2>/dev/null;"
INNER="$INNER printf '%s' '$MAGIC' > /proc/sys/fs/binfmt_misc/register 2>/dev/null;"
INNER="$INNER exec bash '$TMP/startup.sh'"

python3 "$REPO/scripts/dsh-e2e-acp.py" "$MARKER" -- \
  unshare -rm bash -c "$INNER" 2>&1 | tee "$TMP/acp.log"
acp_status="${PIPESTATUS[0]}"
SESSION_ID="$(grep -m1 '^DSH_E2E_SESSION=' "$TMP/acp.log" | cut -d= -f2-)"
if [ -z "$SESSION_ID" ]; then
  # 拿不到会话 id 就别往下走：`--resume ""` 会静默退回 session/new，
  # 那样第二回合看起来"通过"，其实根本没测续接。
  echo "FAIL: 首回合没拿到 sessionId，无法验证续接"
  exit 1
fi
echo "   首回合会话：$SESSION_ID"

echo "⑥ 第二回合：改人格 → 重启 dsh → session/resume → 再跑一轮"
# 失败要能自解释：这三行分别证明"脚本确实带了 --patch""覆盖层里有我们要的人格"。
echo "   启动脚本里的 patch 实参：$(grep -o -- '--patch [^ ]*' "$TMP/startup.sh" | head -1)"
echo "   覆盖层 system-prompt 段："
grep -A3 'id: system-prompt' "$ROOT/opt/dsh/heta-run-overlay.patch.yml" | sed 's/^/     /'
# 一、把覆盖层里的 personaPrefix 换掉（它是单行 JSON 引号标量，直接 sed 就成合法 YAML）。
#     人格只在进程启动时读一次，所以"改人格"必须重启 dsh —— 这也正是 App 的真实形态：
#     每一轮 run 都是新进程 + session/resume。
OVERLAY="$ROOT/opt/dsh/heta-run-overlay.patch.yml"
cp -f "$OVERLAY" "$TMP/overlay-round1.yml"
PERSONA2="HETA-E2E-PERSONA-2"
sed -i "s|^    personaPrefix: .*|    personaPrefix: \"$PERSONA2\"|" "$OVERLAY"
grep -q "$PERSONA2" "$OVERLAY" || { echo "FAIL: 覆盖层里没换上第二人格"; exit 1; }

# 二、凭据文件要重新放一份（第一回合的已被脚本 rm -f 掉）。
printf 'export DEEPSEEK_API_KEY=sk-smoke\nexport DEEPSEEK_BASE_URL=http://127.0.0.1:%s\n' "$PORT" > "$CRED"
chmod 600 "$CRED"

ROUND2_PROMPT="第二回合：再写一次标记文件，确认续接之后还在同一个会话里。"
python3 "$REPO/scripts/dsh-e2e-acp.py" "$MARKER" --resume "$SESSION_ID" --prompt "$ROUND2_PROMPT" -- \
  unshare -rm bash -c "$INNER" 2>&1 | tee "$TMP/acp2.log"
round2_status="${PIPESTATUS[0]}"

echo "⑦ 独立证据：chroot 里的 /workspace/smoke.txt"
if [ -f "$ROOT/workspace/smoke.txt" ]; then
  content="$(cat "$ROOT/workspace/smoke.txt")"
  echo "   内容：$content"
  case "$content" in
    *"$MARKER"*) file_ok=1 ;;
    *) file_ok=0 ;;
  esac
else
  echo "   文件不存在"
  file_ok=0
fi

echo "⑧ 独立证据：凭据文件用完必须已经删掉（真脚本的 rm -f）"
if [ -e "$CRED" ]; then
  echo "   还在：$CRED"
  cred_ok=0
else
  echo "   已删除 ✓"
  cred_ok=1
fi

echo "⑨ 续接语义断言（systemPromptUpdate: in-history）"
history_ok=0
python3 - "$MOCK_DUMP" "$PERSONA2" "$ROUND2_PROMPT" <<'PY'
import json
import sys

dump, persona2, round2_prompt = sys.argv[1], sys.argv[2], sys.argv[3]
requests = []
with open(dump, encoding="utf-8") as handle:
    for line in handle:
        line = line.strip()
        if line:
            requests.append(json.loads(line)["body"])


def system_text(body):
    system = body.get("system")
    if isinstance(system, list):
        return " ".join(str(block.get("text") or "") for block in system if isinstance(block, dict))
    return str(system or "")


def user_texts(body):
    texts = []
    for message in body.get("messages") or []:
        if message.get("role") != "user":
            continue
        content = message.get("content")
        if isinstance(content, str):
            texts.append(content)
        elif isinstance(content, list):
            for block in content:
                if isinstance(block, dict) and block.get("type") == "text":
                    texts.append(str(block.get("text") or ""))
    return texts


first_prompt = "跑一下冒烟测试：把标记写进文件并打印出来。"
print(f"   假模型共收到 {len(requests)} 次请求")
suffix_marker = "Your working directory is"


def body_text(body):
    return json.dumps(body, ensure_ascii=False)


def persona_location(body):
    """人格标记出现在哪：system 字段，还是历史消息（in-history 增量）。"""
    if persona2 in system_text(body):
        return "system"
    if persona2 in body_text(body):
        return "messages"
    return ""


for index, body in enumerate(requests):
    system = system_text(body)
    print(f"   请求 #{index + 1}：system {len(system)} 字符，"
          f"含我们的后缀={suffix_marker in system}，新人格位置={persona_location(body) or '无'}")
problems = []
if len(requests) < 3:
    problems.append(f"请求次数不对（{len(requests)} < 3），第二回合没跑起来")
else:
    round1_hit = persona_location(requests[0])
    round2_hit = persona_location(requests[2])
    if round1_hit:
        problems.append(f"第一回合的请求里就有第二人格（{round1_hit}），断言失去意义")
    if not round2_hit:
        problems.append(
            "续接后模型看不到新人格 —— systemPromptUpdate: in-history 没生效"
            f"（system 开头：{system_text(requests[2])[:200]!r}）"
        )
    else:
        print(f"   ✓ 新人格出现在 {round2_hit}"
              + ("（in-history 增量投递在历史里）" if round2_hit == "messages" else ""))
    round2_users = user_texts(requests[2])
    if first_prompt not in round2_users:
        problems.append("续接后的请求里没有第一回合的用户原文（历史被压成摘要或丢了）")
    if round2_prompt not in round2_users:
        problems.append("续接后的请求里没有本回合的用户原文")
    if len(requests[2].get("messages") or []) <= len(requests[0].get("messages") or []):
        problems.append("续接后的消息条数没有增长，历史看起来没带上")

if problems:
    for problem in problems:
        print(f"   ✗ {problem}")
    sys.exit(1)
print(f"   ✓ 人格已刷新（{persona2}）；历史原样带着，没有退化成摘要")
PY
[ $? -eq 0 ] && history_ok=1

echo
if [ "$acp_status" -eq 0 ] && [ "$round2_status" -eq 0 ] && [ "$file_ok" -eq 1 ] \
   && [ "$cred_ok" -eq 1 ] && [ "$history_ok" -eq 1 ]; then
  echo "====> 端到端通过：工具真执行、回合正常收尾、续接后人格已刷新且历史原样"
  exit 0
fi
echo "====> 端到端失败（首回合=$acp_status 续接=$round2_status 文件=$file_ok 凭据=$cred_ok 续接语义=$history_ok）"
if [ "$round2_status" != 0 ]; then
  echo "--- 第二回合日志尾部 ---"; tail -20 "$TMP/acp2.log" 2>/dev/null
fi
echo "--- 假模型日志 ---"; tail -20 "$TMP/mock.log"
exit 1
