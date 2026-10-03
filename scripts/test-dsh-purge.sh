#!/bin/bash
#
# dsh 运行时清理脚本（runtimePurgeScript）的**行为**测试。
#
# 为什么需要它：那个脚本用路径前缀在 /proc/self/mountinfo 里找要摘掉的挂载，而
# context.filesDir 是 /data/user/0/<包名>/files —— /data/user/0 是指向 /data/data 的
# 符号链接，内核对挂载点记的是**解析后**的 /data/data/...。前缀对不上，dev、proc、
# 技能库这三个挂载就一个都匹配不到，于是 `rm -rf` 会穿过它们删掉宿主 /dev 与用户技能库。
#
# 只做字符串断言的单测对这种"扫描匹配不到真实挂载点"完全免疫（和 --patch 坏 YAML 那次
# 同一个毛病），所以这里在真环境里造同构场景（符号链接前缀 + bind 挂载 + 挂载源里放数据），
# 断言：挂载被摘掉、目标被删、**挂载源数据完好**；摘不掉时必须拒绝删除。
#
# 用法：sudo bash scripts/test-dsh-purge.sh <生成的脚本文件>
#   脚本文件由 app 的单测写出（DshRuntimeInstallerPurgeTest），目标路径是占位符，
#   这里替换成本次运行的临时目录。
set -uo pipefail

GENERATED="${1:?用法: test-dsh-purge.sh <由单测生成的脚本文件>}"
PLACEHOLDER="/dsh-purge-placeholder-4f3a/data/files/dsh-runtime"

if [ "$(id -u)" != 0 ]; then
  if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    exec sudo -E bash "$0" "$@"
  fi
  # 77 表示"环境不支持造挂载"，CI 里按失败处理（见 workflow）
  echo "SKIP: 需要 root 才能造挂载（本机没有免密 sudo）"
  exit 77
fi

[ -f "$GENERATED" ] || { echo "FAIL: 找不到生成的脚本：$GENERATED"; exit 1; }
grep -q "$PLACEHOLDER" "$GENERATED" || {
  echo "FAIL: 生成的脚本里没有占位目标 $PLACEHOLDER（单测写出的内容变了？）"
  exit 1
}

WORK="$(mktemp -d /tmp/dsh-purge-test.XXXXXX)"
cleanup() {
  awk -v p="$WORK" 'index($5, p) == 1 { print $5 }' /proc/self/mountinfo 2>/dev/null |
    sort -r | while read -r m; do umount -l "$m" 2>/dev/null; done
  rm -rf "$WORK"
}
trap cleanup EXIT

fails=0
ok()  { echo "  ok   $1"; }
bad() { echo "  FAIL $1"; fails=$((fails + 1)); }

# 造场景：$1 = 用例名。目标目录经符号链接抵达，里面有一个 bind 挂载，挂载源里是"用户数据"。
build_case() {
  local name="$1"
  local base="$WORK/$name"
  mkdir -p "$base/real" "$base/src"
  ln -s "$base/real" "$base/link"                 # /data/user/0 -> /data/data 的等价物
  TARGET="$base/link/dsh-runtime"                 # 故意用符号链接写法去调脚本
  mkdir -p "$TARGET/mnt" "$TARGET/opt"
  echo "用户数据" > "$base/src/precious"
  echo x > "$TARGET/opt/keep"
  mount --bind "$base/src" "$TARGET/mnt"
  sed "s#$PLACEHOLDER#$TARGET#g" "$GENERATED" > "$WORK/$name.sh"
}

echo "① 符号链接前缀 + bind 挂载：摘挂载 → 删目标，源数据必须完好"
build_case symlink
out="$(bash "$WORK/symlink.sh" 2>&1)"; code=$?
[ "$code" -eq 0 ] && ok "退出码 0" || bad "退出码 $code（输出：$out）"
[ -e "$TARGET" ] && bad "目标目录还在" || ok "目标目录已删除"
[ -e "$WORK/symlink/src/precious" ] && ok "挂载源数据完好" \
  || bad "【挂载源数据被删了】rm -rf 穿过了挂载点"
if grep -q "$WORK/symlink/real/dsh-runtime/mnt" /proc/self/mountinfo; then
  bad "挂载残留"
else
  ok "挂载已摘掉"
fi

echo "② 兄弟目录前缀：不能动 dsh-runtime.installing"
build_case sibling
mkdir -p "$WORK/sibling/link/dsh-runtime.installing/mnt" "$WORK/sibling/src2"
echo "staging 数据" > "$WORK/sibling/src2/precious2"
mount --bind "$WORK/sibling/src2" "$WORK/sibling/link/dsh-runtime.installing/mnt"
bash "$WORK/sibling.sh" >/dev/null 2>&1
[ -e "$WORK/sibling/src2/precious2" ] && ok "staging 挂载源未被动过" \
  || bad "staging 挂载源被删了"
if grep -q "dsh-runtime.installing/mnt" /proc/self/mountinfo; then
  ok "staging 挂载仍在（没越界去摘）"
else
  bad "staging 的挂载被摘掉了（越界）"
fi

echo "③ 摘不掉时：必须一个字都不删"
build_case abort
mkdir -p "$WORK/abort/bin"
printf '#!/bin/sh\nexit 0\n' > "$WORK/abort/bin/umount"   # 假的 umount：什么都不做
chmod +x "$WORK/abort/bin/umount"
out="$(PATH="$WORK/abort/bin:$PATH" bash "$WORK/abort.sh" 2>&1)"; code=$?
[ "$code" -ne 0 ] && ok "拒绝执行（退出码 $code）" || bad "摘不掉却退出 0"
case "$out" in
  *HETA_PURGE_ABORT*) ok "输出里有 HETA_PURGE_ABORT" ;;
  *) bad "没有 HETA_PURGE_ABORT：$out" ;;
esac
[ -e "$TARGET/opt/keep" ] && ok "目标里的文件没被删" || bad "目标里的文件被删了"
[ -e "$WORK/abort/src/precious" ] && ok "挂载源数据没被删" || bad "挂载源数据被删了"

echo "④ 浅路径兜底：误传太浅的路径必须拒绝"
SHALLOW=/tmp/dsh-purge-shallow-test
sed "s#$PLACEHOLDER#$SHALLOW#g" "$GENERATED" > "$WORK/shallow.sh"
mkdir -p "$SHALLOW"; echo y > "$SHALLOW/f"
out="$(bash "$WORK/shallow.sh" 2>&1)"; code=$?
[ "$code" -ne 0 ] && ok "拒绝执行（退出码 $code）" || bad "浅路径没被拦下"
[ -e "$SHALLOW/f" ] && ok "浅路径目录没被删" || bad "浅路径目录被删了"
rm -rf "$SHALLOW"

echo
if [ "$fails" -gt 0 ]; then
  echo "====> $fails 项失败"
  exit 1
fi
echo "====> 全部通过"
