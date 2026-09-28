package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.util.Log
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeSession
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.core.safeLogType
import java.io.File
import kotlinx.coroutines.delay
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
        val promptText = promptWithHistory(request, text)
        Log.i(TAG, "prompt: history=${request.history.size} msgs, chars=${promptText.length}")
        val runId = session.runId
        var round = 0
        var contentChars = 0
        // ACP 是流式的：正文与思考要自己累积，收尾时随结果一起交回，否则 UI 只有增量没有终态。
        val assistantText = StringBuilder()
        val assistantThinking = StringBuilder()
        var sawFailure = false
        // dsh 的流是「思考块 / 正文块 / 工具调用」交替出现的，每个块必须占一个独立的 index：
        // 一直复用同一个 index 会把工具调用之后的正文合并回之前那条消息，顺序和内容都会乱。
        var blockIndex = -1
        var openKind: AgentEvent.AssistantBlockKind? = null
        var openBlockChars = 0
        var lastTextMessageId: String? = null

        fun closeOpenBlock() {
            val kind = openKind ?: return
            session.emit(
                AgentEvent.AssistantBlockEnd(
                    round = round,
                    kind = kind,
                    index = blockIndex,
                    contentChars = openBlockChars,
                )
            )
            openKind = null
            openBlockChars = 0
        }

        fun openBlock(kind: AgentEvent.AssistantBlockKind, blockId: String?) {
            blockIndex += 1
            openKind = kind
            openBlockChars = 0
            session.emit(
                AgentEvent.AssistantBlockStart(
                    round = round,
                    kind = kind,
                    index = blockIndex,
                    blockId = blockId,
                )
            )
        }
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
                            val messageId = update.optString("messageId").ifBlank { null }
                            if (openKind != AgentEvent.AssistantBlockKind.TEXT || messageId != lastTextMessageId) {
                                closeOpenBlock()
                                openBlock(AgentEvent.AssistantBlockKind.TEXT, messageId)
                                lastTextMessageId = messageId
                            }
                            contentChars += delta.length
                            openBlockChars += delta.length
                            assistantText.append(delta)
                            session.emit(
                                AgentEvent.AssistantBlockDelta(
                                    round = round,
                                    kind = AgentEvent.AssistantBlockKind.TEXT,
                                    index = blockIndex,
                                    deltaChars = delta.length,
                                    delta = delta,
                                )
                            )
                        }

                        UPDATE_THOUGHT_CHUNK -> {
                            val delta = update.optJSONObject("content")?.optString("text").orEmpty()
                            if (delta.isEmpty()) return
                            if (openKind != AgentEvent.AssistantBlockKind.THINKING) {
                                closeOpenBlock()
                                openBlock(AgentEvent.AssistantBlockKind.THINKING, null)
                            }
                            contentChars += delta.length
                            openBlockChars += delta.length
                            assistantThinking.append(delta)
                            session.emit(
                                AgentEvent.AssistantBlockDelta(
                                    round = round,
                                    kind = AgentEvent.AssistantBlockKind.THINKING,
                                    index = blockIndex,
                                    deltaChars = delta.length,
                                    delta = delta,
                                )
                            )
                        }

                        UPDATE_TOOL_CALL -> {
                            // 工具卡片插进来，之后的正文属于新的一块。
                            closeOpenBlock()
                            val toolCallId = update.optString("toolCallId").ifBlank { update.optString("id") }
                            val call = toolCallFor(update, toolCallId)
                            session.emit(
                                AgentEvent.ToolStarted(
                                    round = round,
                                    toolCallId = toolCallId,
                                    name = call.name,
                                    argsPreview = traceFormatter.summarizeArguments(call),
                                    command = traceFormatter.displayCommand(call),
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
                                        name = bareToolName(update.optString("title").ifBlank { "tool" }),
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
        // 用户按停止时必须真的把 ACP 掐掉：否则 agent 仍在等审批/模型，GUI 只能一直显示执行中。
        val cancelBinding = session.controller.register {
            Log.i(TAG, "cancel requested: closing ACP process")
            runCatching { client.close() }
        }
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
                promptWithRetry(client, sessionId, promptText)
            }
            closeOpenBlock()
            session.emit(AgentEvent.RunFinished(round = round, contentChars = contentChars))
            session.complete(
                AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = true,
                    content = assistantText.toString(),
                    reasoningContent = assistantThinking.toString(),
                    operation = request.operation,
                )
            ) {}
            true
        } catch (throwable: Throwable) {
            Log.w(TAG, "dsh run failed", throwable)
            sawFailure = true
            val reason = if (session.controller.isCancelled) "已停止"
            else throwable.message ?: throwable.javaClass.simpleName
            finishWithFailure(session, reason)
            false
        } finally {
            cancelBinding.close()
            runCatching { client.close() }
        }
    }

    /**
     * 网关偶发 502 / 连接被重置时，dsh 只发一次请求就放弃；这里补上与 App 内 Agent Loop
     * 一致的重试。只在"请求根本没建立"这类传输层错误上重试，避免重放已经执行过的工具。
     */
    private suspend fun promptWithRetry(client: DshAcpClient, sessionId: String, text: String) {
        var attempt = 0
        while (true) {
            attempt += 1
            try {
                client.prompt(sessionId, text)
                return
            } catch (throwable: Throwable) {
                val reason = throwable.message.orEmpty()
                val retryable = RETRYABLE_MARKERS.any { reason.contains(it) }
                if (!retryable || attempt >= PROMPT_ATTEMPTS) throw throwable
                Log.w(TAG, "prompt attempt $attempt failed ($reason), retrying in ${RETRY_DELAY_MS * attempt}ms")
                delay(RETRY_DELAY_MS * attempt)
            }
        }
    }

    /**
     * 每一轮都会新建一个 ACP 会话，dsh 自己不保留跨轮记忆；把已发生的往来压成一段前言
     * 补进 prompt，否则用户会看到"它以为这是第一次聊天"。
     */
    private fun promptWithHistory(request: AgentRuntimeWire.RunRequest, text: String): String {
        val lines = request.history.takeLast(MAX_HISTORY_MESSAGES).mapNotNull { message ->
            val content = message.content.trim()
            if (content.isEmpty()) return@mapNotNull null
            val speaker = when (message.role) {
                "user" -> "用户"
                "assistant" -> "你"
                else -> return@mapNotNull null
            }
            "$speaker：${content.take(MAX_HISTORY_CHARS_PER_MESSAGE)}"
        }
        if (lines.isEmpty()) return text
        return buildString {
            append("（以下是本次对话之前的往来，仅供你了解上下文，不要重复回复它们）\n")
            append(lines.joinToString("\n"))
            append("\n（历史结束）\n\n")
            append(text)
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
        private const val MAX_HISTORY_MESSAGES = 20
        private const val MAX_HISTORY_CHARS_PER_MESSAGE = 800
        private const val PROMPT_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 2_000L
        private val RETRYABLE_MARKERS = listOf("API request", "超时（", "Connection", "ECONNRESET", "socket")
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

        /** dsh 把 MCP 工具暴露成 mcp__<server>__<tool>，而 UI 的图标与文案表只认裸工具名。 */
        private fun bareToolName(raw: String): String {
            if (!raw.startsWith("mcp__")) return raw
            val parts = raw.split("__")
            return if (parts.size >= 3) parts.drop(2).joinToString("__") else raw
        }

        /** 复用 Eta 自己的摘要器，让 dsh 调用的工具卡片和原生 Agent Loop 长得一样。 */
        private fun toolCallFor(update: JSONObject, toolCallId: String): AgentModelClient.ToolCall {
            val title = update.optString("title").ifBlank { update.optString("kind").ifBlank { "tool" } }
            val rawInput = update.optJSONObject("rawInput") ?: JSONObject()
            return AgentModelClient.ToolCall(
                id = toolCallId,
                name = bareToolName(title),
                argumentsJson = rawInput.toString(),
            )
        }

        private val traceFormatter = AgentTraceFormatter()

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
