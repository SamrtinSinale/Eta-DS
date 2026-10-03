package io.github.mangi.eta.agent.terminal

import java.io.File
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
}
