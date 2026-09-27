package io.github.mangi.eta.agent.dsh

import android.content.Context
import io.github.mangi.eta.agent.mcp.AgentToolServerHost
import org.json.JSONObject

/**
 * DeepSeek Harness 的运行参数。
 *
 * 运行时来自随 APK 分发的内置包（见 [DshRuntimeInstaller]），不依赖用户自己安装
 * 任何 Linux 发行版或 npm 包。
 *
 * 凭据通过环境变量传入而不是命令行参数，避免出现在进程列表里。
 */
internal data class DshRuntimeConfig(
    val rootfsPath: String,
    val providerRoute: String,
    val model: String,
    val apiKey: String,
    val baseUrl: String,
    val workingDirectory: String = DshRuntimeInstaller.WORKSPACE_IN_ROOT,
) {
    /** 在运行时根目录内直接执行，不经过登录 shell，避免额外进程与引号问题。 */
    fun command(): List<String> = listOf(
        CHROOT,
        rootfsPath,
        DshRuntimeInstaller.NODE_IN_ROOT,
        DshRuntimeInstaller.DSH_ENTRY_IN_ROOT,
        "--profile",
        ACP_PROFILE,
    )

    fun environment(): Map<String, String> = buildMap {
        if (apiKey.isNotBlank()) put(ENV_API_KEY, apiKey)
        if (baseUrl.isNotBlank()) put(ENV_BASE_URL, baseUrl)
        put("HOME", DshRuntimeInstaller.HOME_IN_ROOT)
        put("PATH", PATH_IN_ROOT)
        put("LANG", "C.UTF-8")
    }

    /**
     * 把 Eta 自己的 MCP 端点声明给 dsh。
     * 端点与 App 内 Agent Loop 共用同一份工具目录，权限检查仍留在 Eta 侧。
     */
    fun mcpServers(): List<JSONObject> {
        val endpoint = AgentToolServerHost.endpoint ?: return emptyList()
        val token = AgentToolServerHost.authToken ?: return emptyList()
        return listOf(
            JSONObject()
                .put("name", MCP_SERVER_NAME)
                .put("url", endpoint)
                .put("headers", JSONObject().put("Authorization", "Bearer $token")),
        )
    }

    companion object {
        private const val CHROOT = "chroot"
        private const val ACP_PROFILE = "acp"
        private const val PATH_IN_ROOT = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        private const val ENV_API_KEY = "DEEPSEEK_API_KEY"
        private const val ENV_BASE_URL = "DEEPSEEK_BASE_URL"
        private const val MCP_SERVER_NAME = "eta"

        /**
         * 内置运行时尚未展开时返回 null，让上游回退到原有 Agent Loop。
         * 这样首次启动还没展开完成的那几秒不会导致对话失败。
         */
        fun resolveBuiltin(
            context: Context,
            providerRoute: String,
            model: String,
            apiKey: String,
            baseUrl: String,
        ): DshRuntimeConfig? {
            if (!DshRuntimeInstaller.isReady(context)) return null
            return DshRuntimeConfig(
                rootfsPath = DshRuntimeInstaller.runtimeDirectory(context).absolutePath,
                providerRoute = providerRoute,
                model = model,
                apiKey = apiKey,
                baseUrl = baseUrl,
            )
        }
    }
}
