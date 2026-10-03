#!/bin/bash
#
# 本地快速编译（改完先编译，别等 CI）。
#
# 目标：把"改完跑一次编译"变成 **10 秒级**的动作。
#
# 范围（**故意是子集**，不是整个 app）：
#   覆盖：core/ 里的递归删护栏（SafeTreeDelete）、让路残骸回收（StaleRetirementSweeper）、
#         日志（AgentLogger / LogThrottle / ModuleConfig）、Android 终端路径常量
#         （AndroidTerminalPaths）、身份常量（AgentIdentity）。
#   不覆盖：agent/model/AgentPromptBuilder、agent/dsh/**、UI —— 它们会牵出 Room/AndroidX/
#         okhttp/kotlinx 等一大堆 jar，本地追不划算，交回 CI。改动落在那里时，本脚本仍能挡下
#         "语法/同批文件缺失"这类错，但挡不住外部 API 误用。
#
#   实测过的边界（2026-10-03，别再试一遍 —— 都是"以为便宜、实际不便宜"）：
#     · 那 4 个终端控制器**不是零依赖**：`ConsoleSessionController` 要 `TerminalEnvironment`
#       + `ShellProcessSupervisor`；补进去之后继续牵出 `AgentExecutionService`/UI/Compose。
#       
#     · `AgentTerminalToolCatalog` → `AgentToolSchema` → `AgentExecutionService` → `ui/SettingsScreen.kt`
#       → Compose 组件 → `hook/xiaoai`：错误驱动的补齐会**爆炸**。
#     · `SharedFolderMounts`（要 `config.Prefs`）、`TerminalRuntime`（要 `RootAccess` +
#       `AgentExecutionService`）同理。
#   ⇒ 结论：本地编译**只覆盖低依赖的 core/ 几个文件 + 两个常量**；凡是要碰终端控制器、
#     运行时服务或 UI 的改动，验证仍然只能靠 CI。这是这个工具的**已知上限**，不是能靠加文件解决的。
#
# 前置（一次性，约 320 MB）：
#   JDK 17           : apt-get install -y openjdk-17-jdk-headless
#   kotlinc 2.4.20   : 官方 zip（**别用 apt 的 kotlin**，那是 1.3，编不了本项目）
#                      https://github.com/JetBrains/kotlin/releases/download/v2.4.20/kotlin-compiler-2.4.20.zip
#   android.jar      : 从 https://dl.google.com/android/repository/repository2-3.xml 里找
#                      platform-36*.zip，解出来的 android.jar（26 MB 左右）
#   路径通过环境变量给：KOTLINC=/path/to/kotlinc  ANDROID_JAR=/path/to/android.jar
#   本机已装好：JDK 17（apt）+ kotlinc 2.4.20 + android.jar，统一放在 /opt/heta-localc/（可读）
#
# 用法：bash scripts/compile-local.sh
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 1

KOTLINC="${KOTLINC:-/opt/heta-localc/kotlinc/bin/kotlinc}"
if [ -z "${ANDROID_JAR:-}" ]; then
  ANDROID_JAR="$(ls /opt/heta-localc/android/*/android.jar 2>/dev/null | head -1)"
fi

[ -x "$KOTLINC" ] || { echo "SKIP: 找不到 kotlinc（设 KOTLINC=...）"; exit 77; }
[ -f "$ANDROID_JAR" ] || { echo "SKIP: 找不到 android.jar（设 ANDROID_JAR=...）"; exit 77; }

B=app/src/main/kotlin/io/github/mangi/eta
FILES=(
  "$B/core/SafeTreeDelete.kt"
  "$B/core/StaleRetirementSweeper.kt"
  "$B/core/SweepStamp.kt"
  "$B/core/AgentLogger.kt"
  "$B/core/LogThrottle.kt"
  "$B/core/ModuleConfig.kt"
  "$B/agent/terminal/AndroidTerminalPaths.kt"
  "$B/agent/model/AgentIdentity.kt"
)

# AGP 生成的 BuildConfig 在本地不存在，给个桩（只用到 APPLICATION_ID；值与 applicationId 一致）。
# 临时目录按用户区分：root 与普通用户各用各的，避免"先用 root 跑过一次，之后普通用户写不进去"。
STUB_DIR="${TMPDIR:-/tmp}/heta-local-compile-$(id -u)"
rm -rf "$STUB_DIR"
mkdir -p "$STUB_DIR"
cat > "$STUB_DIR/BuildConfig.kt" <<'STUB'
package io.github.mangi.eta

/** 本地编译桩：AGP 会生成同名类，这里只为让 kotlinc 编得过。 */
internal object BuildConfig {
    const val APPLICATION_ID = "io.sartin.eats"
}
STUB

echo "本地编译：${#FILES[@]} 个文件 + BuildConfig 桩"
start=$(date +%s)
"$KOTLINC" -nowarn -cp "$ANDROID_JAR" -d "$STUB_DIR/out" "${FILES[@]}" "$STUB_DIR/BuildConfig.kt"
code=$?
end=$(date +%s)
echo "exit=$code（$((end - start)) 秒）"
exit "$code"
