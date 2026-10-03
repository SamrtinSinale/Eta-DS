package io.github.mangi.eta.core

import java.io.File
import java.nio.file.Files

/**
 * 回收 [SafeTreeDelete] "改名让路"留下的残骸。
 *
 * 让路产物叫 `<原名字>.broken-<毫秒时间戳>`，散落在各安装器自己的目录里 —— 有 `filesDir` 顶层的，
 * 也有 rootfs 深处的（`opt/eta/apk-analysis/current.broken-*` 之类），所以**要按根扫**，只扫顶层
 * 会漏掉大半。没有回收方的话它们就是看不见的磁盘增长（实测有一棵完整的 291MB runtime 残骸）。
 *
 * 行为：
 *  - 只认 `<名字>.broken-<≥10 位数字>` 形态的**目录**，别的路径一概不碰；
 *  - **遍历不跟符号链接**：Debian rootfs 里 `bin -> usr/bin`、`lib -> usr/lib` 都是链接，跟着走
 *    会把同一棵子树走两遍，也可能跑出预期范围；
 *  - 有挂载就原样留着（`SKIPPED`，不再改名，免得每开一次 App 多一个残骸）；
 *  - 删不干净（残骸里剩 root 属主文件，App 身份删不掉）记 `INCOMPLETE`，**退避 24h**，并且给一次
 *    [purge] 提权兜底（由组合根接上 `DshRuntimeInstaller.purgeAsRoot`）—— 否则那截 root 尾巴永远
 *    不会消失；
 *  - 每次最多 [limit] 个，按路径取最早。
 */
internal object StaleRetirementSweeper {

    internal const val DEFAULT_LIMIT = 2

    /** 扫多深：让路产物最深大约在 `opt/eta/<name>/<version>.broken-*`。 */
    internal const val MAX_DEPTH = 4

    /** 同一批残骸 24h 只再试一次；残骸名单变了立刻重试。 */
    internal const val RETRY_WINDOW_MS = 24 * 60 * 60 * 1000L

    private const val STAMP_NAME = ".retirement-sweep-stamp"

    /** 让路命名：`<名字>.broken-<时间戳>`。名字里可以再带点（`dsh-runtime.installing.broken-1`）。 */
    private val RETIRED_NAME = Regex(""".+\.broken-\d{10,}""")

    /** 纯选择，便于单测：在若干根目录下（有限深度、**不跟符号链接**）找让路产物。 */
    internal fun retiredDirectories(roots: List<File>, limit: Int = DEFAULT_LIMIT): List<File> {
        val found = ArrayList<File>()
        for (root in roots) {
            if (!root.isDirectory || isSymlink(root)) continue
            collect(root, 0, found)
            if (found.size >= limit) break
        }
        return found.distinctBy { it.absolutePath }.sortedBy { it.absolutePath }.take(limit)
    }

    private fun collect(directory: File, depth: Int, into: MutableList<File>) {
        if (depth > MAX_DEPTH) return
        val children = directory.listFiles() ?: return
        for (child in children) {
            if (isSymlink(child) || !child.isDirectory) continue
            if (RETIRED_NAME.matches(child.name)) {
                into += child
                continue
            }
            collect(child, depth + 1, into)
        }
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * 尽力回收，返回实际释放的目录数。
     *
     * @param purge 提权兜底：App 身份删不干净时调用一次（由组合根接上；默认不做，方便单测）。
     * @param onUnreleased 最终没释放成的回调（带原因），调用方拿去记日志。
     */
    internal fun sweep(
        filesDir: File,
        roots: List<File>,
        limit: Int = DEFAULT_LIMIT,
        purge: (File) -> Int? = { null },
        onUnreleased: (File, SafeTreeDelete.Outcome) -> Unit = { _, _ -> },
    ): Int {
        val victims = retiredDirectories(roots, limit)
        if (victims.isEmpty()) return 0

        val stampFile = File(filesDir, STAMP_NAME)
        val names = victims.map { it.absolutePath }
        val stamp = parseSweepStamp(runCatching { stampFile.readText() }.getOrNull())
        if (shouldSkipSweep(stamp, System.currentTimeMillis(), names, RETRY_WINDOW_MS)) return 0
        runCatching {
            stampFile.writeText(names.joinToString("\n") + "\n" + System.currentTimeMillis() + "\n")
        }

        var released = 0
        for (victim in victims) {
            var outcome = SafeTreeDelete.deleteIfUnmounted(victim)
            if (outcome == SafeTreeDelete.Outcome.INCOMPLETE) {
                // root 属主的尾巴 App 删不掉：给一次提权兜底（脚本自带"先验挂载再删"的护栏）。
                // **不看它的返回值**：null 既可能是 su 超时，也可能是"目录已经不存在了"（那其实
                // 已经成功）。一律重新探测，以磁盘事实为准 —— 用返回值当结论会让日志说谎。
                runCatching { purge(victim) }
                outcome = SafeTreeDelete.deleteIfUnmounted(victim)
            }
            when (outcome) {
                SafeTreeDelete.Outcome.DELETED -> {
                    released++
                    AndroidAgentLogger.info("已回收让路残骸：${victim.absolutePath}")
                }
                else -> onUnreleased(victim, outcome)
            }
        }
        return released
    }
}
