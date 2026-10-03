#!/usr/bin/env python3
"""检查测试里用了 JUnit/JUnit5/kotlin.test 断言却没导入（这类问题只在 CI 编译期暴露，白等一轮）。

判定很保守，只为不误报：
  - 只认一组已知断言名；
  - 认 `import org.junit.Assert.*` 这种通配导入；
  - 认任何以该名字结尾的 import（`import kotlin.test.assertFalse`、jupiter 同样算）；
  - 认全限定调用 `org.junit.Assert.assertFalse(...)`。
只有"用了、既没导入、也没写全限定"才报。
作用域闸门（**必须先写在断言里，否则「检查通过」会被误读成「没问题」**）：
  - **只扫测试根目录**（默认 `app/src/test`，可用第一个参数覆盖），不扫主源集；
  - 文件里必须出现 `junit` 或 `kotlin.test` 字样才进入检查 —— 没有这两个词的 Kotlin 文件
    本来就不是 JUnit 测试，跳过是刻意的（反向用例里若造一个"完全没有 junit 字样"的探针，
    它不会报警，这是设计而非漏洞）。

用法：python3 scripts/check-junit-imports.py [测试根目录]
"""
import pathlib
import re
import sys


def logging_hints(root: pathlib.Path) -> list[str]:
    """提示：同一名字的主类里用了 AndroidAgentLogger / android.util.Log，而测试没上 Robolectric。

    这类测试会死在 "Method i in android.util.Log not mocked"（仓库没开 isReturnDefaultValues），
    我已经踩过两次，所以至少打一行提示。**只提示，不算失败** —— 有些路径确实不经过日志。
    """
    hints = []
    for path in sorted(root.rglob("*Test.kt")):
        text = path.read_text(encoding="utf-8")
        if "RobolectricTestRunner" in text:
            continue
        subject = path.name.removesuffix("Test.kt")
        for main in pathlib.Path("app/src/main").rglob(f"{subject}.kt"):
            main_text = main.read_text(encoding="utf-8")
            if "AndroidAgentLogger" in main_text or "android.util.Log" in main_text:
                hints.append(f"{path}: 主类 {main.name} 用了日志，通常需要 @RunWith(RobolectricTestRunner::class)")
                break
    return hints

ASSERTS = ['assertArrayEquals', 'assertDoesNotThrow', 'assertEquals', 'assertFalse', 'assertNotEquals', 'assertNotNull', 'assertNotSame', 'assertNull', 'assertSame', 'assertThat', 'assertThrows', 'assertTimeout', 'assertTimeoutPreemptively', 'assertTrue', 'fail']
ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/test")

problems = []
for path in sorted(ROOT.rglob("*.kt")):
    text = path.read_text(encoding="utf-8")
    if "junit" not in text and "kotlin.test" not in text:
        continue
    if re.search(r"import org\.junit\.Assert\.\*", text):
        continue
    imported = set(re.findall(r"^import [\w.]*\.(\w+)$", text, flags=re.M))
    used = {name for name in re.findall(r"\b(assert\w+|fail)\s*\(", text) if name in ASSERTS}
    missing = sorted(
        name for name in used
        if name not in imported and f"org.junit.Assert.{name}(" not in text
    )
    if missing:
        problems.append(f"{path}: 缺 {', '.join(missing)} 的 import（或写成全限定调用）")

for problem in problems:
    print(f"::error::{problem}")
for hint in logging_hints(ROOT):
    print(f"提示：{hint}")
print(f"检查了 {ROOT}：{'有问题' if problems else '没发现问题'}")
sys.exit(1 if problems else 0)
