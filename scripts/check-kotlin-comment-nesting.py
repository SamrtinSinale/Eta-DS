#!/usr/bin/env python3
"""检查 Kotlin 注释正文里的 `/*` —— 块注释可嵌套，这种写法会吞掉后面的代码。

KDoc 里写路径通配（例如 ``root/.dsh/**``）就会踩到：那个 `/*` 开一个嵌套注释，后面的 `*/`
只关掉嵌套层，外层永不闭合。编译器的报错毫无指向性（"Expecting ';' after the last enum entry"），
所以这里静态挡一道。

判定要点（不误报）：
  - 认字符串（三引号 raw string 与普通字符串、含转义）与字符字面量 —— `"image/*"` 这种不是注释；
  - 认 `//` 行注释；
  - 只报"**已经在块注释里**又遇到 `/*`"。

用法：python3 scripts/check-kotlin-comment-nesting.py [源码根目录]
"""
import pathlib
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main")


def scan(path: pathlib.Path) -> list[tuple[int, str]]:
    problems: list[tuple[int, str]] = []
    in_block = False
    in_raw = False
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        index = 0
        length = len(line)
        while index < length:
            if in_raw:
                end = line.find('"""', index)
                if end < 0:
                    break
                in_raw = False
                index = end + 3
                continue
            if in_block:
                nested = line.find("/*", index)
                close = line.find("*/", index)
                if nested >= 0 and (close < 0 or nested < close):
                    problems.append((number, "注释正文里出现 `/*`（Kotlin 块注释可嵌套，会让后面的代码被吞）"))
                    in_block = False
                    index = nested + 2
                    continue
                if close < 0:
                    break
                in_block = False
                index = close + 2
                continue
            # 非注释、非 raw string：按 token 前进
            if line.startswith('"""', index):
                in_raw = True
                index += 3
                continue
            if line.startswith("//", index):
                break
            if line.startswith("/*", index):
                in_block = True
                index += 2
                continue
            char = line[index]
            if char == '"' or char == "'":
                quote = char
                index += 1
                while index < length:
                    if line[index] == "\\":
                        index += 2
                        continue
                    if line[index] == quote:
                        index += 1
                        break
                    index += 1
                continue
            index += 1
    return problems


def main() -> int:
    problems: list[str] = []
    for path in sorted(ROOT.rglob("*.kt")):
        for number, message in scan(path):
            problems.append(f"{path}:{number}: {message}")
    for problem in problems:
        print(f"::error::{problem}")
    print(f"检查了 {ROOT}：{'有问题' if problems else '没发现问题'}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
