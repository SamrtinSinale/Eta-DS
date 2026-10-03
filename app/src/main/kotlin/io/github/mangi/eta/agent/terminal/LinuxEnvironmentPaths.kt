package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File

/** 两个 Linux rootfs 共用的磁盘布局和就绪判定。 */
internal object LinuxEnvironmentPaths {
    const val READY_MARKER = ".eta-environment-ready"

    fun environmentDir(context: Context, distribution: LinuxDistribution): File =
        environmentDir(context, distribution, LinuxEnvironmentSettingsRepository.backend(context, distribution))

    fun environmentDir(context: Context, distribution: LinuxDistribution, backend: LinuxExecutionBackend): File =
        if (backend == LinuxExecutionBackend.CHROOT) {
            File(context.filesDir, "terminal/${distribution.wireName}")
        } else {
            TerminalPrivateStorage.prootEnvironment(context.filesDir, distribution)
        }

    fun rootfsDir(context: Context, distribution: LinuxDistribution): File =
        File(environmentDir(context, distribution), "rootfs")

    fun rootfsDir(context: Context, distribution: LinuxDistribution, backend: LinuxExecutionBackend): File =
        File(environmentDir(context, distribution, backend), "rootfs")

    fun legacyRootfsDir(context: Context, distribution: LinuxDistribution): File =
        rootfsDir(context, distribution, LinuxExecutionBackend.CHROOT)

    fun backendOf(rootfsPath: String?): LinuxExecutionBackend =
        if (TerminalPrivateStorage.isProotPath(rootfsPath)) LinuxExecutionBackend.PROOT else LinuxExecutionBackend.CHROOT

    /**
     * 基础环境是否就绪。
     *
     * [expectedBaseVersion] 非空时**额外比对就绪标记里的 `version=`**。只有安装/修复路径才该传它：
     * 产物版本换了（App 升级）而环境还是旧的，就该被当成"需要重装"，否则旧环境永远算就绪、
     * 用户也永远拿不到重装提示。
     *
     * **使用路径**（终端、文件浏览器、守护任务）必须保持宽松 —— 判定过严会把"版本旧但能用"的
     * 环境判死，3.0.6.22 那次就是判定过严反过来把可用状态弄坏。
     */
    fun rootfsReady(rootfsPath: String?, expectedBaseVersion: String? = null): Boolean {
        if (rootfsPath.isNullOrBlank()) return false
        val rootfs = File(rootfsPath)
        val marker = File(rootfs, READY_MARKER)
        if (!marker.isFile) return false
        // 标记文件会残留：rootfs 被清空或搬走后它还在，于是空壳环境仍被当成「已就绪」，
        // 既装不上东西、又不给重装入口。再验一个 rootfs 必备的可执行文件，空壳直接判为未就绪。
        if (!File(rootfs, "usr/bin/env").isFile) return false
        if (expectedBaseVersion != null && readMarkerVersion(marker) != expectedBaseVersion) return false
        return true
    }

    /** 读就绪标记里的 `version=`；缺失或非数字返回 null（与安装器写标记的格式一致）。 */
    fun readMarkerVersion(marker: File): String? = runCatching {
        marker.readLines()
            .firstOrNull { it.startsWith("version=") }
            ?.substringAfter('=')?.trim()
            ?.takeIf { it.matches(Regex("[0-9]+")) }
    }.getOrNull()

    /**
     * 工具集标记的**内容**校验（不是"文件在不在"）。
     *
     * 两个发行版的工具集 revision 各自维护；只查 `isFile` 会让"revision 升了、标记还是旧的"这种
     * 环境通过前置检查，于是安装器对着一个 UI 报「未就绪」的环境去跑 `eta-apt install`。
     */
    fun commonToolsReady(rootfsPath: String?, distribution: LinuxDistribution): Boolean {
        if (rootfsPath.isNullOrBlank()) return false
        return when (distribution) {
            LinuxDistribution.DEBIAN -> DebianEnvironmentInstaller.commonToolsReady(File(rootfsPath))
            LinuxDistribution.ALPINE -> AlpineEnvironmentPaths.commonToolsReady(rootfsPath)
        }
    }
}
