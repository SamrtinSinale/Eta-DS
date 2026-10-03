package io.github.mangi.eta.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 让路残骸的回收。
 *
 * 背景：改名让路只腾空原路径，**不释放磁盘**（实测现场有一棵 291MB 的完整 runtime 残骸，
 * 而且挂载跟着改名走，挂载不解就永远删不掉）。这里钉住：命名匹配只认让路产物、**深层也要扫到**、
 * 限流取最早、没挂载的残骸被删掉、以及"同一批删不干净时 24h 不重复重试"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StaleRetirementSweeperTest {

    /** 共享编解码的窗口由调用方给；这里等价于本对象的 RETRY_WINDOW_MS。 */
    private fun skip(stamp: SweepStamp?, nowMs: Long, names: List<String>): Boolean =
        shouldSkipSweep(stamp, nowMs, names, StaleRetirementSweeper.RETRY_WINDOW_MS)


    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun onlyRetiredNamesArePicked() {
        val filesDir = temporaryFolder.newFolder("files")
        File(filesDir, "dsh-runtime").mkdirs()                       // 在用的，不能碰
        File(filesDir, "dsh-runtime.installing").mkdirs()            // 解包中的，不能碰
        File(filesDir, "dsh-runtime.broken-1790864453989").mkdirs()  // 让路产物
        File(filesDir, "dsh-runtime.installing.broken-1790864453990").mkdirs()
        File(filesDir, "x.broken-123").mkdirs()                      // 时间戳位数不够，不认
        File(filesDir, "y.broken-1790864453991").createNewFile()     // 文件不算
        File(filesDir, "unrelated").mkdirs()

        val names = StaleRetirementSweeper
            .retiredDirectories(listOf(filesDir), limit = 10).map { it.name }

        assertEquals(
            listOf("dsh-runtime.broken-1790864453989", "dsh-runtime.installing.broken-1790864453990"),
            names,
        )
    }

    /** 深层残骸也要扫到：实机上 12/13 处让路发生在 rootfs 里面，只扫 filesDir 顶层会漏掉大半。 */
    @Test
    fun deeperRetirementsAreFoundToo() {
        val filesDir = temporaryFolder.newFolder("files")
        val rootfs = File(filesDir, "terminal/debian/rootfs")
        val deep = File(rootfs, "opt/eta/apk-analysis/current.broken-1790864453989")
        deep.mkdirs()
        File(filesDir, "dsh-runtime.broken-1790864453990").mkdirs()

        val names = StaleRetirementSweeper
            .retiredDirectories(listOf(filesDir, rootfs), limit = 10)
            .map { it.name }

        assertEquals(
            setOf("dsh-runtime.broken-1790864453990", "current.broken-1790864453989"),
            names.toSet(),
        )
    }

    @Test
    fun sweepHonoursTheLimitOldestFirst() {
        val filesDir = temporaryFolder.newFolder("files")
        for (stamp in listOf(1790864453981L, 1790864453982L, 1790864453983L)) {
            File(filesDir, "profiles.broken-$stamp").mkdirs()
        }

        val names = StaleRetirementSweeper
            .retiredDirectories(listOf(filesDir), limit = 2).map { it.name }

        assertEquals(
            listOf("profiles.broken-1790864453981", "profiles.broken-1790864453982"),
            names,
        )
    }

    /** 没挂载的残骸（普通临时目录）应当被删掉并计入释放数。 */
    @Test
    fun sweepDeletesUnmountedResidue() {
        val filesDir = temporaryFolder.newFolder("files")
        val residue = File(filesDir, "profiles.broken-1790864453989")
        File(residue, "inner").mkdirs()
        File(residue, "inner/file").writeText("x")

        val released = StaleRetirementSweeper.sweep(filesDir, listOf(filesDir))

        assertEquals(1, released)
        assertTrue("残骸应当已经不在", !residue.exists())
        assertEquals(
            SafeTreeDelete.Outcome.DELETED,
            SafeTreeDelete.deleteIfUnmounted(residue),
        )
    }

    /** 退避：同一批残骸窗口内不再重试；名单变了立刻重试。 */
    @Test
    fun sweepBacksOffForTheSameBatch() {
        val now = 1_000_000_000_000L
        val stamp = SweepStamp(listOf("a.broken-1790864453989"), now - 1_000)

        assertTrue(
            "同一批 + 窗口内应当跳过",
            skip(stamp, now, listOf("a.broken-1790864453989")),
        )
        assertTrue(
            "出现新残骸要立刻重试",
            !skip(
                stamp, now, listOf("a.broken-1790864453989", "b.broken-1790864453990"),
            ),
        )
        assertTrue(
            "窗口过了要重试",
            !skip(
                stamp, stamp.attemptedAtMs + 25 * 60 * 60 * 1000L, listOf("a.broken-1790864453989"),
            ),
        )
        assertTrue("没有记录就别跳过", !skip(null, now, emptyList()))
    }

    /**
     * 遍历**不跟符号链接**。
     *
     * Debian rootfs 里 `bin -> usr/bin`、`lib -> usr/lib` 都是链接：跟着走会把同一棵子树走两遍，
     * 若链接指到根外还会跑出预期范围。这里两个方向都钉住：根本身是链接 → 整棵跳过；
     * 子目录是链接 → 不进去。
     */
    @Test
    fun walkDoesNotFollowSymlinks() {
        val filesDir = temporaryFolder.newFolder("files")
        val real = File(filesDir, "real")
        File(real, "profiles.broken-1790864453989").mkdirs()

        val rootLink = File(filesDir, "root-link")
        java.nio.file.Files.createSymbolicLink(rootLink.toPath(), real.toPath())
        assertTrue(
            "根本身是符号链接时整棵跳过",
            StaleRetirementSweeper.retiredDirectories(listOf(rootLink), limit = 10).isEmpty(),
        )

        val nested = File(filesDir, "nested")
        nested.mkdirs()
        val nestedLink = File(nested, "inner-link")
        java.nio.file.Files.createSymbolicLink(nestedLink.toPath(), real.toPath())
        assertTrue(
            "子目录是符号链接时不进去",
            StaleRetirementSweeper.retiredDirectories(listOf(nested), limit = 10).isEmpty(),
        )

        assertEquals(
            "真实路径仍然能扫到",
            listOf("profiles.broken-1790864453989"),
            StaleRetirementSweeper.retiredDirectories(listOf(real), limit = 10).map { it.name },
        )
    }

    /**
     * App 身份删不干净时（残骸里剩 root 属主文件）要调用一次提权兜底，并把最终结果上报。
     *
     * 这里用 chmod 000 的子目录模拟"删不干净"（测试进程不是 root，删不动）。
     */
    @Test
    fun incompleteResidueTriggersThePrivilegedFallback() {
        val filesDir = temporaryFolder.newFolder("files")
        val residue = File(filesDir, "profiles.broken-1790864453989")
        val locked = File(residue, "locked")
        locked.mkdirs()
        File(locked, "file").writeText("root-ish")
        assertTrue("准备条件：要能挡住删除", locked.setReadable(false) && locked.setExecutable(false))

        val purged = mutableListOf<String>()
        val unreleased = mutableListOf<SafeTreeDelete.Outcome>()
        val released = StaleRetirementSweeper.sweep(
            filesDir = filesDir,
            roots = listOf(filesDir),
            purge = { target ->
                purged += target.name
                null // 模拟"提权也没成功"，于是仍然是 INCOMPLETE
            },
            onUnreleased = { _, outcome -> unreleased += outcome },
        )

        runCatching { locked.setReadable(true); locked.setExecutable(true) }

        assertEquals(
            "提权兜底必须被调用一次",
            listOf("profiles.broken-1790864453989"),
            purged,
        )
        assertEquals(0, released)
        assertEquals(listOf(SafeTreeDelete.Outcome.INCOMPLETE), unreleased)
    }

    /**
     * 提权兜底返回 null 也可能**已经成功**（`purgeLeftovers` 的 null 混了"su 超时"和"目录已不在"）。
     *
     * 所以兜底之后必须重新探测，以磁盘事实为准：这里让钩子把残骸删掉、然后返回 null。
     */
    @Test
    fun privilegedFallbackIsJudgedByDiskNotByItsReturnValue() {
        val filesDir = temporaryFolder.newFolder("files")
        val residue = File(filesDir, "profiles.broken-1790864453989")
        File(residue, "locked").mkdirs()
        File(residue, "locked/file").writeText("root-ish")
        File(residue, "locked").setReadable(false)

        val released = StaleRetirementSweeper.sweep(
            filesDir = filesDir,
            roots = listOf(filesDir),
            purge = { target ->
                // 挡住删除的是 locked/ 自己（不是它的父目录），所以要恢复它。
                File(target, "locked").setReadable(true)
                target.deleteRecursively()
                null // 关键：返回 null，但东西已经清掉了
            },
        )

        runCatching { residue.setReadable(true) }
        assertTrue("残骸应当已被回收", !residue.exists())
        assertEquals("不能因为它返回 null 就报未释放", 1, released)
    }

    @Test
    fun sweepStampRoundTrips() {
        val raw = "a.broken-1790864453989\nb.broken-1790864453990\n1790864453989\n"

        val stamp = parseSweepStamp(raw)

        assertEquals(
            listOf("a.broken-1790864453989", "b.broken-1790864453990"),
            stamp?.names,
        )
        assertEquals(1790864453989L, stamp?.attemptedAtMs)
        assertEquals(null, parseSweepStamp("garbage"))
    }
}
