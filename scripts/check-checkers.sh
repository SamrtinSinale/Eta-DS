#!/bin/bash
#
# 检查器的反向用例：每个检查器都必须能被「已知坏输入」触发报警。
#
# 为什么需要这个文件：这个项目的失败模式已经转移到**验证层** ——
#   · 判据写错：`\s` 在 BRE 里无效；用 grep 数空行数出假结论；把报警判定写成"标签在前"
#   · 范围写错：断言没界定作用域，「0 处」在两种理解下真假相反
#   · 守卫失效：`| tail` 吞掉退出码，检查失败却照样提交
#   · 批量删多：按模式处理却没先断言形状（两次）
# 而没有反向用例的检查器**等于没被验证过**：它在坏输入下沉默时，你分不清是「没问题」还是「没生效」。
#
# 判据用**退出码**（报警 = 非 0），不去匹配输出文案 —— 文案会变，退出码才是约定。
# 每个检查器先跑一次**干净态**做对照，否则「总是报警」也会被算成通过。
#
# 用法：bash scripts/check-checkers.sh
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 1

MAIN=app/src/main/kotlin/io/github/mangi/eta
TEST=app/src/test/kotlin/io/github/mangi/eta
PROBE_MAIN="$MAIN/zz_checker_probe"
PROBE_TEST="$TEST/zz_checker_probe"
fails=0
ok()  { echo "  ok   $1"; }
bad() { echo "  FAIL $1"; fails=$((fails + 1)); }

cleanup() { rm -rf "$PROBE_MAIN" "$PROBE_TEST"; }
trap cleanup EXIT
cleanup

# 跑一个检查器：干净态应 0，注入坏输入后应非 0
expect_silent() {   # $1=说明  $2..=命令
  local label="$1"; shift
  local out; out="$("$@" 2>&1)"; local code=$?
  if [ "$code" -eq 0 ]; then ok "$label：干净态安静"; else bad "$label：干净态就报警（对照失败）：$(echo "$out" | tail -1)"; fi
}
expect_alarm() {    # $1=说明  $2..=命令
  local label="$1"; shift
  local out; out="$("$@" 2>&1)"; local code=$?
  if [ "$code" -ne 0 ]; then ok "$label：坏输入下报警（exit=$code）"; else bad "$label：坏输入下沉默（这次反向用例抓到的是真守卫失效）"; fi
}

echo "① 干净态对照"
expect_silent "check-invariants" bash scripts/check-invariants.sh
expect_silent "check-junit-imports" python3 scripts/check-junit-imports.py
expect_silent "comment-nesting" python3 scripts/check-kotlin-comment-nesting.py

echo "② 注入裸递归删 → check-invariants 必须报警"
mkdir -p "$PROBE_MAIN"
cat > "$PROBE_MAIN/Probe.kt" <<'KT'
package io.github.mangi.eta.zz_checker_probe

import java.io.File

internal fun probe(any: File) {
    any.deleteRecursively()
}
KT
expect_alarm "check-invariants/裸递归删" bash scripts/check-invariants.sh
rm -rf "$PROBE_MAIN"

echo "③ 注入缺 JUnit import 的测试 → check-junit-imports 必须报警"
mkdir -p "$PROBE_TEST"
cat > "$PROBE_TEST/ProbeTest.kt" <<'KT'
package io.github.mangi.eta.zz_checker_probe

import org.junit.Test

internal class ProbeTest {
    @Test
    fun check() {
        // 故意漏掉 assertEquals 的 import —— 这才是真实形态（真实漏 import 的文件总会有别的 junit import）
        assertEquals(1, 1)
    }
}
KT
expect_alarm "check-junit-imports/缺 import" python3 scripts/check-junit-imports.py
rm -rf "$PROBE_TEST"

echo "④ 注入 KDoc 里嵌 /*（Kotlin 块注释会嵌套 → 编译挂）→ comment-nesting 必须报警"
mkdir -p "$PROBE_MAIN"
cat > "$PROBE_MAIN/Probe.kt" <<'KT'
package io.github.mangi.eta.zz_checker_probe

/**
 * 举例：这样写会把 KDoc 里的注释开起来 /* 后续全被吞掉
 */
internal fun probe(): Int = 1
KT
expect_alarm "comment-nesting/KDoc 嵌 /*" python3 scripts/check-kotlin-comment-nesting.py
rm -rf "$PROBE_MAIN"

echo "⑥ 注入未登记的 ProcessBuilder → check-invariants 必须报警"
mkdir -p "$PROBE_MAIN"
cat > "$PROBE_MAIN/Probe.kt" <<'KT'
package io.github.mangi.eta.zz_checker_probe

internal fun probe(): ProcessBuilder = ProcessBuilder("su", "-c", "id")
KT
expect_alarm "check-invariants/未登记进程站点" bash scripts/check-invariants.sh
rm -rf "$PROBE_MAIN"

echo "⑦ 只在注释里提到 ProcessBuilder → 不能误报（白名单判据看的是真实调用）"
mkdir -p "$PROBE_MAIN"
cat > "$PROBE_MAIN/Probe.kt" <<'KT'
package io.github.mangi.eta.zz_checker_probe

// 以前这里写过 ProcessBuilder("su", "-c", ...)，现在走统一入口了。
internal fun probe(): Int = 1
KT
if bash scripts/check-invariants.sh >/dev/null 2>&1; then
  ok "注释不误报"
else
  bad "注释里的 ProcessBuilder 把检查弄红了（假失败）"
fi
rm -rf "$PROBE_MAIN"

echo "⑧ 只在注释里提到 Runtime.getRuntime → 不得误报（判据 B 的注释形态）"
mkdir -p "$PROBE_MAIN"
cat > "$PROBE_MAIN/Probe.kt" <<'KT'
package io.github.mangi.eta.zz_checker_probe

// 以前这里用 Runtime.getRuntime().exec(...)，已迁走。
internal fun probe(): Int = 1
KT
if bash scripts/check-invariants.sh >/dev/null 2>&1; then
  ok "注释版 Runtime.getRuntime 不误报"
else
  bad "注释里的 Runtime.getRuntime 把检查弄红了（假失败）"
fi
rm -rf "$PROBE_MAIN"

echo "⑨ 注入未登记的 CollectLimit.UNBOUNDED → check-invariants 必须报警"
mkdir -p "$PROBE_MAIN"
cat > "$PROBE_MAIN/Probe.kt" <<'KT'
package io.github.mangi.eta.zz_checker_probe

import io.github.mangi.eta.core.CollectLimit

internal fun probe(): Int = CollectLimit.UNBOUNDED
KT
expect_alarm "check-invariants/未登记 UNBOUNDED" bash scripts/check-invariants.sh
rm -rf "$PROBE_MAIN"

echo "⑩ 只在注释里提到 CollectLimit.UNBOUNDED → 不能误报"
mkdir -p "$PROBE_MAIN"
cat > "$PROBE_MAIN/Probe.kt" <<'KT'
package io.github.mangi.eta.zz_checker_probe

// 以前这里读流用 CollectLimit.UNBOUNDED，现在换成有界读取了。
internal fun probe(): Int = 1
KT
if bash scripts/check-invariants.sh >/dev/null 2>&1; then
  ok "注释不误报"
else
  bad "注释里的 UNBOUNDED 把检查弄红了（假失败）"
fi
rm -rf "$PROBE_MAIN"

echo "⑤ 历史区间反向用例：check-cherry-pick-semantics 必须报警"
expect_alarm "cherry-pick b780c18..2d63fb1" \
  python3 scripts/check-cherry-pick-semantics.py b780c18..2d63fb1
expect_silent "cherry-pick 当前区间" python3 scripts/check-cherry-pick-semantics.py HEAD~3

echo
if [ "$fails" -gt 0 ]; then
  echo "====> $fails 项失败"
  exit 1
fi
echo "====> 全部通过：检查器对坏输入报警、对干净态沉默"
