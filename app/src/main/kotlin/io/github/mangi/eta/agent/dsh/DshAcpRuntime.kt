package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.util.Log
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ReasoningEffort
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用 DeepSeek Harness 作为执行内核：一次 run 对应一条 ACP 会话轮次。
 *
 * 与 AgentRuntimeRunExecutor 的分工：
 * - 这里只负责驱动 ACP，并把会话更新翻译成 [AgentEvent] 交给既有 UI 管线；
 * - 工具执行不在这里发生 —— dsh 通过 MCP 调用 Eta 暴露的本地工具端点，
 *   权限检查仍在 AgentLocalTools.execute 内部完成；
 * - 模型、地址与凭据直接取 run 请求里已冻结的配置，用户在 Heta 配置一次即可。
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
        // dsh 不保存自己的会话：把这一轮做过什么回写成 Eta 的历史消息，
        // 否则 checkpoint 里只剩用户消息，下一轮它什么都不记得。
        val transcript = DshTranscriptBuilder(runId)
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
                            transcript.text(delta)
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
                            transcript.thinking(delta)
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
                            transcript.toolStarted(toolCallId, call.name, call.argumentsJson)
                            session.emit(
                                AgentEvent.ToolStarted(
                                    round = round,
                                    toolCallId = toolCallId,
                                    name = call.name,
                                    argsPreview = summarizeTool(call),
                                    command = toolCommand(call),
                                )
                            )
                        }

                        UPDATE_TOOL_CALL_UPDATE -> {
                            val status = update.optString("status")
                            if (status == "completed" || status == "failed") {
                                val toolCallId = update.optString("toolCallId").ifBlank { update.optString("id") }
                                val resultText = toolResultText(update)
                                transcript.toolFinished(toolCallId, resultText)
                                session.emit(
                                    AgentEvent.ToolFinished(
                                        round = round,
                                        toolCallId = toolCallId,
                                        name = bareToolName(update.optString("title").ifBlank { "tool" }),
                                        resultSummary = resultText.take(600),
                                        imageCount = 0,
                                        imageBytes = 0,
                                        success = status == "completed",
                                    )
                                )
                            }
                        }

                        UPDATE_USAGE -> {
                            // ACP 的 usage_update：used 是当前上下文占用量，size 是窗口本身。
                            // 早先把 size 写进 inputTokens，于是库里存出 input_tokens=1000000
                            // 这种假用量；窗口不是输入量，这里只上报占用，窗口只用来自检。
                            val used = update.optInt("used", -1)
                            val size = update.optInt("size", -1)
                            val configured = request.config.contextWindow
                            if (size > 0 && configured != null && configured > 0 && size != configured) {
                                Log.w(
                                    TAG,
                                    "context window mismatch: dsh=$size, model config=$configured",
                                )
                            }
                            session.emit(
                                AgentEvent.UsageReceived(
                                    round = round,
                                    usage = AgentTokenUsage(
                                        contextTokens = if (used >= 0) used else null,
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
                // 会话里选的思考强度要真的传下去，否则 dsh 一直用它自己的默认档（high），
                // 对话里的「思考」开关就成了摆设。
                dshEffort(request.config.effectiveReasoningEffort)?.let { effort ->
                    runCatching { client.setConfigOption(sessionId, CONFIG_REASONING_EFFORT, effort) }
                        .onSuccess { Log.i(TAG, "reasoning effort: $effort") }
                        .onFailure { Log.w(TAG, "setting reasoning effort failed: ${it.message}") }
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
            val transcriptMessages = transcript.build()
            session.emit(AgentEvent.RunFinished(round = round, contentChars = contentChars))
            session.complete(
                AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = true,
                    content = assistantText.toString(),
                    reasoningContent = assistantThinking.toString(),
                    transcript = transcriptMessages,
                    operation = request.operation,
                )
            ) {}
            true
        } catch (throwable: Throwable) {
            Log.w(TAG, "dsh run failed", throwable)
            sawFailure = true
            val reason = if (session.controller.isCancelled) "已停止"
            else throwable.message ?: throwable.javaClass.simpleName
            val partial = runCatching { transcript.build() }.getOrDefault(emptyList())
            finishWithFailure(session, reason, partial)
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
     * 把对话历史压成一段纯文本随提示一起交给 dsh。
     *
     * 踩过的坑：早先只取 content 非空的历史行，而长会话的尾巴几乎全是工具卡片与思考卡片
     * （这些行的 content 是空的，内容挂在 tool_name／result_summary 上），于是"最近 20 条"
     * 被整批丢掉、注入结果为空——dsh 每次新起进程都从零开始，反复重读文件、轮数暴涨。
     * 现在按角色分别渲染（含工具调用与工具结果），并从后往前收集到预算为止。
     *
     * 预算是模型窗口的一个份额（见 [historyBudgetChars]），不是固定字符数。固定 6000 字符
     * 时，长会话每轮仍然只注入最近十来条被截断的消息：上下文占用永远停在窗口的个位数百分比，
     * 而 dsh 侧没有读取完整会话的工具，表现就是"聊了很久它还是什么都不记得"。
     */
    private fun promptWithHistory(request: AgentRuntimeWire.RunRequest, text: String): String {
        val budgetChars = historyBudgetChars(request.config.contextWindow)
        val lines = collectHistoryLines(request.history, budgetChars)
        if (lines.isEmpty()) {
            Log.i(TAG, "history injection empty: history=${request.history.size} msgs")
            return text
        }
        Log.i(
            TAG,
            "prompt: history=${request.history.size} msgs, injected=${lines.size} lines, " +
                "chars=${lines.sumOf { it.length }}, budget=$budgetChars",
        )
        return buildString {
            append("（以下是本次对话之前的往来，仅供你了解上下文，不要重复回复它们）\n")
            append(lines.joinToString("\n"))
            append("\n（历史结束）\n\n")
            append(text)
        }
    }

    /**
     * dsh 的工具输出放在 content[] 里（形如 {type:"content", content:{type:"text", text:...}}），
     * 它并没有 rawOutput 字段——之前读错字段，所以卡片上看不到任何结果，失败也没有原因。
     */
    private fun toolResultText(update: JSONObject): String {
        val entries = update.optJSONArray("content") ?: return ""
        val text = StringBuilder()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val block = entry.optJSONObject("content") ?: entry
            val value = block.optString("text")
            if (value.isBlank()) continue
            if (text.isNotEmpty()) text.append('\n')
            text.append(value)
        }
        return text.toString()
    }

    /**
     * 工具卡片的摘要。
     *
     * Eta 的摘要表只认自己的工具；dsh 自带的 bash/read/glob 一类会落到兜底文案
     * （"准备执行"），于是界面上看不出它到底在干什么。
     */
    private fun summarizeTool(call: AgentModelClient.ToolCall): String {
        val known = traceFormatter.summarizeArguments(call)
        if (known != UNKNOWN_TOOL_LABEL) return known
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: JSONObject()
        fun text(key: String) = args.optString(key).takeIf { it.isNotBlank() }
        fun tail(path: String?) = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return when (call.name) {
            "bash" -> "执行命令"
            "glob" -> "查找文件" + (text("pattern")?.let { " · $it" } ?: "")
            "grep" -> "搜索内容" + (text("pattern")?.let { " · $it" } ?: "")
            "read" -> "读取文件" + (tail(text("file_path") ?: text("path"))?.let { " · $it" } ?: "")
            "write" -> "写入文件" + (tail(text("file_path") ?: text("path"))?.let { " · $it" } ?: "")
            "edit" -> "编辑文件" + (tail(text("file_path") ?: text("path"))?.let { " · $it" } ?: "")
            "ls" -> "列出目录" + (tail(text("path"))?.let { " · $it" } ?: "")
            "todo", "todo_write" -> "更新任务清单"
            "job_list", "jobs" -> "查看后台任务"
            "web_search" -> "搜索网页"
            "web_fetch" -> "抓取网页"
            "subagent" -> "派生子任务"
            "ask_user" -> "询问用户"
            else -> call.name
        }
    }

    /** bash 的命令单独给一行，方便核对；Eta 自己的终端工具仍走原格式化器。 */
    private fun toolCommand(call: AgentModelClient.ToolCall): String? =
        traceFormatter.displayCommand(call) ?: runCatching {
            if (call.name != "bash") return@runCatching null
            JSONObject(call.argumentsJson).optString("command").trim()
                .takeIf { it.isNotBlank() && it.length <= MAX_DISPLAY_COMMAND_CHARS }
        }.getOrNull()

    /**
     * Eta 的思考档位（off/default/minimal/low/medium/high/xhigh/max）映射到 dsh 的四档
     * （off/low/high/max）；default 交给 dsh 自己的默认值。
     */
    private fun dshEffort(effort: ReasoningEffort?): String? = when (effort) {
        null, ReasoningEffort.DEFAULT -> null
        ReasoningEffort.OFF -> "off"
        ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> "low"
        ReasoningEffort.MEDIUM, ReasoningEffort.HIGH -> "high"
        ReasoningEffort.XHIGH, ReasoningEffort.MAX -> "max"
    }

    private fun finishWithFailure(
        session: AgentRuntimeSession,
        reason: String,
        transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
    ) {
        session.emit(AgentEvent.RunFailed(reason = reason))
        session.complete(
            AgentRuntimeWire.RunResult(
                runId = session.runId,
                ok = false,
                content = "",
                error = reason,
                transcript = transcript,
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
        private const val CONFIG_REASONING_EFFORT = "reasoning_effort"
        private const val UNKNOWN_TOOL_LABEL = "准备执行"
        private const val MAX_DISPLAY_COMMAND_CHARS = 600
        private const val MAX_HISTORY_MESSAGES = 240
        private const val MAX_HISTORY_CHARS_PER_MESSAGE = 2_000
        private const val DEFAULT_CONTEXT_WINDOW = 128_000
        private const val HISTORY_WINDOW_PERCENT = 15L
        private const val MIN_HISTORY_TOTAL_CHARS = 6_000
        private const val MAX_HISTORY_TOTAL_CHARS = 150_000
        private const val MAX_HISTORY_TOOL_NAMES = 5
        private const val PROMPT_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 2_000L
        private val RETRYABLE_MARKERS = listOf("API request", "超时（", "Connection", "ECONNRESET", "socket")
        private const val DSH_ENTRY_RELATIVE = "usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
        private const val OFFICIAL_ROUTE = "deepseek-official"

        /**
         * 历史注入的字符预算：模型窗口的一个份额，而不是固定字符数。
         *
         * 1 字符按 1 token 保守估算（中文实际就是 1:1，英文只会更省），把窗口的 15% 留给历史，
         * 其余留给本轮的思考、工具输出和回答——与 AgentContextBudget 的
         * TRIGGER_RATIO(0.85) / RECENT_RATIO(0.20) 是同一套口径。上限 150k 字符是为了压住
         * 单轮 prefill：注入的历史每轮都在滑动，前缀缓存命中不到，不能无限放大。
         * 窗口未知时按 128K 处理，并按上下限夹住。
         */
        internal fun historyBudgetChars(contextWindow: Int?): Int {
            val window = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
            return (window.toLong() * HISTORY_WINDOW_PERCENT / 100)
                .coerceIn(MIN_HISTORY_TOTAL_CHARS.toLong(), MAX_HISTORY_TOTAL_CHARS.toLong())
                .toInt()
        }

        /**
         * 从最新往回收集可注入的历史行，直到用完预算，返回时恢复成从旧到新。
         *
         * 单条渲染超过剩余预算时停止而不是跳过：继续往前只会拿到更旧、更小的消息，
         * 拼出来的一段会缺中间环节，比少注入更糟。
         */
        internal fun collectHistoryLines(
            history: List<AgentModelClient.ConversationMessage>,
            budgetChars: Int,
            maxMessages: Int = MAX_HISTORY_MESSAGES,
            maxCharsPerMessage: Int = MAX_HISTORY_CHARS_PER_MESSAGE,
        ): List<String> {
            val lines = ArrayList<String>()
            var budget = budgetChars
            for (message in history.asReversed()) {
                if (lines.size >= maxMessages) break
                val rendered = renderHistoryMessage(message, maxCharsPerMessage) ?: continue
                if (rendered.length > budget) break
                budget -= rendered.length
                lines.add(rendered)
            }
            lines.reverse()
            return lines
        }

        /** 单条历史消息的文本化；没有可读内容时返回 null。 */
        private fun renderHistoryMessage(
            message: AgentModelClient.ConversationMessage,
            maxCharsPerMessage: Int,
        ): String? {
            val content = message.content.trim()
            val toolNames = toolCallNames(message)
            val rendered = when (message.role) {
                "user" -> content.takeIf { it.isNotEmpty() }?.let { "用户：${it.take(maxCharsPerMessage)}" }
                "assistant" -> buildString {
                    if (content.isNotEmpty()) append("你：${content.take(maxCharsPerMessage)}")
                    if (toolNames.isNotEmpty()) {
                        if (isNotEmpty()) append('\n')
                        append("你调用了工具：")
                        append(toolNames.take(MAX_HISTORY_TOOL_NAMES).joinToString("、"))
                    }
                }.takeIf { it.isNotEmpty() }
                "tool" -> "工具结果：${content.ifEmpty { "（无输出）" }.take(maxCharsPerMessage)}"
                "system" -> content.takeIf { it.isNotEmpty() }?.let { "系统：${it.take(maxCharsPerMessage)}" }
                else -> null
            } ?: return null
            return if (message.contextSummary) "（更早对话的摘要）$rendered" else rendered
        }

        /** 从工具调用 JSON 里取出工具名，解析失败就当作没有。 */
        private fun toolCallNames(message: AgentModelClient.ConversationMessage): List<String> {
            val raw = message.toolCallsJson.trim()
            if (raw.isEmpty()) return emptyList()
            return runCatching {
                val array = org.json.JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val call = array.optJSONObject(index) ?: continue
                        val name = call.optJSONObject("function")?.optString("name").orEmpty()
                            .ifBlank { call.optString("name") }
                        if (name.isNotBlank()) add(bareToolName(name))
                    }
                }
            }.getOrDefault(emptyList())
        }

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

        /**
         * Heta 只有 dsh 一个内核：运行时或模型配置不可用时给出明确原因，让上层直接报错，
         * 不再静默回退到旧内核（旧内核会把整段历史全量重发，实测 175k tokens／轮、单任务
         * 50 分钟，界面上完全看不出降级）。
         */
        fun unavailableReason(context: Context, request: AgentRuntimeWire.RunRequest): String {
            val modelConfig = request.config
            if (modelConfig.apiKey.isBlank() || modelConfig.baseUrl.isBlank() || modelConfig.model.isBlank()) {
                return "模型服务配置不完整（缺 API Key／地址／模型名），dsh 内核无法启动，已停止本次执行。"
            }
            val ready = runCatching { DshRuntimeInstaller.isReady(context) }.getOrDefault(false)
            return if (ready) {
                "dsh 内核启动失败：运行时已就绪但会话没能建立，已停止本次执行。"
            } else {
                "dsh 运行时未就绪（随包运行时缺失或正在解压），已停止本次执行，避免回退到旧内核。"
            }
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

/**
 * 把 dsh 的 ACP 流（思考／正文／工具调用／工具结果）重新拼回 Eta 的历史消息。
 *
 * dsh 每次提问都是一次性的 ACP 会话，不会把这一轮做过什么写回 Eta；
 * 没有这个回写，checkpoint 里就只剩用户消息，下一轮 dsh 从零开始（反复重读、轮数暴涨）。
 * 形态对齐旧内核：一段 assistant 输出（可带 tool_calls）+ 若干条 tool 结果。
 */
private class DshTranscriptBuilder(private val runId: String) {
    private val messages = JSONArray()
    private val reasoning = StringBuilder()
    private val content = StringBuilder()
    private var calls = JSONArray()
    private val results = ArrayList<Pair<String, String>>()
    private val resultIds = linkedSetOf<String>()
    private var hasPending = false
    private var round = 0

    @Synchronized
    fun thinking(delta: String) {
        if (delta.isEmpty()) return
        if (results.isNotEmpty()) flush()
        hasPending = true
        reasoning.append(delta)
    }

    @Synchronized
    fun text(delta: String) {
        if (delta.isEmpty()) return
        if (results.isNotEmpty()) flush()
        hasPending = true
        content.append(delta)
    }

    @Synchronized
    fun toolStarted(id: String, name: String, argumentsJson: String) {
        if (results.isNotEmpty()) flush()
        hasPending = true
        calls.put(
            JSONObject()
                .put("id", id)
                .put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", argumentsJson))
        )
    }

    @Synchronized
    fun toolFinished(id: String, result: String) {
        results += id to result
    }

    /** 把当前一段 assistant 输出与已收到的工具结果写进消息列表。 */
    @Synchronized
    fun flush() {
        if (!hasPending && results.isEmpty()) return
        if (hasPending) {
            round += 1
            val message = JSONObject()
                .put("role", "assistant")
                .put("content", content.toString())
            if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning.toString())
            if (calls.length() > 0) message.put("tool_calls", JSONArray(calls.toString()))
            message.put("_eta_message_id", "assistant-$runId-$round")
            messages.put(message)
        }
        for ((id, text) in results) {
            messages.put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", id)
                    .put("content", text)
            )
            resultIds += id
        }
        hasPending = false
        reasoning.setLength(0)
        content.setLength(0)
        calls = JSONArray()
        results.clear()
    }

    /** 收尾：给被中断、始终没有等到结果的工具调用补一条占位结果，再转成持久消息。 */
    @Synchronized
    fun build(): List<AgentModelClient.ConversationMessage> {
        flush()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val messageCalls = message.optJSONArray("tool_calls") ?: continue
            for (callIndex in 0 until messageCalls.length()) {
                val callId = messageCalls.optJSONObject(callIndex)?.optString("id").orEmpty()
                if (callId.isEmpty() || callId in resultIds) continue
                resultIds += callId
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", INTERRUPTED_RESULT)
                )
            }
        }
        return AgentConversationCodec.transcript(messages, 0)
    }

    companion object {
        private const val INTERRUPTED_RESULT = "（执行被中断，未返回结果）"
    }
}
