package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.util.Log
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeSession
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.core.safeLogType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 用 DeepSeek Harness 作为执行内核：一次 run 对应一条 ACP 会话轮次。
 *
 * 与 AgentRuntimeRunExecutor 的分工：
 * - 这里只负责驱动 ACP，并把会话更新翻译成 [AgentEvent] 交给既有 UI 管线；
 * - 工具执行不在这里发生 —— dsh 通过 MCP 调用 Eta 暴露的本地工具端点，
 *   权限检查仍在 AgentLocalTools.execute 内部完成；
 * - 模型、地址与凭据直接取 run 请求里已冻结的配置，用户在 Eda 配置一次即可。
 */
internal class DshAcpRuntime(
    private val config: DshRuntimeConfig,
) {
    fun execute(session: AgentRuntimeSession, request: AgentRuntimeWire.RunRequest): Boolean {
        val text = request.prompt?.trim().orEmpty()
        if (text.isEmpty()) {
            finishWithFailure(session, "消息为空")
            return false
        }
        val runId = session.runId
        var round = 0
        var messageBlockIndex = 0
        var messageBlockOpen = false
        var contentChars = 0
        var sawFailure = false
        val client = DshAcpClient(
            command = config.command(),
            workingDirectory = config.processDirectory,
            extraEnvironment = config.environment(),
            listener = object : DshAcpClient.Listener {
                override fun onSessionUpdate(sessionId: String, update: JSONObject) {
                    when (update.optString("sessionUpdate")) {
                        UPDATE_MESSAGE_CHUNK -> {
                            val delta = update.optJSONObject("content")?.optString("text").orEmpty()
                            if (delta.isEmpty()) return
                            if (!messageBlockOpen) {
                                session.emit(
                                    AgentEvent.AssistantBlockStart(
                                        round = round,
                                        kind = AgentEvent.AssistantBlockKind.TEXT,
                                        index = messageBlockIndex,
                                        blockId = update.optString("messageId").ifBlank { null },
                                    )
                                )
                                messageBlockOpen = true
                            }
                            contentChars += delta.length
                            session.emit(
                                AgentEvent.AssistantBlockDelta(
                                    round = round,
                                    kind = AgentEvent.AssistantBlockKind.TEXT,
                                    index = messageBlockIndex,
                                    deltaChars = delta.length,
                                    delta = delta,
                                )
                            )
                        }

                        UPDATE_THOUGHT_CHUNK -> {
                            val delta = update.optJSONObject("content")?.optString("text").orEmpty()
                            if (delta.isEmpty()) return
                            contentChars += delta.length
                            session.emit(
                                AgentEvent.AssistantBlockDelta(
                                    round = round,
                                    kind = AgentEvent.AssistantBlockKind.THINKING,
                                    index = messageBlockIndex,
                                    deltaChars = delta.length,
                                    delta = delta,
                                )
                            )
                        }

                        UPDATE_TOOL_CALL -> {
                            val toolCallId = update.optString("toolCallId").ifBlank { update.optString("id") }
                            session.emit(
                                AgentEvent.ToolStarted(
                                    round = round,
                                    toolCallId = toolCallId,
                                    name = update.optString("title").ifBlank { update.optString("kind", "tool") },
                                    argsPreview = update.optString("rawInput").take(400),
                                )
                            )
                        }

                        UPDATE_TOOL_CALL_UPDATE -> {
                            val status = update.optString("status")
                            if (status == "completed" || status == "failed") {
                                session.emit(
                                    AgentEvent.ToolFinished(
                                        round = round,
                                        toolCallId = update.optString("toolCallId").ifBlank { update.optString("id") },
                                        name = update.optString("title").ifBlank { "tool" },
                                        resultSummary = update.optString("rawOutput").take(600),
                                        imageCount = 0,
                                        imageBytes = 0,
                                        success = status == "completed",
                                    )
                                )
                            }
                        }

                        UPDATE_USAGE -> {
                            val used = update.optInt("used", -1)
                            val size = update.optInt("size", -1)
                            session.emit(
                                AgentEvent.UsageReceived(
                                    round = round,
                                    usage = AgentTokenUsage(
                                        contextTokens = if (used >= 0) used else null,
                                        inputTokens = if (size >= 0) size else null,
                                    ),
                                )
                            )
                        }

                        else -> Log.i(TAG, "unhandled update: ${update.optString("sessionUpdate")}")
                    }
                }

                override fun onClosed(reason: String) {
                    Log.i(TAG, "dsh acp closed: $reason")
                }
            },
        )
        Log.i(TAG, "acp launch: exec chroot " + config.command().last().substringAfter("exec chroot "))
        return try {
            client.start()
            Log.i(TAG, "acp process spawned")
            runBlocking {
                client.initialize()
                val sessionId = client.newSession(
                    cwd = config.workingDirectory,
                    mcpServers = config.mcpServers(),
                )
                if (config.providerRoute.isNotBlank() && config.model.isNotBlank()) {
                    runCatching { client.setModel(sessionId, config.providerRoute, config.model) }
                        .onFailure { Log.w(TAG, "set model failed: ${it.safeLogType()}") }
                }
                round = 1
                session.emit(
                    AgentEvent.RunStarted(
                        initialImages = 0,
                        initialImageBytes = 0,
                        toolCount = 0,
                        terminalTools = true,
                    )
                )
                session.emit(AgentEvent.RoundStarted(round = round, messageCount = 1))
                client.prompt(sessionId, text)
            }
            if (messageBlockOpen) {
                session.emit(
                    AgentEvent.AssistantBlockEnd(
                        round = round,
                        kind = AgentEvent.AssistantBlockKind.TEXT,
                        index = messageBlockIndex,
                        contentChars = contentChars,
                    )
                )
            }
            session.emit(AgentEvent.RunFinished(round = round, contentChars = contentChars))
            session.complete(
                AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = true,
                    content = "",
                    reasoningContent = "",
                    operation = request.operation,
                )
            ) {}
            true
        } catch (throwable: Throwable) {
            Log.w(TAG, "dsh run failed", throwable)
            sawFailure = true
            finishWithFailure(session, throwable.message ?: throwable.javaClass.simpleName)
            false
        } finally {
            if (!sawFailure) runCatching { client.close() } else runCatching { client.close() }
        }
    }

    private fun finishWithFailure(session: AgentRuntimeSession, reason: String) {
        session.emit(AgentEvent.RunFailed(reason = reason))
        session.complete(
            AgentRuntimeWire.RunResult(
                runId = session.runId,
                ok = false,
                content = "",
                error = reason,
            )
        ) {}
    }

    companion object {
        private const val TAG = "DshAcpRuntime"
        private const val UPDATE_MESSAGE_CHUNK = "agent_message_chunk"
        private const val UPDATE_THOUGHT_CHUNK = "agent_thought_chunk"
        private const val UPDATE_TOOL_CALL = "tool_call"
        private const val UPDATE_TOOL_CALL_UPDATE = "tool_call_update"
        private const val UPDATE_USAGE = "usage_update"
        private const val DSH_ENTRY_RELATIVE = "usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
        private const val OFFICIAL_ROUTE = "deepseek-official"

        /**
         * Linux 环境里已经装好 dsh 时才启用 ACP 内核；否则返回 null 让上游回退到原有 Agent Loop。
         * 这样没装环境、或环境损坏的设备不会因此失去原有能力。
         */
        fun create(context: Context, request: AgentRuntimeWire.RunRequest): DshAcpRuntime? {
            val modelConfig = request.config
            val ready = runCatching { DshRuntimeInstaller.isReady(context) }.getOrDefault(false)
            Log.i(
                TAG,
                "probe: key=${modelConfig.apiKey.isNotBlank()} base=${modelConfig.baseUrl.isNotBlank()} " +
                    "model=${modelConfig.model} provider=${modelConfig.providerId} runtimeReady=$ready",
            )
            if (modelConfig.apiKey.isBlank() || modelConfig.baseUrl.isBlank() || modelConfig.model.isBlank()) {
                Log.i(TAG, "probe: model config incomplete, falling back")
                return null
            }
            val resolved = DshRuntimeConfig.resolveBuiltin(
                context = context,
                providerRoute = OFFICIAL_ROUTE,
                model = modelConfig.model,
                apiKey = modelConfig.apiKey,
                baseUrl = modelConfig.baseUrl,
            ) ?: return null
            return DshAcpRuntime(resolved)
        }

        private fun readyDistribution(context: Context): LinuxDistribution? =
            LinuxDistribution.entries.firstOrNull { distribution ->
                runCatching {
                    val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
                    LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath) &&
                        File(rootfs, DSH_ENTRY_RELATIVE).exists()
                }.getOrDefault(false)
            }
    }
}
