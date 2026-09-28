package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.util.Log
import io.github.mangi.eta.agent.mcp.AgentToolServerHost
import java.io.File
import org.json.JSONObject

/**
 * DeepSeek Harness 的运行参数。
 *
 * 运行时来自随 APK 分发的内置包（见 [DshRuntimeInstaller]），不依赖用户自己安装
 * 任何 Linux 发行版或 npm 包。
 *
 * 三个必须注意的点：
 * 1. chroot 需要 root，App 进程本身没有权限，因此命令必须经 `su -c` 包装；
 * 2. 凭据通过 `export` 传进这条 shell 命令，而不是命令行参数，避免出现在进程列表里；
 * 3. ACP profile 把模型写死成内置默认值，自定义网关的模型名要靠 `--patch` 覆盖层注入。
 */
internal data class DshRuntimeConfig(
    val rootfsPath: String,
    val providerRoute: String,
    val model: String,
    val apiKey: String,
    val baseUrl: String,
    val workingDirectory: String = DshRuntimeInstaller.WORKSPACE_IN_ROOT,
) {
    /**
     * ACP 进程自身的宿主工作目录。
     *
     * [workingDirectory] 是 ACP 会话内部的 cwd（"\/workspace"，只在 chroot 里存在）；
     * ProcessBuilder 用的是宿主路径，指向不存在的目录会直接 ENOENT。
     */
    val processDirectory: String = File(rootfsPath, "workspace").absolutePath

    /** su 是 Eta 既有提权路径；脚本里的 export 保证凭据不落进 argv。 */
    fun command(): List<String> = listOf(SU, "-c", rootScript())

    fun environment(): Map<String, String> = buildMap {
        put("HOME", DshRuntimeInstaller.HOME_IN_ROOT)
        put("PATH", PATH_IN_ROOT)
        put("LANG", "C.UTF-8")
    }

    /**
     * 把模型选择写成 profile 覆盖层，返回它在 chroot 内的路径。
     *
     * ACP profile 的默认模型取自内置目录，而自定义网关通常只放行自己的模型名
     * （例如 `cn:deepseek-v4.1-flash`），不覆盖就会在服务端被判白名单拒绝。
     */
    private fun writeProfileOverlay(): String? {
        if (model.isBlank()) return null
        return runCatching {
            val overlay = File(rootfsPath, OVERLAY_RELATIVE)
            overlay.parentFile?.mkdirs()
            overlay.writeText(
                buildString {
                    append("- id: acp\n")
                    append("  config:\n")
                    append("    provider: ")
                        .append(JSONObject.quote(providerRoute.ifBlank { DEFAULT_ROUTE }))
                        .append('\n')
                    append("    model: ").append(JSONObject.quote(model)).append('\n')
                }
            )
            OVERLAY_IN_ROOT
        }.getOrElse { throwable ->
            Log.w(TAG, "profile overlay write failed", throwable)
            null
        }
    }

    private fun rootScript(): String {
        val overlay = writeProfileOverlay()
        return buildString {
            append("export HOME=").append(DshRuntimeInstaller.HOME_IN_ROOT)
            append(" PATH=").append(PATH_IN_ROOT)
            append(" LANG=C.UTF-8")
            if (apiKey.isNotBlank()) append(" DEEPSEEK_API_KEY=").append(shellQuote(apiKey))
            if (baseUrl.isNotBlank()) append(" DEEPSEEK_BASE_URL=").append(shellQuote(baseUrl))
            append("; exec chroot ").append(shellQuote(rootfsPath))
            append(' ').append(DshRuntimeInstaller.NODE_IN_ROOT)
            append(' ').append(DshRuntimeInstaller.DSH_ENTRY_IN_ROOT)
            append(" --profile ").append(ACP_PROFILE)
            if (overlay != null) append(" --patch ").append(shellQuote(overlay))
        }
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
        private const val SU = "su"
        private const val ACP_PROFILE = "acp"
        private const val DEFAULT_ROUTE = "deepseek-official"
        private const val TAG = "DshRuntimeConfig"
        /** 前段是宿主的 Android 路径（su、chroot），后段是 chroot 内的路径（node）。 */
        private const val PATH_IN_ROOT =
            "/system/bin:/system/xbin:/product/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        private const val ENV_API_KEY = "DEEPSEEK_API_KEY"
        private const val ENV_BASE_URL = "DEEPSEEK_BASE_URL"
        private const val MCP_SERVER_NAME = "eta"
        private const val OVERLAY_RELATIVE = "opt/dsh/eta-run-overlay.patch.yml"
        private const val OVERLAY_IN_ROOT = "/opt/dsh/eta-run-overlay.patch.yml"

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

        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }
}
