package io.github.mangi.eta.agent.mcp

import android.content.Context
import android.util.Base64
import android.util.Log
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONArray
import java.io.File
import java.security.SecureRandom

/**
 * 持有对外暴露本地工具的 MCP server。
 *
 * 与 Eta 自有的 Agent Loop 并行存在：App 内对话继续走原链路，这里额外把同一份工具目录
 * 开放给外部 agent（DeepSeek Harness）。
 *
 * 权限模型不变：工具清单按设备条件投影，Root / 无障碍 / 敏感开关仍由
 * AgentLocalTools.execute 在执行前逐次检查，这里不做任何放行。
 */
internal object AgentToolServerHost {
    private const val TAG = "AgentToolServerHost"
    private const val SERVER_NAME = "eta-mobile"
    private const val TOKEN_FILE_NAME = "tool-server.token"
    private const val TOKEN_BYTES = 32
    private const val BROWSER_RUN_ID = "mcp-tool-server"

    @Volatile
    private var server: AgentToolServer? = null

    @Volatile
    private var localTools: AgentLocalTools? = null

    @Volatile
    private var cachedToken: String? = null

    val endpoint: String? get() = server?.endpoint

    val authToken: String? get() = cachedToken

    val running: Boolean get() = server != null

    @Synchronized
    fun ensureStarted(context: Context): AgentToolServer {
        server?.let { return it }
        val appContext = context.applicationContext
        val token = loadOrCreateToken(appContext)
        val tools = AgentLocalTools(
            context = appContext,
            logger = AndroidAgentLogger,
            browserRunId = BROWSER_RUN_ID,
        )
        val schemaProvider: () -> JSONArray = {
            AgentToolCatalog.build(
                terminalTools = true,
                browserTools = true,
                deviceDirectTools = true,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
                memoryTools = true,
                memoryWritable = true,
                capabilities = AgentToolCapabilities(rootAvailable = true),
            )
        }
        val instance = AgentToolServer(
            serverName = SERVER_NAME,
            authToken = token,
            toolSchemaProvider = schemaProvider,
            toolExecutor = tools::execute,
        )
        instance.start()
        server = instance
        localTools = tools
        cachedToken = token
        Log.i(TAG, "tool server ready at ${instance.endpoint}")
        return instance
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        runCatching { localTools?.close() }
        localTools = null
    }

    private fun loadOrCreateToken(context: Context): String {
        val file = File(context.filesDir, TOKEN_FILE_NAME)
        val existing = runCatching { file.readText().trim() }.getOrNull()
        if (!existing.isNullOrBlank()) return existing
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        runCatching { file.writeText(token) }
        return token
    }
}
