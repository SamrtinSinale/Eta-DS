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
    fun ensureInstalled(context: Context): Boolean {
        if (isReady(context)) return true
        val root = runtimeDirectory(context)
        val staging = File(root.parentFile, STAGING_DIR_NAME)
        return runCatching {
            purgeLeftovers(staging)
            if (staging.exists() && !staging.deleteRecursively()) {
                error("解包目录删不掉：${staging.absolutePath}")
            }
            purgeLeftovers(root)
            if (root.exists() && !root.deleteRecursively()) {
                error("旧运行时目录删不掉（多半还有挂载）：${root.absolutePath}")
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
            runCatching { staging.deleteRecursively() }
            false
        }
    }

    /**
     * 强制重装：把已就绪的运行时也清掉重解包。
     *
     * 给设置里的"重装对话运行时"入口用，同时也是**验证清理路径**（先摘挂载 → 解包到 staging
     * → 就位）的唯一通道 —— 正常升级只在 [REVISION] 变化时才走到那里。
     *
     * 代价：DSH_HOME 在运行时目录里，dsh 侧的历史会一起没（App 侧映射表还在，下一轮会回落到
     * "新会话 + 历史摘要"）。所以要在对话空闲时用。
     */
    @Synchronized
    fun reinstall(context: Context): Boolean {
        runCatching { File(runtimeDirectory(context), READY_MARKER).delete() }
        return ensureInstalled(context)
    }

    /**
     * 借 su 清掉旧运行时里的残留：先摘挂载，再删 root 文件。
     *
     * dsh 是通过 su 以 root 身份跑的，它在运行时目录里留下的会话文件（/workspace、日志、
     * node 缓存）owner 是 root，App 进程既删不掉子文件也进不去这些目录。
     *
     * 以前这里只是一句 `su -c rm -rf`，三个问题：不 umount（挂载点还在时删不掉，见
     * [runtimePurgeScript]）、不看退出码、`waitFor()` 没有超时（su 卡在授权弹窗上就会把
     * 调用方线程永久挂住，界面永远"未就绪"且没有任何报错）。
     */
    private fun purgeLeftovers(root: File) {
        if (!root.exists()) return
        runCatching {
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
                    Log.w(TAG, "purge leftovers 超时（${PURGE_TIMEOUT_MS}ms）：su 可能卡在授权，目录保持原样")
                    return
                }
                val output = runCatching { log.readText() }.getOrDefault("").trim()
                if (started.exitValue() != 0 || output.isNotEmpty()) {
                    Log.w(TAG, "purge leftovers exit=${started.exitValue()} output=${output.take(600)}")
                }
            } finally {
                runCatching { log.delete() }
            }
        }.onFailure { Log.w(TAG, "purge leftovers failed", it) }
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
        // 收集「规范化后在目标之下」的挂载点；mountinfo 里的路径也先规范化再比。
        append("scan() { awk '{print ${'$'}5}' /proc/self/mountinfo 2>/dev/null | while read -r m; do ")
        append("cm=$(readlink -f \"${'$'}m\" 2>/dev/null || echo \"${'$'}m\"); ")
        append("case \"${'$'}cm\" in \"${'$'}c\"|\"${'$'}c\"/*) echo \"${'$'}m\";; esac; done; }; ")
        // 从最深往外摘（按 / 的段数，不是字典序）。
        append("scan | awk -F/ '{print NF, ${'$'}0}' | sort -rn | cut -d' ' -f2- ")
        append("| while read -r m; do umount -l \"${'$'}m\" 2>/dev/null; done; ")
        // 删之前再扫一遍：还有挂载就一个字都不删。
        append("left=$(scan | wc -l); ")
        append("if [ \"${'$'}left\" -gt 0 ]; then echo \"HETA_PURGE_ABORT: ${'$'}left mount(s) still under ${'$'}c\"; ")
        append("scan | head -n 8; exit 1; fi; ")
        append("rm -rf \"${'$'}t\" 2>&1; ")
        append("if [ -e \"${'$'}t\" ]; then echo \"HETA_PURGE_INCOMPLETE:\"; ")
        append("ls -la \"${'$'}t\" 2>&1 | head -n 8; exit 1; fi")
    }
}
