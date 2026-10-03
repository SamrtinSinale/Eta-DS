package io.github.mangi.eta.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 递归删的挂载点判定（纯函数部分）。
 *
 * 回归背景：`deleteRecursively()` 会走进挂载点、把挂载源一起删掉 —— 3.0.6.14/15 就是这么删掉
 * Debian rootfs 内容的。这里钉住三条：**目录自身**是挂载点、**其下**是挂载点，都要判真；
 * **兄弟目录**（`dsh-runtime` vs `dsh-runtime.installing`）绝不能误判。
 */
class SafeTreeDeleteTest {

    @Test
    fun mountOnTheDirectoryItselfCounts() {
        assertTrue(
            SafeTreeDelete.hasMountUnder(
                listOf("/data/data/app/files/dsh-runtime"),
                "/data/data/app/files/dsh-runtime",
            ),
        )
    }

    @Test
    fun mountAnywhereBelowCounts() {
        assertTrue(
            SafeTreeDelete.hasMountUnder(
                listOf("/data/data/app/files/dsh-runtime/dev", "/x/y"),
                "/data/data/app/files/dsh-runtime",
            ),
        )
    }

    @Test
    fun siblingWithTheSamePrefixDoesNotCount() {
        // 这一条是 e6aec9c 那次的教训：前缀匹配会误伤 dsh-runtime.installing。
        assertFalse(
            SafeTreeDelete.hasMountUnder(
                listOf("/data/data/app/files/dsh-runtime.installing/mnt"),
                "/data/data/app/files/dsh-runtime",
            ),
        )
        assertFalse(
            SafeTreeDelete.hasMountUnder(
                listOf("/data/data/app/files/skills-backup"),
                "/data/data/app/files/skills",
            ),
        )
    }

    /**
     * procfs 会把空格写成 `\040`、反斜杠写成 `\134`。
     *
     * 不解码就直接 `File(raw).canonicalPath`，解析不了就会**回落到带转义的原串**，于是跟规范化后
     * 的路径永远对不上 → 判成"没挂载" → fail-open（正是要避免的方向）。这条钉住解码。
     */
    @Test
    fun parseMountPointsDecodesProcfsEscapes() {
        // 用 raw string：Kotlin 没有八进制转义，普通字符串写 "\040" 是编译错误。
        val line = """36 25 0:32 / /data/local/tmp/eta\040work rw,nosuid - tmpfs tmpfs rw"""

        assertEquals(listOf("/data/local/tmp/eta work"), SafeTreeDelete.parseMountPoints(listOf(line)))
        assertEquals("/a\\b", SafeTreeDelete.unescapeMountPath("""/a\134b"""))
    }

    /** 解析不了就返回 null —— 调用方按 fail-closed 处理（当作有挂载，不改不删）。 */
    @Test
    fun parseMountPointsFailsClosedOnGarbage() {
        assertNull("字段不够", SafeTreeDelete.parseMountPoints(listOf("too short")))
        assertNull(
            "只有一行坏掉也算坏",
            SafeTreeDelete.parseMountPoints(
                listOf("36 25 0:32 / /x rw - tmpfs tmpfs rw", "broken line"),
            ),
        )
        assertNull("未知转义不能猜", SafeTreeDelete.unescapeMountPath("""/a\777b"""))
    }

    @Test
    fun parseMountPointsKeepsOrdinaryPathsAsIs() {
        val lines = listOf(
            "36 25 0:32 / /data/user/0/app/files/dsh-runtime/dev rw - tmpfs tmpfs rw",
            "37 25 0:32 / /proc rw - proc proc rw",
        )

        assertEquals(
            listOf("/data/user/0/app/files/dsh-runtime/dev", "/proc"),
            SafeTreeDelete.parseMountPoints(lines),
        )
    }

    @Test
    fun unrelatedMountsDoNotCount() {
        assertFalse(
            SafeTreeDelete.hasMountUnder(
                listOf("/proc", "/dev", "/data/data/other/files/dsh-runtime"),
                "/data/data/app/files/dsh-runtime",
            ),
        )
        assertFalse(SafeTreeDelete.hasMountUnder(emptyList(), "/data/data/app/files/dsh-runtime"))
    }
}
