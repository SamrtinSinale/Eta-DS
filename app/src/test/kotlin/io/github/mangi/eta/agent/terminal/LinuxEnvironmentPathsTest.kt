package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxEnvironmentPathsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun rootfs(name: String): File = temporary.newFolder(name)

    private fun markReady(rootfs: File, sentinel: Boolean = true) {
        File(rootfs, LinuxEnvironmentPaths.READY_MARKER).writeText("version=13\n")
        if (sentinel) {
            File(rootfs, "usr/bin").mkdirs()
            File(rootfs, "usr/bin/env").writeText("#!/bin/sh\n")
        }
    }

    @Test
    fun markerWithoutRootfsContentIsNotReady() {
        // 回归：rootfs 被清空或搬走后标记文件会残留，空壳不能再被当成可用环境。
        val empty = rootfs("empty")
        markReady(empty, sentinel = false)

        assertFalse(LinuxEnvironmentPaths.rootfsReady(empty.absolutePath))
    }

    @Test
    fun markerWithRequiredBinaryIsReady() {
        val healthy = rootfs("healthy")
        markReady(healthy)

        assertTrue(LinuxEnvironmentPaths.rootfsReady(healthy.absolutePath))
    }

    @Test
    fun missingMarkerIsNotReady() {
        val bare = rootfs("bare")
        File(bare, "usr/bin").mkdirs()
        File(bare, "usr/bin/env").writeText("#!/bin/sh\n")

        assertFalse(LinuxEnvironmentPaths.rootfsReady(bare.absolutePath))
    }

    @Test
    fun blankPathIsNotReady() {
        assertFalse(LinuxEnvironmentPaths.rootfsReady(null))
        assertFalse(LinuxEnvironmentPaths.rootfsReady(""))
    }

    /**
     * 基础环境版本比版本：**只在传了期望版本时**生效。
     *
     * 使用路径（终端、文件浏览器、守护任务）不传，所以"版本旧但能用"的环境照旧可用；
     * 安装/修复路径传期望版本，旧环境会被判成需要重装 —— 这就是"App 升级了要提示重装"的入口。
     * 反过来严格判定绝不能出现在使用路径上：3.0.6.22 那次就是判定过严把可用状态弄坏。
     */
    @Test
    fun staleBaseVersionFailsOnlyTheStrictCheck() {
        val stale = rootfs("stale")
        markReady(stale, sentinel = true)
        File(stale, LinuxEnvironmentPaths.READY_MARKER).writeText("version=12\n")

        assertTrue(
            "使用路径保持宽松",
            LinuxEnvironmentPaths.rootfsReady(stale.absolutePath),
        )
        assertFalse(
            "安装/修复路径判需要重装",
            LinuxEnvironmentPaths.rootfsReady(stale.absolutePath, expectedBaseVersion = "13"),
        )
        assertTrue(
            "传同一个版本就通过",
            LinuxEnvironmentPaths.rootfsReady(stale.absolutePath, expectedBaseVersion = "12"),
        )
    }

    @Test
    fun missingOrNonNumericVersionFailsOnlyTheStrictCheck() {
        val bodies = listOf("", "sha256=abc\n", "version=abc\n", "version=\n", "version = 13\n")
        bodies.forEachIndexed { index, body ->
            val candidate = rootfs("marker-$index")
            markReady(candidate, sentinel = true)
            File(candidate, LinuxEnvironmentPaths.READY_MARKER).writeText(body)

            assertTrue(
                "宽松判定不变：${body.ifBlank { "<空标记>" }}",
                LinuxEnvironmentPaths.rootfsReady(candidate.absolutePath),
            )
            assertFalse(
                "严格判定要拒绝：${body.ifBlank { "<空标记>" }}",
                LinuxEnvironmentPaths.rootfsReady(candidate.absolutePath, expectedBaseVersion = "13"),
            )
        }
    }

    @Test
    fun readMarkerVersionFollowsTheInstallerFormat() {
        val candidate = rootfs("read")
        markReady(candidate, sentinel = true)
        File(candidate, LinuxEnvironmentPaths.READY_MARKER)
            .writeText("version=13\nsha256=deadbeef\ndistribution=debian\n")

        assertEquals(
            "13",
            LinuxEnvironmentPaths.readMarkerVersion(
                File(candidate, LinuxEnvironmentPaths.READY_MARKER),
            ),
        )
        assertNull(
            "标记不存在时给 null",
            LinuxEnvironmentPaths.readMarkerVersion(File(candidate, "missing-marker")),
        )
    }
}
