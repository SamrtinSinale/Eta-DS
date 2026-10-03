#!/usr/bin/env python3
"""cherry-pick 后的语义冲突检查：git 看不到的那类冲突。

用法：
    python3 scripts/check-cherry-pick-semantics.py <BASE>            # 比较 BASE..HEAD
    python3 scripts/check-cherry-pick-semantics.py <BASE>..<TIP>     # 显式范围（用于反向自检）

**备用脚本，手动跑**（不被 check-invariants.sh 调用、不进 CI）。它抓的是：
**上游删掉了一个 import，而我们的代码还在用它**。git 只做文本合并，这种情况会干净地
合过去，直到 CI 才炸 `Unresolved reference`。真实案例：上游重构设置页时删掉了
`androidx.compose.material.icons.rounded.Memory` 的 import，而我们在"重装对话运行时"那行仍在用。

三条规则都是被真实误报打磨出来的，改之前先读：
  - 只看**调用或点号限定**的用法（`sym(` 或 `.sym`）：`item(key = …)` 这种具名参数不算，
    但它恰好和 Compose 的 `key()` 同名 —— 第一版按"出现即算"会误报；
  - **同包声明**要跳过：Kotlin 里同包符号不需要 import，上游顺手删冗余 import 时，
    符号本身还在本包（测试源集与主源集共享同一个包）；
  - 读代码要**按版本读**（`git show <TIP>:<path>`），不能读工作区 —— 否则拿历史区间做反向自检时，
    工作区里已经补回的 import 会让它误判成"现在还导入着"而跳过。

砍掉过的规则（别再捡回来）："被删的声明仍被引用" —— 试了两版都收不干净（`close`、形参名、
别的文件的类名），而**假阳性比漏报更贵**：它会逼人去改正确的代码。
"""
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DECL = re.compile(
    r"^\s*(?:internal\s+|private\s+|public\s+|open\s+|abstract\s+)*"
    r"(?:class|interface|object|enum class|typealias|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
KOTLIN_ROOT = re.compile(r"^app/src/[^/]+/kotlin/")


def code_lines(text: str) -> list[str]:
    return [l for l in text.splitlines() if not l.strip().startswith(("//", "*", "/*"))]


def read_version(tip: str, path: str, fallback: Path) -> str:
    result = subprocess.run(["git", "show", f"{tip}:{path}"], cwd=REPO,
                            capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else fallback.read_text(encoding="utf-8", errors="ignore")


def used_as_call_or_member(lines: list[str], symbol: str) -> bool:
    call = re.compile(rf"(?<![A-Za-z0-9_]){re.escape(symbol)}\s*\(")
    member = re.compile(rf"\.{re.escape(symbol)}(?![A-Za-z0-9_])")
    return any(call.search(line) or member.search(line) for line in lines)


def package_dir(relative: str) -> str | None:
    match = KOTLIN_ROOT.match(relative)
    if not match:
        return None
    rest = relative[match.end():]
    return rest.rsplit("/", 1)[0] if "/" in rest else ""


def declared_in_any_source_root(relative: str, symbol: str) -> bool:
    package = package_dir(relative)
    if package is None:
        return False
    for source_set in ("main", "test", "androidTest"):
        directory = REPO / "app/src" / source_set / "kotlin" / package
        if not directory.is_dir():
            continue
        for candidate in directory.glob("*.kt"):
            for line in code_lines(candidate.read_text(encoding="utf-8", errors="ignore")):
                m = DECL.match(line)
                if m and m.group(1) == symbol:
                    return True
    return False


def main() -> int:
    arg = sys.argv[1] if len(sys.argv) > 1 else "HEAD~1"
    base, tip = arg.split("..", 1) if ".." in arg else (arg, "HEAD")

    diff = subprocess.run(["git", "diff", "-U0", f"{base}..{tip}"], cwd=REPO,
                          capture_output=True, text=True, check=True).stdout
    removed: dict[str, set[str]] = {}
    added: dict[str, set[str]] = {}
    current = None
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            current = line[len("+++ b/"):]
            continue
        if current is None or not line or line[0] not in "-+" or line.startswith(("---", "+++")):
            continue
        body = line[1:].strip()
        if not body.startswith("import "):
            continue
        bucket = removed if line[0] == "-" else added
        bucket.setdefault(current, set()).add(body[len("import "):].split(".")[-1])

    problems, skipped = [], []
    for path, symbols in sorted(removed.items()):
        file = REPO / path
        if not file.is_file():
            continue
        lines = code_lines(read_version(tip, path, file))
        still = {l.strip()[len("import "):].split(".")[-1]
                 for l in lines if l.strip().startswith("import ")}
        for symbol in sorted(symbols - added.get(path, set()) - still):
            if not used_as_call_or_member(lines, symbol):
                continue
            if declared_in_any_source_root(path, symbol):
                skipped.append(f"{path}: {symbol}")
                continue
            problems.append(f"{path}: 删掉的 import `{symbol}` 仍被调用/点号引用")

    if skipped:
        print(f"同包声明（冗余 import 被删，正常）：{len(skipped)} 处")
        for item in skipped[:4]:
            print(f"  – {item}")
    if problems:
        print(f"\n发现 {len(problems)} 处语义冲突（{base}..{tip}）：")
        for problem in problems:
            print(f"  ✗ {problem}")
        return 1
    print(f"\n检查了 {base}..{tip}：没有'删掉的 import 仍被引用' ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
