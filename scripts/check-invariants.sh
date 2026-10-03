#!/bin/bash
#
# 提交前不变式检查：这个会话里反复回归过的那几类"绝不能再来一次"。
#
# 用法：bash scripts/check-invariants.sh
#
# 为什么**不**接进 CI：它钉的是"坏写法不存在"这类语义模式（例如"别在日志里硬写 purge leftovers"），
# 合法重构可能让它过期 —— 我们已经被过期断言坑过两次（钉住 `purgeAsRoot` 的确切写法，下一轮重构
# 就失效）。所以它是给人/agent 在提交前跑的辅助；CI 那边装的是两个**判定保守**的静态检查
# （scripts/check-junit-imports.py、scripts/check-kotlin-comment-nesting.py）。
set -u
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 1

fail=0
check() {
  local label="$1" expect="$2" actual="$3"
  if [ "$expect" = "$actual" ]; then
    echo "  ok   $label = $actual"
  else
    echo "  FAIL $label：期望 $expect，实际 $actual"
    fail=1
  fi
}

# 反向自检：断言脚本自己也要被测 —— 故意给错期望，必须被判失败。
check "(自检) 故意错的期望" 1 0 > /dev/null 2>&1
if [ "$fail" -eq 1 ]; then
  echo "  ok   反向自检：错期望能被判失败"
  fail=0
else
  echo "  FAIL 反向自检：错期望没被判失败 —— 断言脚本失效了"
  fail=1
fi

core=app/src/main/kotlin/io/github/mangi/eta/core
dsh=app/src/main/kotlin/io/github/mangi/eta/agent/dsh
terminal=app/src/main/kotlin/io/github/mangi/eta/agent/terminal

echo "=== 递归删必须过护栏 ==="
# 只有这两个文件允许直接 deleteRecursively：前者是护栏本身，后者在 purge 退出码之后。
# 注意用 -E：BRE 里 \s 不是空白类（上一版就是这么误报的 —— 注释里提到 deleteRecursively
# 会被算成真实调用）。合法新增调用点时，正确做法是**在这里加文件名并写明为什么合法**，
# 而不是去改代码迁就断言。
check "裸递归删残留（合法新增请在此处加文件名，别改代码）" 0 "$(
  grep -rn 'deleteRecursively' app/src/main/kotlin --include='*.kt' \
    | grep -v 'SafeTreeDelete.kt' | grep -v 'DshRuntimeInstaller.kt' \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|/\*|\*)' | wc -l
)"
check "护栏文件存在" 1 "$([ -f $core/SafeTreeDelete.kt ] && echo 1 || echo 0)"

echo "=== 让路残骸的回收链 ==="
check "Outcome.PARTIAL 残留" 0 "$(grep -rc 'Outcome\.PARTIAL' $core | awk -F: '{s+=$2} END {print s+0}')"
check "枚举声明 INCOMPLETE" 1 "$(grep -c '^        INCOMPLETE,$' $core/SafeTreeDelete.kt)"
check "提权兜底钩子" 1 "$(grep -c 'purge: (File) -> Int?' $core/StaleRetirementSweeper.kt)"
check "兜底后以磁盘事实为准" 1 "$(grep -c 'runCatching { purge(victim) }' $core/StaleRetirementSweeper.kt)"
check "遍历不跟符号链接" 0 "$(grep -c 'walkTopDown' $core/StaleRetirementSweeper.kt)"

echo "=== 日志不能说谎（会话里栽过两次）==="
check "日志里硬写 purge leftovers" 0 "$(grep -c 'Log\.[wi](TAG, "purge leftovers' $dsh/DshRuntimeInstaller.kt)"

echo "=== 就绪判据 ==="
check "expectedBaseVersion 转发" 1 "$(grep -c 'rootfsReady(rootfs.absolutePath, expectedBaseVersion)' $terminal/DebianEnvironmentInstaller.kt)"
check "DEFAULT_SYSTEM_PROMPT 只声明一次" 1 "$(grep -rc '^    val DEFAULT_SYSTEM_PROMPT:' app/src/main --include='*.kt' | awk -F: '{s+=$2} END {print s+0}')"
check "const DEFAULT_SYSTEM_PROMPT 残留" 0 "$(grep -rc 'const val DEFAULT_SYSTEM_PROMPT' app/src/main --include='*.kt' | awk -F: '{s+=$2} END {print s+0}')"

echo "=== 死代码 ==="
check "canResume 不再比较 providerRoute" 0 "$(grep 'state.providerRoute != providerRoute' $dsh/DshAcpSessionStore.kt | grep -vc '^ *//')"

echo "=== CI 防线 ==="
check "两个静态检查已接入 CI" 2 "$(grep -c 'check-junit-imports.py\|check-kotlin-comment-nesting.py' .github/workflows/android-release.yml)"
check "静态检查脚本可编译" 0 "$(python3 -m py_compile scripts/check-junit-imports.py scripts/check-kotlin-comment-nesting.py 2>&1 | wc -l)"

echo
if [ "$fail" -eq 0 ]; then echo "全部不变式通过 ✓"; else echo "有不变式没过 ✗"; fi
exit "$fail"
