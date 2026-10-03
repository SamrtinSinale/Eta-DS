package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Debian rootfs 的"结构探针"判据。
 *
 * 回归背景有两条：
 *  1. 3.0.6.14/15 的清理脚本穿透挂载点删过一轮，**被挂载覆盖的目录内容**（dpkg 数据库首当其冲）
 *     可能被掏空，而就绪标记还在、apt/dpkg 二进制也在 —— 环境"看着是活的"。
 *  2. **3.0.6.22 的回归**：探针一度接进了 `baseRootfsReady`，而安装流程以它为准 —— 探针一误报，
 *     用户就卡在"基础环境安装不成功"。所以这里最重要的一条是：
 *     **探针只提示，缺核心路径不影响就绪、不 gate 安装。**
 *
 * 探针按「rootfs 路径 + 就绪标记 mtime」缓存，所以改完文件要 touch 标记（重装本来也会重写它）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DebianRootfsStructureTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun readyRootfs(): File {
        val rootfs = temporaryFolder.newFolder("rootfs")
        File(rootfs, "usr/bin/env").apply { parentFile?.mkdirs(); writeText("") }
        for (path in DebianEnvironmentInstaller.CORE_PATHS) {
            File(rootfs, path).apply { parentFile?.mkdirs(); writeText("") }
        }
        markReady(rootfs)
        return rootfs
    }

    private var stampSeq = 0L

    /** 就绪标记是安装最后一步写的；测试里用递增的 mtime 让探针缓存**确定性**失效。 */
    private fun markReady(rootfs: File, version: String = "13") {
        val marker = File(rootfs, LinuxEnvironmentPaths.READY_MARKER)
        marker.writeText("version=$version\nsha256=test\n")
        marker.setLastModified(1_700_000_000_000L + stampSeq++)
    }

    /**
     * wrapper 必须把期望版本**转发下去**。
     *
     * 回归：上一版 `baseRootfsReady(rootfs, expectedBaseVersion)` 的声明里有这个参数，函数体却只传了
     * 路径 —— 三个严格调用点（`status()`、`installBase` 幂等、`installTools` 前置）全都在传
     * `DEBIAN_VERSION`，但版本比对一次都没执行，"旧产物 → 提示重装"整条链路是死的。
     * 当时的测试都只调 `baseRootfsReady(rootfs)`（默认 null），恰好绕过这条路。
     */
    @Test
    fun strictVersionIsForwardedThroughTheDebianWrapper() {
        val rootfs = readyRootfs()
        markReady(rootfs, version = "12")

        assertTrue(
            "不传期望版本时保持宽松",
            DebianEnvironmentInstaller.baseRootfsReady(rootfs),
        )
        assertFalse(
            "传了期望版本就必须比对 —— 参数不能在 wrapper 里丢掉",
            DebianEnvironmentInstaller.baseRootfsReady(rootfs, expectedBaseVersion = "13"),
        )
        assertTrue(
            "版本一致时通过",
            DebianEnvironmentInstaller.baseRootfsReady(rootfs, expectedBaseVersion = "12"),
        )
    }

    /** 最要紧的一条：缺核心路径只进探针，**不 gate 安装**（3.0.6.22 就是这么弄坏安装的）。 */
    @Test
    fun missingCorePathIsReportedButDoesNotBlockReadiness() {
        val rootfs = readyRootfs()
        assertTrue(DebianEnvironmentInstaller.structureWarnings(rootfs).isEmpty())

        File(rootfs, "var/lib/dpkg/status").delete()
        markReady(rootfs)

        assertEquals(
            listOf("var/lib/dpkg/status"),
            DebianEnvironmentInstaller.structureWarnings(rootfs),
        )
        assertTrue(
            "探针只提示：标记在就是已装，安装流程不能被它挡住",
            DebianEnvironmentInstaller.baseRootfsReady(rootfs),
        )
    }

    @Test
    fun restoringTheCorePathClearsTheWarning() {
        val rootfs = readyRootfs()
        File(rootfs, "usr/bin/apt").delete()
        markReady(rootfs)
        assertEquals(listOf("usr/bin/apt"), DebianEnvironmentInstaller.structureWarnings(rootfs))

        File(rootfs, "usr/bin/apt").apply { parentFile?.mkdirs(); writeText("") }
        markReady(rootfs)

        assertTrue(DebianEnvironmentInstaller.structureWarnings(rootfs).isEmpty())
        assertTrue(DebianEnvironmentInstaller.baseRootfsReady(rootfs))
    }

    @Test
    fun missingAdvisoryToolsDoNotAffectTheWarningList() {
        // 能力清单里的东西（git/python3/gcc…）不属于"结构"，缺它们不该进告警。
        assertFalse(
            "git 不该出现在核心集里",
            DebianEnvironmentInstaller.CORE_PATHS.any { it.endsWith("/git") },
        )
        val rootfs = readyRootfs()   // 完全没有 git/python3/gcc

        assertTrue(DebianEnvironmentInstaller.structureWarnings(rootfs).isEmpty())
        assertTrue(DebianEnvironmentInstaller.baseRootfsReady(rootfs))
    }

    /** 诊断缺口：连 env 都没了（最狠的损坏）也要能打出"缺哪些路径"，不能短路掉。 */
    @Test
    fun missingEnvIsStillReportedInsteadOfShortCircuitingDiagnostics() {
        val rootfs = readyRootfs()
        File(rootfs, "usr/bin/env").delete()
        markReady(rootfs)

        assertEquals(listOf("usr/bin/env"), DebianEnvironmentInstaller.structureWarnings(rootfs))
    }

    /**
     * 宿主侧探针与**绝对**符号链接。
     *
     * 那台设备的 Debian 环境里 `usr/local/bin/node -> /opt/eta/node/26.8.1/bin/node` 就是这种形态：
     * 目标在宿主上不存在（那路径只在 App 私有目录里），`exists()` 恒假 —— 但东西是装了的。
     * 判据必须宽容，否则这条日志会长期说谎（`python3✗ node✗` 而它们好用）。
     */
    @Test
    fun absoluteSymlinksCountAsPresentOnTheHostSideProbe() {
        val rootfs = readyRootfs()

        val node = File(rootfs, "usr/local/bin/node")
        node.parentFile?.mkdirs()
        java.nio.file.Files.createSymbolicLink(
            node.toPath(),
            java.nio.file.Paths.get("/opt/eta/node/26.8.1/bin/node"),
        )
        assertFalse("宿主上本来就解析不了这个目标", node.exists())
        assertTrue(DebianEnvironmentInstaller.existsInRootfs(rootfs, "usr/local/bin/node"))

        val dpkg = File(rootfs, "usr/bin/dpkg")
        dpkg.delete()
        java.nio.file.Files.createSymbolicLink(
            dpkg.toPath(),
            java.nio.file.Paths.get("/opt/eta/dpkg"),
        )
        markReady(rootfs)

        assertTrue(
            "绝对链接也算在：不该因此报缺路径",
            DebianEnvironmentInstaller.structureWarnings(rootfs).isEmpty(),
        )
    }
}
