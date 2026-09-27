package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream

/**
 * 把随 APK 一起分发的 DeepSeek Harness 运行时展开到应用私有目录。
 *
 * 运行时是一个最小 Linux 用户态：只含 Node 依赖的 glibc 集合、Node 二进制和 dsh
 * 本体，压缩后约 45MB，展开后约 226MB。首次启动时展开一次，之后由标记文件跳过。
 *
 * 这样用户安装 APK 后不需要自己安装 Linux 发行版、Node 或 npm 包。
 */
internal object DshRuntimeInstaller {
    private const val TAG = "DshRuntimeInstaller"
    private const val ASSET_NAME = "dsh-runtime.tar.xz"
    private const val ROOT_DIR_NAME = "dsh-runtime"
    private const val READY_MARKER = ".runtime-ready"
    private const val REVISION = 1

    /** chroot 之后 dsh 的入口，供启动命令使用。 */
    const val NODE_IN_ROOT = "/opt/node/bin/node"
    const val DSH_ENTRY_IN_ROOT = "/opt/dsh/lib/bin.js"
    const val WORKSPACE_IN_ROOT = "/workspace"
    const val HOME_IN_ROOT = "/root"

    fun runtimeDirectory(context: Context): File = File(context.filesDir, ROOT_DIR_NAME)

    fun isReady(context: Context): Boolean {
        val root = runtimeDirectory(context)
        return File(root, READY_MARKER).exists() &&
            File(root, "opt/node/bin/node").exists() &&
            File(root, "opt/dsh/lib/bin.js").exists()
    }

    /**
     * 幂等安装；失败时清理半成品，避免留下一个"看起来已就绪"的目录。
     */
    @Synchronized
    fun ensureInstalled(context: Context): Boolean {
        if (isReady(context)) return true
        val root = runtimeDirectory(context)
        return runCatching {
            if (root.exists()) root.deleteRecursively()
            if (!root.mkdirs() && !root.exists()) error("无法创建运行时目录")
            context.assets.open(ASSET_NAME).use { raw ->
                XZInputStream(BufferedInputStream(raw)).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        extract(tar, root)
                    }
                }
            }
            File(root, READY_MARKER).writeText("revision=$REVISION\n")
            Log.i(TAG, "runtime installed at ${root.absolutePath}")
            true
        }.getOrElse { throwable ->
            Log.w(TAG, "runtime install failed", throwable)
            runCatching { root.deleteRecursively() }
            false
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
