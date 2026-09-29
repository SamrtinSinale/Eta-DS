package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.mcp.AgentToolServerHost
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import java.io.File

/**
 * 把 Heta 的工具 server 端点写成 dsh 可加载的 patch 文件。
 *
 * dsh 启动时带上 --patch，插件 @deepseek-ai/dsh-mcp-client 会连接本机回环上的
 * MCP 端点，把手机工具以 mcp__heta__* 的形式挂进它的工具表。
 *
 * 文件写在 Linux rootfs 内部（而不是 /workspace），因为 App 私有目录下的 rootfs
 * 才是可直接写入的宿主路径。
 */
internal object DshMcpBridge {
    private const val DIR_NAME = "root/.heta"
    private const val FILE_NAME = "dsh-mcp.yml"

    /** 传给 dsh 的 --patch 参数，是 rootfs 内的绝对路径。 */
    const val PATCH_PATH = "/" + DIR_NAME + "/" + FILE_NAME

    fun prepare(context: Context, distribution: LinuxDistribution): Boolean {
        val server = runCatching { AgentToolServerHost.ensureStarted(context) }.getOrNull() ?: return false
        val token = AgentToolServerHost.authToken ?: return false
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
        val directory = File(rootfs, DIR_NAME)
        if (!directory.exists() && !directory.mkdirs()) return false
        val content = buildString {
            append("- insert:\n")
            append("    - id: heta-mobile-tools\n")
            append("      name: '@deepseek-ai/dsh-mcp-client'\n")
            append("      config:\n")
            append("        serverName: heta\n")
            append("        transport: streamable-http\n")
            append("        url: ").append(server.endpoint).append('\n')
            append("        headers:\n")
            append("          Authorization: 'Bearer ").append(token).append("'\n")
        }
        return runCatching {
            File(directory, FILE_NAME).writeText(content)
            true
        }.getOrDefault(false)
    }
}
