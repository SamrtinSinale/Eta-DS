#!/bin/bash
#
# 本地跑 agent/dsh 那两个零依赖文件的单测（改完先跑，别等 CI）。
#
# 范围（**故意很窄**）：
#   只测 app/src/{main,test}/kotlin/io/github/mangi/eta/agent/dsh/ 下的
#   DshPatchDocument 与 DshProfileStore 及其单测。
#   为什么只有它们能本地跑：这两个文件只依赖 java.io / java.util / org.json（在 android.jar 里），
#   不牵 Room/AndroidX/okhttp；测试侧要 junit + snakeyaml，都在本机 Gradle 缓存里能查到。
#   agent/dsh 的其余文件（DshRuntimeInstaller、DshAcpClient…）牵出 AndroidX，仍然只能靠 CI。
#
# 边界（照 compile-local.sh 的形制，别指望它能挡别的）：
#   · 挡得住：这两个文件的编译错、单测逻辑错（包括"改一行顺手删掉别人 insert 条目"这类）。
#   · 挡不住：其它文件的编译错、Gradle/AGP 相关的错、CI 上才有的环境差异。
#   · 找不到 kotlinc / android.jar / 依赖 jar 时**跳过**（exit 77），不假装通过。
#
# 前置：JDK 17（apt）、kotlinc 2.4.20、android.jar（同 compile-local.sh 的 /opt/heta-localc）；
#      junit 4.13.2 / hamcrest 1.3 / snakeyaml 2.4 / org.json 从 Gradle 缓存里自动找。
#
# 用法：bash scripts/test-local-dsh.sh
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 1

KOTLINC="${KOTLINC:-/opt/heta-localc/kotlinc/bin/kotlinc}"
if [ -z "${ANDROID_JAR:-}" ]; then
  ANDROID_JAR="$(ls /opt/heta-localc/android/*/android.jar 2>/dev/null | head -1)"
fi

# Gradle 缓存位置随用户不同（本机是普通用户跑的 Gradle，脚本却常常由 root 调起），都找。
finder() { find "$HOME/.gradle" /root/.gradle /home/*/.gradle -name "$1" 2>/dev/null | head -1; }
JUNIT="$(finder 'junit-4.13.2.jar')"
HAMCREST="$(finder 'hamcrest-core-1.3.jar')"
SNAKEYAML="$(finder 'snakeyaml-2.4.jar')"
JSONJAR="$(finder 'json-2026*.jar')"

[ -x "$KOTLINC" ] || { echo "SKIP: 找不到 kotlinc（设 KOTLINC=...）"; exit 77; }
[ -f "$ANDROID_JAR" ] || { echo "SKIP: 找不到 android.jar（设 ANDROID_JAR=...）"; exit 77; }
for jar in "$JUNIT" "$HAMCREST" "$SNAKEYAML" "$JSONJAR"; do
  [ -n "$jar" ] && [ -f "$jar" ] || { echo "SKIP: Gradle 缓存里找不到依赖 jar：$jar"; exit 77; }
done

KOTLIN_LIB="$(cd "$(dirname "$KOTLINC")/../lib" && pwd)"
[ -f "$KOTLIN_LIB/kotlin-stdlib.jar" ] || { echo "SKIP: 找不到 kotlin-stdlib.jar"; exit 77; }

# 编译产物放用户目录：/tmp 会被清理，放那儿下次还得重编。
OUT="${TMPDIR:-$HOME}/heta-local-dsh-$(id -u)"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

B=app/src/main/kotlin/io/github/mangi/eta/agent/dsh
T=app/src/test/kotlin/io/github/mangi/eta/agent/dsh
FILES=(
  "$B/DshPatchDocument.kt"
  "$B/DshProfileStore.kt"
  "$T/DshPatchDocumentTest.kt"
  "$T/DshProfileStoreTest.kt"
)

echo "本地编译：${#FILES[@]} 个文件"
start=$(date +%s)
"$KOTLINC" -nowarn -cp "$ANDROID_JAR:$JUNIT:$SNAKEYAML" -d "$OUT/classes" "${FILES[@]}"
code=$?
end=$(date +%s)
echo "编译 exit=$code（$((end - start)) 秒）"
[ "$code" -eq 0 ] || exit "$code"

# 运行期要用**真** org.json（android.jar 里的是抛 "Stub!" 的桩），还要自己带上 kotlin-stdlib。
echo "本地单测："
java -cp "$OUT/classes:$JSONJAR:$JUNIT:$HAMCREST:$SNAKEYAML:$KOTLIN_LIB/kotlin-stdlib.jar" \
  org.junit.runner.JUnitCore \
  io.github.mangi.eta.agent.dsh.DshPatchDocumentTest \
  io.github.mangi.eta.agent.dsh.DshProfileStoreTest
code=$?
echo "单测 exit=$code（总计 $(( $(date +%s) - start )) 秒）"
exit "$code"
