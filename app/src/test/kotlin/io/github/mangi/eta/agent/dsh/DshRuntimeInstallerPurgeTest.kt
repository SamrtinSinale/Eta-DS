package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.core.SweepStamp
import io.github.mangi.eta.core.parseSweepStamp
import io.github.mangi.eta.core.shouldSkipSweep
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 安装器清空运行时目录时用的 root 脚本。
 *
 * 回归背景：这里以前只是一句 `su -c rm -rf`。可运行时目录是**挂载宿主**（`/dev`、`/proc`、
 * 技能库都挂在这个目录里），挂载点还在时 `rm -rf` 删不干净，App 却毫不知情，接着往脏目录里
 * 解包，最后表现为永远"未就绪"。所以脚本必须：先摘挂载 → 再删 → 最后验一遍删干净。
 */
class DshRuntimeInstallerPurgeTest {

    /**
     * 共享编解码的窗口由调用方给。installer 的窗口常量是 private，这里用等价字面量，
     * 并由不变式钉住"两侧窗口都还是 24h"。
     */
    private fun skip(stamp: SweepStamp?, nowMs: Long, names: List<String>): Boolean =
        shouldSkipSweep(stamp, nowMs, names, 24 * 60 * 60 * 1000L)


    @Test
    fun purgeScriptCanonicalisesPathsAndVerifiesBeforeDeletingAnything() {
        val script = runtimePurgeScript("/data/user/0/io.sartin.eats/files/dsh-runtime")

        // /data/user/0 是指向 /data/data 的符号链接，内核把挂载点记成解析后的路径。
        // 不规范化就匹配不到 dev / proc / 技能库这些真实挂载 —— rm -rf 会穿过它们删宿主。
        assertTrue("没有规范化路径：$script", script.contains("readlink -f"))

        // 关键顺序：先确认挂载摘干净，再删。删完才报错 = 先损坏再报错。
        val abort = script.indexOf("HETA_PURGE_ABORT")
        val remove = script.indexOf("rm -rf")
        assertTrue("没有删除前的挂载确认：$script", abort >= 0)
        assertTrue("没有删除动作：$script", remove >= 0)
        assertTrue("确认放在 rm -rf 之后了：$script", abort < remove)

        // 嵌套挂载按层数从深到浅摘，不是字典序。
        assertTrue("没按深度排序：$script", script.contains("awk -F/ '{print NF,"))
        // 目标已被删掉的孤儿挂载不能当阻塞（设备上就是这么被卡死的）。
        assertTrue("没识别孤儿挂载：$script", script.contains("HETA_PURGE_ORPHAN"))
        // umount 失败必须带上原因，别再静默。
        assertTrue("umount 失败没记原因：$script", script.contains("HETA_UMOUNT_FAILED"))
        // 兜底：路径太浅（误传 / 或 /data）时什么都不做。
        assertTrue("没有路径深度兜底：$script", script.contains("/*/*/*/*"))
        assertTrue("没先 umount：$script", script.contains("umount -l"))
        assertTrue("没扫 mountinfo：$script", script.contains("/proc/self/mountinfo"))
        assertTrue("没删目录：$script", script.contains("rm -rf"))
        assertTrue("删完没校验：$script", script.contains("HETA_PURGE_INCOMPLETE"))
        assertTrue("校验失败没退非 0：$script", script.contains("exit 1"))
    }

    @Test
    fun purgeScriptQuotesTheTargetPath() {
        val script = runtimePurgeScript("/data/user/0/it's/files/dsh-runtime")

        assertTrue("路径没按 shell 规矩转义：$script", script.contains("it'\\''s"))
    }

    /**
     * 把当前实现生成的脚本写到 `build/dsh-purge-script.sh`，交给
     * `scripts/test-dsh-purge.sh` 在 CI 里做**行为**验证：真造出「符号链接前缀 + bind 挂载」，
     * 真跑脚本，断言挂载源里的数据没被 `rm -rf` 穿过删掉。
     *
     * 单测自己造不了挂载，所以这里只负责把脚本文本落盘；目标路径用占位符，shell 测试再替换成
     * 它自己的临时目录。这样脚本只有一份真相（Kotlin），行为测试用的是它真实的输出。
     */
    @Test
    fun writesTheGeneratedScriptForTheShellBehaviourTest() {
        val file = File("build/dsh-purge-script.sh")
        file.parentFile?.mkdirs()
        file.writeText(runtimePurgeScript(BEHAVIOUR_TEST_TARGET))

        assertTrue("脚本没写出来：${file.absolutePath}", file.length() > 0)
        assertTrue(
            "生成的脚本里应当带占位目标（shell 测试要替换它）",
            file.readText().contains(BEHAVIOUR_TEST_TARGET),
        )
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * 残骸识别的边界：只认 `dsh-runtime.broken-*` **目录**，别把正在用的运行时、staging
     * 目录、同前缀的文件或无关目录卷进来。
     */
    @Test
    fun staleRuntimeDirsOnlyPicksRenamedCorpseDirectories() {
        val filesDir = temporaryFolder.newFolder("files")
        File(filesDir, "dsh-runtime").mkdirs()
        File(filesDir, "dsh-runtime.installing").mkdirs()
        File(filesDir, "dsh-runtime.broken-111").mkdirs()
        File(filesDir, "dsh-runtime.broken-222").mkdirs()
        File(filesDir, "dsh-runtime.installing.broken-333").mkdirs()
        File(filesDir, "dsh-runtime.broken-444").createNewFile()
        File(filesDir, "unrelated").mkdirs()

        val names = DshRuntimeInstaller.staleRuntimeDirs(filesDir).map { it.name }

        assertEquals(
            listOf("dsh-runtime.broken-111", "dsh-runtime.broken-222", "dsh-runtime.installing.broken-333"),
            names,
        )
    }

    /** 每次只清最早的两个：每个残骸都是一次 su，别让启动路径堆起来。 */
    @Test
    fun staleRuntimeDirsHonoursTheSweepLimitOldestFirst() {
        val filesDir = temporaryFolder.newFolder("files")
        for (index in 1..4) File(filesDir, "dsh-runtime.broken-10$index").mkdirs()

        val names = DshRuntimeInstaller.staleRuntimeDirs(filesDir, limit = 2).map { it.name }

        assertEquals(listOf("dsh-runtime.broken-101", "dsh-runtime.broken-102"), names)
    }

    /** 退避：同一批残骸窗口内只试一次；名单变了要立刻再试。 */
    @Test
    fun sweepBackoffOnlySkipsTheSameBatchInsideTheWindow() {
        val now = 1_000_000_000_000L
        val stamp = SweepStamp(listOf("dsh-runtime.broken-1"), now - 1_000)

        assertTrue(
            "同一批 + 窗口内应当跳过",
            skip(stamp, now, listOf("dsh-runtime.broken-1")),
        )
        assertTrue(
            "出现新残骸要立刻再试",
            !skip(
                stamp, now, listOf("dsh-runtime.broken-1", "dsh-runtime.broken-2"),
            ),
        )
        assertTrue(
            "窗口过了要再试",
            !skip(
                stamp, stamp.attemptedAtMs + 25 * 60 * 60 * 1000L, listOf("dsh-runtime.broken-1"),
            ),
        )
        assertTrue("没有记录就别跳过", !skip(null, now, emptyList()))
    }

    @Test
    fun sweepStampRoundTripsThroughItsFileFormat() {
        val raw = "dsh-runtime.broken-111\ndsh-runtime.installing.broken-222\n1790833211587\n"

        val stamp = parseSweepStamp(raw)

        assertEquals(
            listOf("dsh-runtime.broken-111", "dsh-runtime.installing.broken-222"),
            stamp?.names,
        )
        assertEquals(1790833211587L, stamp?.attemptedAtMs)
        assertEquals(null, parseSweepStamp("garbage"))
    }

    companion object {
        /** 供 shell 行为测试替换的占位目标；层数要够，别被浅路径兜底拦下。 */
        const val BEHAVIOUR_TEST_TARGET = "/dsh-purge-placeholder-4f3a/data/files/dsh-runtime"
    }
}
