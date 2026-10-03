package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream

/**
 * 把随 APK 一起分发的 DeepSeek Harness 运行时展开到应用私有目录。
 *
 * 运行时是一个最小 Linux 用户态：只含 Node 依赖的 glibc 集合、Node 二进制和 dsh
 * 本体，压缩后约 47.5MB，展开后约 278MB。首次启动时展开一次，之后由标记文件跳过。
 *
 * 这样用户安装 APK 后不需要自己安装 Linux 发行版、Node 或 npm 包。
 */
internal object DshRuntimeInstaller {
    private const val TAG = "DshRuntimeInstaller"
    private const val ASSET_NAME = "dsh-runtime.tar.xz"
    private const val ROOT_DIR_NAME = "dsh-runtime"
    private const val READY_MARKER = ".runtime-ready"
    /** 更换 dsh-runtime.tar.xz 时必须递增，确保已安装 APK 重新解包。 */
    private const val REVISION = 8

    /** 解包先落在这个兄弟目录，验完再整体改名就位（避免脏目录被当成已就绪）。 */
    private const val STAGING_DIR_NAME = "$ROOT_DIR_NAME.installing"

    /** 清理旧运行时的等待上限：su 卡在授权上时不能把调用方线程永久挂住。 */
    private const val PURGE_TIMEOUT_MS = 20_000L

    /** 清理脚本输出最多记多少行（按行截断，不是按字符一刀切）。 */
    private const val PURGE_LOG_LINES = 24

    /** 每次安装最多回收几个残骸（每个都是一次 su，最坏 20s）。 */
    private const val STALE_SWEEP_LIMIT = 2

    /** 残骸目录的命名前缀：运行时的、以及解包目录的（[retireAside] 用的就是这套）。 */
    private val STALE_PREFIXES = listOf("$ROOT_DIR_NAME.broken-", "$STAGING_DIR_NAME.broken-")

    /** sweep 退避：同一批残骸在这个窗口内只试一次。 */
    private const val SWEEP_RETRY_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** 退避记录文件（放在 filesDir 下，和运行时目录同级）。 */
    private const val SWEEP_STAMP_NAME = ".dsh-sweep-stamp"

    /** chroot 之后 dsh 的入口，供启动命令使用。 */
    const val NODE_IN_ROOT = "/opt/node/bin/node"
    const val DSH_ENTRY_IN_ROOT = "/opt/dsh/lib/bin.js"
    const val WORKSPACE_IN_ROOT = "/workspace"
    const val HOME_IN_ROOT = "/root"

    fun runtimeDirectory(context: Context): File = File(context.filesDir, ROOT_DIR_NAME)

    fun isReady(context: Context): Boolean {
        val root = runtimeDirectory(context)
        val marker = File(root, READY_MARKER)
        if (!marker.exists() || marker.readText().trim() != "revision=$REVISION") return false
        return File(root, "opt/node/bin/node").exists() &&
            File(root, "opt/dsh/lib/bin.js").exists()
    }

    /**
     * 幂等安装；失败时清理半成品，避免留下一个"看起来已就绪"的目录。
     *
     * 解包先落在 [STAGING_DIR_NAME] 这个兄弟目录，验完标记文件再整体改名就位：这样
     * "旧目录删不掉"和"解包中途失败"都不会留下一个内容混杂、却被 [isReady] 认成已就绪的
     * 运行时。旧目录删不掉时**明确失败**（`deleteRecursively` 的返回值会看），
     * 而不是往脏目录上继续铺新文件——9-30 那次就是这么装出一个永远"未就绪"的目录的。
     */
    @Synchronized
    fun ensureInstalled(context: Context, force: Boolean = false): Boolean {
        val root = runtimeDirectory(context)
        if (!force && isReady(context)) {
            // 残骸回收必须放在这个早返回**之前**：健康装机下面就直接返回了，放到后面等于永远
            // 扫不到（发布出去的 3.0.6.18 就是这样：残骸一直躺在那，logcat 里一条都没有）。
            // 没有残骸时 staleRuntimeDirs() 返回空列表 —— 不开 su、零成本。
            sweepStaleRuntimes(root.parentFile)
            return true
        }
        val staging = File(root.parentFile, STAGING_DIR_NAME)
        return runCatching {
            // 先收历次让路留下的残骸（含上次的 staging 残骸），再动本次这两个目录 ——
            // 放在后面会把"本轮刚让路的那棵树"也扫一遍，白等一次 su。
            sweepStaleRuntimes(root.parentFile)
            val stagingExit = purgeLeftovers(staging)
            if (staging.exists()) {
                // 同一条护栏：purge 没成功（还有摘不掉的挂载 / su 没跑）就绝不递归删 ——
                // `deleteRecursively()` 是 walkBottomUp() 逐个 delete()，会走进挂载点。
                if (stagingExit != 0 || !staging.deleteRecursively()) {
                    retireAside(staging) ?: error("解包目录删不掉：${staging.absolutePath}")
                }
            }
            val rootExit = purgeLeftovers(root)
            if (root.exists() && (rootExit != 0 || !root.deleteRecursively())) {
                // 删不掉多半是里面还有挂载点：外部工具链的 bind、或者目标已被删除的孤儿挂载。
                // 孤儿挂载 umount 找不到路径、rm 也删不到，但**改名不影响它** —— 与其把用户卡在
                // "未就绪"，不如把旧目录整体改名让路：挂载点跟着改名走，新运行时立刻可用；
                // 等挂载消失，残骸由 [sweepStaleRuntimes] 自动回收。
                retireAside(root)?.let { aside ->
                    Log.w(TAG, "旧运行时目录让路：${aside.absolutePath}（purge exit=${rootExit ?: "未跑"}）")
                } ?: error("旧运行时目录删不掉（多半还有挂载）：${root.absolutePath}")
            }
            if (!staging.mkdirs() && !staging.exists()) error("无法创建解包目录")
            context.assets.open(ASSET_NAME).use { raw ->
                XZInputStream(BufferedInputStream(raw)).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        extract(tar, staging)
                    }
                }
            }
            File(staging, READY_MARKER).writeText("revision=$REVISION\n")
            if (!staging.renameTo(root)) {
                error("运行时目录就位失败：${staging.absolutePath} -> ${root.absolutePath}")
            }
            Log.i(TAG, "runtime installed at ${root.absolutePath}")
            true
        }.getOrElse { throwable ->
            Log.w(TAG, "runtime install failed", throwable)
            // 不变式：任何递归删之前都要先 purge 成功。这里删不掉就改名让路 —— rename 不会
            // 穿过挂载点，残骸交给 [sweepStaleRuntimes] 下次再试。
            runCatching { if (staging.exists()) retireAside(staging) }
            false
        }
    }

    /**
     * 以 root 清一个目录（复用安装路径那套 su + 护栏脚本）。
     *
     * 给让路残骸的回收当提权兜底：残骸里 root 属主的内容 App 身份删不掉，只能提权；脚本本身
     * "先验挂载再删、摘不干净就一个字不删"。
     *
     * @return 脚本退出码；没跑起来返回 null。
     */
    internal fun purgeAsRoot(target: File): Int? =
        // 换个标签：它现在服务的可能是 apk-analysis / profiles 的残骸，叫 "purge leftovers"
        //（安装路径的语义）会让人以为在清运行时。
        purgeLeftovers(target, label = "残骸提权清理")

    /**
     * 强制重装：把已就绪的运行时也清掉重解包。
     *
     * 给设置里的"重装对话运行时"入口用，同时也是**验证清理路径**（先摘挂载 → 解包到 staging
     * → 就位）的唯一通道 —— 正常升级只在 [REVISION] 变化时才走到那里。
     *
     * **不靠删就绪标记来实现**：清理失败（挂载摘不掉、su 被拒）时旧目录还好端端地在那儿，
     * 标记却已经没了 —— 一次点击就把用户本来能用的 dsh 变成永久"未就绪"。走
     * [ensureInstalled] 的 `force`：失败时旧目录与旧标记原样保留，`isReady()` 依旧成立。
     * 真正就位靠 staging 的 rename 整体替换，本来也不需要事先删标记。
     *
     * 代价：DSH_HOME 在运行时目录里，dsh 侧的历史会一起没（App 侧映射表还在，下一轮会回落到
     * "新会话 + 历史摘要"）。所以要在对话空闲时用。
     */
    @Synchronized
    fun reinstall(context: Context): Boolean = ensureInstalled(context, force = true)

    /**
     * 借 su 清掉旧运行时里的残留：先摘挂载，再删 root 文件。
     *
     * dsh 是通过 su 以 root 身份跑的，它在运行时目录里留下的会话文件（/workspace、日志、
     * node 缓存）owner 是 root，App 进程既删不掉子文件也进不去这些目录。
     *
     * 以前这里只是一句 `su -c rm -rf`，三个问题：不 umount（挂载点还在时删不掉，见
     * [runtimePurgeScript]）、不看退出码、`waitFor()` 没有超时（su 卡在授权弹窗上就会把
     * 调用方线程永久挂住，界面永远"未就绪"且没有任何报错）。
     *
     * @return 脚本退出码；目录不存在（没跑）或超时返回 null。
     */
    private fun purgeLeftovers(root: File, label: String = "purge leftovers"): Int? {
        if (!root.exists()) return null
        return runCatching {
            // 输出重定向到临时文件，而不是留管道到 waitFor 之后再读：管道只有 64KB，
            // 子进程若在退出前写满就会卡在 write 上、永远不退出，于是这里只会看到"超时"，
            // 真正的原因（脚本输出太多）反而丢了。写文件就没有这个上限。
            val log = File.createTempFile("dsh-purge-", ".log")
            try {
                val started = ProcessBuilder("su", "-c", runtimePurgeScript(root.absolutePath))
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start()
                if (!started.waitFor(PURGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    started.destroyForcibly()
                    Log.w(TAG, "$label 超时（${PURGE_TIMEOUT_MS}ms）：su 可能卡在授权，目录保持原样")
                    return null
                }
                logPurgeOutcome(
                    started.exitValue(),
                    runCatching { log.readText() }.getOrDefault(""),
                    label,
                )
                started.exitValue()
            } finally {
                runCatching { log.delete() }
            }
        }.onFailure { Log.w(TAG, "$label failed", it) }.getOrNull()
    }

    /**
     * 记一条清理结果。
     *
     * 输出按**行**截断。以前是 600 字符一刀切：真机上 19 个挂载的 `UMOUNT_FAILED` /
     * `ORPHAN` 行一多就只剩半截，排查又得靠猜。同时把"exit=0 且没有值得注意的行"（正常情况
     * 只会带一条 HETA_PURGE_ORPHAN）降到 info，别让每次安装都刷一条 WARN。
     */
    private fun logPurgeOutcome(exitCode: Int, output: String, label: String) {
        val lines = output.trim().lines().filter { it.isNotBlank() }
        val notable = exitCode != 0 ||
            lines.any { it.contains("FAILED") || it.contains("ABORT") || it.contains("INCOMPLETE") }
        val rendered = buildString {
            append(lines.take(PURGE_LOG_LINES).joinToString("\n"))
            if (lines.size > PURGE_LOG_LINES) append("\n…（共 ${lines.size} 行）")
        }
        val message = "$label exit=$exitCode" + if (rendered.isEmpty()) "" else ":\n$rendered"
        if (notable) Log.w(TAG, message) else Log.i(TAG, message)
    }

    /**
     * 历次"改名让路"留下的残骸目录（`dsh-runtime.broken-<时间戳>`）。
     *
     * 这些目录里的挂载点跟着改名走，前缀扫描再也扫不到它们 —— 没有回收机制的话，它们既不会
     * 被删也不会被重试，只能靠人手动收拾（而且可能是一棵完整的 278MB 树）。
     */
    internal fun staleRuntimeDirs(filesDir: File, limit: Int = Int.MAX_VALUE): List<File> =
        filesDir.listFiles()
            ?.filter { file ->
                file.isDirectory && STALE_PREFIXES.any { file.name.startsWith(it) }
            }
            ?.sortedBy { it.name }
            ?.take(limit)
            ?: emptyList()

    /**
     * 上次 sweep 的记录：残骸名单 + 时间。
     *
     * 名单变了（出现新残骸）就立刻再试，否则同一批一天只试一次。
     */
    internal data class SweepStamp(val names: List<String>, val attemptedAtMs: Long)

    internal fun parseSweepStamp(raw: String?): SweepStamp? {
        val lines = raw?.lines()?.filter { it.isNotBlank() } ?: return null
        val time = lines.lastOrNull()?.toLongOrNull() ?: return null
        return SweepStamp(names = lines.dropLast(1), attemptedAtMs = time)
    }

    internal fun shouldSkipSweep(
        stamp: SweepStamp?,
        nowMs: Long,
        names: List<String>,
        windowMs: Long = SWEEP_RETRY_WINDOW_MS,
    ): Boolean =
        stamp != null && stamp.names == names && nowMs - stamp.attemptedAtMs in 0 until windowMs

    /** 把删不掉的目录改名让路（挂载点跟着改名走）；改名失败返回 null。 */
    private fun retireAside(directory: File): File? {
        val aside = File(directory.parentFile, "${directory.name}.broken-${System.currentTimeMillis()}")
        if (!directory.renameTo(aside)) return null
        return aside
    }

    /**
     * 尽力回收残骸；这是**收敛**的：能删就删，删不掉就留着下次再试。
     *
     * 护栏和主路径完全一致：**清理没成功就绝不递归删**。`deleteRecursively()` 是
     * walkBottomUp() 逐个 delete()，会走进挂载点 —— 残骸里的技能库挂载源就是 App 自己的
     * `files/skills`，App 进程删得动，越过挂载点就会把它删掉。
     *
     * 每次最多清 [STALE_SWEEP_LIMIT] 个（按名字取最早的两个）：每个都是一次 su，最坏 20s，
     * 别让 App 启动路径堆起来。删不掉的（root 属主文件）下次再试。
     */
    private fun sweepStaleRuntimes(filesDir: File?) {
        val directory = filesDir ?: return
        val victims = staleRuntimeDirs(directory, STALE_SWEEP_LIMIT)
        if (victims.isEmpty()) return
        // 轻退避：同一批残骸一天只试一次（名字变了就立刻再试）。
        // 没有 su 的设备、或残骸里有删不掉的 root 属主文件时，否则每次开 App 都白跑两次 su。
        val stampFile = File(directory, SWEEP_STAMP_NAME)
        val stamp = parseSweepStamp(runCatching { stampFile.readText() }.getOrNull())
        val names = victims.map { it.name }
        if (shouldSkipSweep(stamp, System.currentTimeMillis(), names)) return
        runCatching {
            stampFile.writeText(names.joinToString("\n") + "\n" + System.currentTimeMillis() + "\n")
        }
        for (victim in victims) {
            if (purgeLeftovers(victim) != 0) {
                Log.i(TAG, "残骸暂不清（清理未成功），留到下次：${victim.absolutePath}")
                continue
            }
            if (victim.deleteRecursively()) {
                Log.i(TAG, "已回收运行时残骸：${victim.absolutePath}")
            } else {
                Log.i(TAG, "残骸删不干净（多半是 root 属主文件），留到下次：${victim.absolutePath}")
            }
        }
    }

    private fun extract(tar: TarArchiveInputStream, root: File) {
        val rootPath = root.canonicalPath
        while (true) {
            val entry = tar.nextEntry ?: break
            val name = entry.name.removePrefix("./")
            if (name.isEmpty() || name == ".") continue
            val target = File(root, name)
            if (!target.canonicalPath.startsWith(rootPath)) continue
            when {
                entry.isDirectory -> target.mkdirs()
                entry.isSymbolicLink -> {
                    target.parentFile?.mkdirs()
                    runCatching {
                        if (target.exists()) target.delete()
                        android.system.Os.symlink(entry.linkName, target.absolutePath)
                    }
                }
                entry.isFile -> {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output -> tar.copyTo(output) }
                    if (entry.mode and 0b001_000_000 != 0) target.setExecutable(true, false)
                }
            }
        }
    }
}

/**
 * 清空运行时目录用的 root 脚本。
 *
 * 这个目录同时是挂载宿主：启动脚本往里挂 `/dev`、`/proc` 和技能库，dsh 自己的终端工具链
 * 也会挂别的东西。**挂载点还在时 `rm -rf` 不会停下**——它会先穿过挂载点把里面的东西删掉
 *（宿主 `/dev` 的设备节点、用户的整个技能库），然后才因为删不掉挂载点本身报错。
 *
 * 所以脚本严格按这个顺序：规范化目标路径 → 收集并摘掉下面的挂载 → **确认没剩下挂载才敢删**
 * → 删 → 再验一次删干净。
 *
 * 两个坑，都是实测踩出来的：
 *
 * 1. **路径必须规范化**。`context.filesDir` 是 `/data/user/0/<包名>/files`，而 `/data/user/0`
 *    是指向 `/data/data` 的符号链接；内核记录的挂载点是**解析后**的 `/data/data/...`。
 *    拿未解析的路径比前缀，`dev`（宿主 `/dev` 的 rbind）、`proc`、技能库这三个挂载一个都
 *    匹配不到，于是它们全被 `rm -rf` 穿过——本机按同构场景（符号链接前缀 + bind）复现过：
 *    扫描 0 命中、`rm` 报 `Device or resource busy`，而**源目录内容已经被删了**。
 *    这里对两侧都做 `readlink -f`，任何别名（`/data/user/0`、`/data_mirror/...`）都能对上。
 * 2. **校验必须放在 `rm -rf` 之前**。删完再报 `HETA_PURGE_INCOMPLETE` 是"先损坏再报错"；
 *    摘不干净就直接 `HETA_PURGE_ABORT` 退出，一个字都不删。
 *
 * 匹配按路径边界（`c` 本身或 `c/` 之下），不是简单前缀，免得顺手动到
 * `…/dsh-runtime.installing`、`…/dsh-runtime-old` 这些兄弟目录。摘挂载按路径层数从深到浅
 *（`awk -F/` 数段数），嵌套时从最里层往外摘。最后有一道兜底：目标路径太浅（误传 `/`、
 * `/data`）就什么都不做。路径按 shell 规矩单引号转义；mountinfo 里的空格会写成 `\040`，
 * App 私有目录下不会出现，所以不做反转义。
 */
internal fun runtimePurgeScript(target: String): String {
    val quoted = "'" + target.replace("'", "'\\''") + "'"
    return buildString {
        append("t=").append(quoted).append("; ")
        // 规范化失败就什么都不做：宁可"未就绪"，也不能凭一个对不上的路径去删。
        append("c=$(readlink -f \"${'$'}t\" 2>/dev/null) || exit 1; ")
        // 兜底：目标至少要有四层（/data/data/<包名>/files/<目录>），否则拒绝执行。
        append("case \"${'$'}c\" in /*/*/*/*) ;; *) echo \"HETA_PURGE_ABORT: path too shallow: ${'$'}c\"; exit 1;; esac; ")
        // 扫 mountinfo，分两类：
        //   LIVE   —— 路径能解析、且规范化后在目标之下：要么被摘掉，要么阻塞删除
        //   ORPHAN —— 路径已经解析不了（目标目录被删掉、只剩挂载表条目）：rm -rf 根本走不到它，
        //             umount 也永远找不到路径。**它不能算阻塞** —— 设备上就撞到过：38 个这种
        //             挂载把安装流程永久卡在"未就绪"。
        append("scan() { awk '{print ${'$'}5}' /proc/self/mountinfo 2>/dev/null | while read -r m; do ")
        append("cm=$(readlink -f \"${'$'}m\" 2>/dev/null); ")
        append("if [ -z \"${'$'}cm\" ]; then echo \"ORPHAN ${'$'}m\"; continue; fi; ")
        append("case \"${'$'}cm\" in \"${'$'}c\"|\"${'$'}c\"/*) echo \"LIVE ${'$'}m\";; esac; done; }; ")
        // 从最深往外摘（按 / 的段数，不是字典序）；umount 失败要把原因带出来，别静默。
        append("live() { scan | grep '^LIVE' | cut -d' ' -f2- ")
        append("| awk -F/ '{print NF, ${'$'}0}' | sort -rn | cut -d' ' -f2-; }; ")
        append("for m in ${'$'}(live); do ")
        append("if err=$(umount -l \"${'$'}m\" 2>&1); then :; ")
        append("elif err=$(/system/bin/umount -l \"${'$'}m\" 2>&1); then :; ")
        append("else echo \"HETA_UMOUNT_FAILED: ${'$'}m: ${'$'}err\"; fi; done; ")
        append("orphans=$(scan | grep -c '^ORPHAN'); ")
        append("if [ \"${'$'}orphans\" -gt 0 ]; then ")
        append("echo \"HETA_PURGE_ORPHAN: ${'$'}orphans mount(s) whose target is gone (not blocking; reboot clears them)\"; fi; ")
        // 删之前再确认一次：LIVE 的没摘干净就一个字都不删。
        append("left=$(live | wc -l); ")
        append("if [ \"${'$'}left\" -gt 0 ]; then echo \"HETA_PURGE_ABORT: ${'$'}left mount(s) still under ${'$'}c\"; ")
        append("live | head -n 8; exit 1; fi; ")
        append("rm -rf \"${'$'}t\" 2>&1; ")
        append("if [ -e \"${'$'}t\" ]; then echo \"HETA_PURGE_INCOMPLETE:\"; ")
        append("ls -la \"${'$'}t\" 2>&1 | head -n 8; exit 1; fi")
    }
}
