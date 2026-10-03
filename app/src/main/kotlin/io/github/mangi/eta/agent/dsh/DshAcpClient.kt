package io.github.mangi.eta.agent.dsh

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * DeepSeek Harness 的 ACP (Agent Client Protocol) v1 客户端。
 *
 * 协议要点（已实测）：
 * - 换行分隔的 JSON-RPC 2.0，标准输出专供协议帧，日志走标准错误
 * - initialize / session/new / session/resume / session/set_config_option / session/prompt
 * - dsh 自己会把会话持久化，`session/resume` 能带着完整上下文接着聊（不必每轮重放历史）
 * - 会话过程中的 `session/update` 通知承载流式内容：
 *   agent_message_chunk、agent_thought_chunk、tool_call、tool_call_update、usage_update
 * - prompt 请求在轮次结束时返回 stopReason(如 end_turn)
 *
 * 本类只负责协议与进程，不解释 UI 语义；事件由 [listener] 交给上层映射。
 */
internal class DshAcpClient(
    private val command: List<String>,
    private val workingDirectory: String,
    private val extraEnvironment: Map<String, String> = emptyMap(),
    private val listener: Listener,
) : AutoCloseable {

    interface Listener {
        /** 收到一条会话更新（原始 update 对象）。 */
        fun onSessionUpdate(sessionId: String, update: JSONObject)

        /** 进程退出或协议中断。 */
        fun onClosed(reason: String)
    }

    private val nextId = AtomicLong(1)
    private val pending = AcpResponseSlots()
    private val processLock = Any()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var closed = false

    private var readerThread: Thread? = null
    private var errorThread: Thread? = null

    @Volatile
    var sessionId: String? = null
        private set

    /**
     * dsh 在 `initialize` 里声明本连接是否接受内联图片。
     *
     * 它是两条与运算：附件存储支持栅格格式，**且**当前模型声明了 image 输入。
     * 后者取决于 dsh 自己的模型目录——自定义网关的模型名不在目录里时会被当成纯文本，
     * 于是这里为 false，此时发送 image block 会被 dsh 以
     * `inline image prompts were not advertised by this connection` 拒绝。
     */
    @Volatile
    var imagePromptEnabled: Boolean = false
        private set

    val running: Boolean get() = process?.isAlive == true

    fun start() {
        synchronized(processLock) {
            if (process != null) return
            val builder = ProcessBuilder(command)
            builder.directory(java.io.File(workingDirectory))
            builder.environment().putAll(extraEnvironment)
            builder.redirectErrorStream(false)
            val started = builder.start()
            process = started
            writer = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8))
            readerThread = Thread({ readLoop(started) }, "dsh-acp-reader").apply { isDaemon = true; start() }
            errorThread = Thread({ errorLoop(started) }, "dsh-acp-stderr").apply { isDaemon = true; start() }
        }
    }

    suspend fun initialize(): JSONObject {
        val result = request(
            "initialize",
            JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("clientCapabilities", JSONObject()),
            timeoutMs = INIT_TIMEOUT_MS,
        )
        imagePromptEnabled = result
            .optJSONObject("agentCapabilities")
            ?.optJSONObject("promptCapabilities")
            ?.optBoolean("image") == true
        return result
    }

    suspend fun newSession(
        cwd: String,
        mcpServers: List<JSONObject> = emptyList(),
    ): String {
        val params = JSONObject().put("cwd", cwd).put("mcpServers", JSONArray().also { array ->
            mcpServers.forEach(array::put)
        })
        val result = request("session/new", params, timeoutMs = SESSION_TIMEOUT_MS)
        val id = result.optString("sessionId").takeIf { it.isNotBlank() }
            ?: throw DshAcpException("session/new 未返回 sessionId")
        sessionId = id
        return id
    }

    /**
     * 续接一个已持久化的 dsh 会话。
     *
     * 约束（dsh 侧实测）：会话必须已落盘、不是 subagent、且 [cwd] 与建会话时一致；
     * 不满足时 dsh 回 invalidParams，调用方应回退到 [newSession]。
     */
    suspend fun resumeSession(
        sessionId: String,
        cwd: String,
        mcpServers: List<JSONObject> = emptyList(),
    ): String {
        val params = JSONObject()
            .put("sessionId", sessionId)
            .put("cwd", cwd)
            .put("mcpServers", JSONArray().also { array -> mcpServers.forEach(array::put) })
        val result = request(METHOD_SESSION_RESUME, params, timeoutMs = SESSION_TIMEOUT_MS)
        val resumed = result.optString("sessionId").takeIf { it.isNotBlank() } ?: sessionId
        this.sessionId = resumed
        return resumed
    }

    /** 设置会话广告出来的任意配置项（模型、思考强度……）。 */
    suspend fun setConfigOption(sessionId: String, configId: String, value: String): JSONObject = request(
        METHOD_SET_CONFIG_OPTION,
        JSONObject()
            .put("sessionId", sessionId)
            .put("configId", configId)
            .put("value", value),
        timeoutMs = SESSION_TIMEOUT_MS,
    )

    suspend fun setModel(sessionId: String, providerRoute: String, model: String): JSONObject =
        request(
            "session/set_config_option",
            JSONObject()
                .put("sessionId", sessionId)
                .put("configId", CONFIG_MODEL)
                .put("value", modelValue(providerRoute, model)),
            timeoutMs = SESSION_TIMEOUT_MS,
        )

    /**
     * 发送一轮提示；[onUpdate] 之外的流式内容通过 [Listener] 回调。
     * 返回终止原因（如 end_turn / max_tokens / cancelled）。
     *
     * [images] 按 wire order 夹在文本之后。dsh 侧要求 data 是**规范 base64**
     * （标准字母表、正确 padding、不得含换行），mimeType 只能是 png/jpeg/webp/gif；
     * 不合规会在准入阶段直接回 invalidParams。
     */
    suspend fun prompt(
        sessionId: String,
        text: String,
        images: List<AcpImage> = emptyList(),
        timeoutMs: Long = PROMPT_TIMEOUT_MS,
    ): String {
        val blocks = JSONArray()
        // dsh 只要求「图片或非空文本」至少有一个，顺序不影响语义；
        // 文本放前面更贴近阅读顺序，也方便日志里肉眼核对。
        if (text.isNotEmpty()) {
            blocks.put(JSONObject().put("type", "text").put("text", text))
        }
        images.forEach { image ->
            blocks.put(
                JSONObject()
                    .put("type", "image")
                    .put("mimeType", image.mimeType)
                    .put("data", image.data)
            )
        }
        val params = JSONObject()
            .put("sessionId", sessionId)
            .put("prompt", blocks)
        val result = request("session/prompt", params, timeoutMs = timeoutMs)
        return result.optString("stopReason").ifBlank { "end_turn" }
    }

    /** 一条已编码好的内联图片。[data] 必须是规范 base64。 */
    internal data class AcpImage(
        val mimeType: String,
        val data: String,
    )

    suspend fun cancel(sessionId: String) {
        runCatching {
            send(JSONObject()
                .put("jsonrpc", JSONRPC)
                .put("method", "session/cancel")
                .put("params", JSONObject().put("sessionId", sessionId)))
        }
    }

    private suspend fun request(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
        val id = nextId.getAndIncrement()
        val slot = pending.register(id)
        try {
            send(JSONObject()
                .put("jsonrpc", JSONRPC)
                .put("id", id)
                .put("method", method)
                .put("params", params))
            val response = try {
                withTimeout(timeoutMs) { slot.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw DshAcpException("$method 超时（${timeoutMs}ms）")
            }
            val error = response.optJSONObject("error")
            if (error != null) {
                throw DshAcpException(
                    "$method 失败：${error.optString("message").ifBlank { error.optString("code") }}"
                )
            }
            return response.optJSONObject("result") ?: JSONObject()
        } finally {
            pending.forget(id)
        }
    }

    private fun send(message: JSONObject) {
        val target = writer ?: throw DshAcpException("ACP 进程未启动")
        synchronized(processLock) {
            target.write(message.toString())
            target.write("\n")
            target.flush()
        }
    }

    private fun readLoop(started: Process) {
        val reader = BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val message = runCatching { JSONObject(trimmed) }.getOrNull() ?: continue
                val id = if (message.has("id") && !message.isNull("id")) message.optLong("id", -1) else -1L
                if (id >= 0 && (message.has("result") || message.has("error"))) {
                    pending.settle(id, message)
                    continue
                }
                val method = message.optString("method")
                if (id >= 0 && method.isNotBlank()) {
                    // agent -> client 的请求（审批等）：不回话 agent 就会一直等下去。
                    runCatching { answerServerRequest(id, method, message.optJSONObject("params")) }
                        .onFailure { Log.w(TAG, "answering $method failed", it) }
                    continue
                }
                if (method == METHOD_SESSION_UPDATE) {
                    val params = message.optJSONObject("params") ?: continue
                    val session = params.optString("sessionId")
                    val update = params.optJSONObject("update") ?: continue
                    runCatching { listener.onSessionUpdate(session, update) }
                        .onFailure { Log.w(TAG, "session update failed", it) }
                }
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "ACP reader stopped", throwable)
        } finally {
            notifyClosed("protocol-eof")
        }
    }

    /**
     * 回答 agent 发来的请求。
     *
     * dsh 每次工具调用都会先要一次审批（只提供 allow-once / reject-once），
     * Eta 侧不在这一层做审批，所以一律放行；未知方法回错，避免对方永久等待。
     */
    private fun answerServerRequest(id: Long, method: String, params: JSONObject?) {
        val reply = JSONObject().put("jsonrpc", JSONRPC).put("id", id)
        if (method == METHOD_REQUEST_PERMISSION) {
            val optionId = allowOptionId(params)
            Log.i(TAG, "permission granted: $optionId")
            reply.put(
                "result",
                JSONObject().put(
                    "outcome",
                    JSONObject().put("outcome", "selected").put("optionId", optionId),
                ),
            )
        } else {
            Log.w(TAG, "unsupported agent request: $method")
            reply.put(
                "error",
                JSONObject().put("code", -32601).put("message", "unsupported method: $method"),
            )
        }
        send(reply)
    }

    private fun allowOptionId(params: JSONObject?): String {
        val options = params?.optJSONArray("options") ?: return ALLOW_ONCE
        for (index in 0 until options.length()) {
            val option = options.optJSONObject(index) ?: continue
            if (option.optString("kind").startsWith("allow")) return option.optString("optionId")
        }
        for (index in 0 until options.length()) {
            val option = options.optJSONObject(index) ?: continue
            if (option.optString("optionId").startsWith("allow")) return option.optString("optionId")
        }
        return ALLOW_ONCE
    }

    private fun errorLoop(started: Process) {
        val reader = BufferedReader(InputStreamReader(started.errorStream, Charsets.UTF_8))
        runCatching {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) Log.i(TAG, "acp: ${line.take(400)}")
            }
        }
    }

    private fun notifyClosed(reason: String) {
        if (closed) return
        closed = true
        failPending(reason)
        runCatching { listener.onClosed(reason) }
    }

    /**
     * 主动收尾（用户停止、正常结束都会走这里）。
     *
     * 关键是先把在途请求结算掉：以前只 destroy 进程，等着 session/prompt 响应的协程要等到
     * 超时（30 分钟）才醒，界面就成了"点了停止但半天没反应"。现在先让请求立刻拿到结果，
     * 再尽力把进程和管道收干净。
     */
    override fun close() {
        val started = process
        val wasOpen = !closed
        process = null
        closed = true
        failPending(STOPPED_REASON)
        runCatching { writer?.close() }
        writer = null
        runCatching { started?.destroy() }
        // 关掉子进程的管道：即使 su 已经退出、node 还在，写回一个已关闭的管道也会让它退出。
        runCatching { started?.inputStream?.close() }
        runCatching { started?.errorStream?.close() }
        runCatching { started?.outputStream?.close() }
        readerThread?.interrupt()
        errorThread?.interrupt()
        if (wasOpen) runCatching { listener.onClosed(STOPPED_REASON) }
    }

    /** 让所有在途请求立刻拿到一个错误结果，而不是继续等超时。 */
    private fun failPending(reason: String) {
        pending.failAll(reason)
    }

    companion object {
        private const val TAG = "DshAcpClient"
        private const val JSONRPC = "2.0"
        private const val PROTOCOL_VERSION = 1
        private const val METHOD_SESSION_UPDATE = "session/update"
        private const val METHOD_SESSION_RESUME = "session/resume"
        private const val METHOD_REQUEST_PERMISSION = "session/request_permission"
        private const val METHOD_SET_CONFIG_OPTION = "session/set_config_option"
        private const val ALLOW_ONCE = "allow-once"
        private const val STOPPED_REASON = "已停止"
        private const val CONFIG_MODEL = "model"
        private const val INIT_TIMEOUT_MS = 60_000L
        private const val SESSION_TIMEOUT_MS = 60_000L
        private const val PROMPT_TIMEOUT_MS = 30 * 60 * 1000L

        /** ACP 用 JSON 数组字符串表示 (provider, model) 对。 */
        fun modelValue(providerRoute: String, model: String): String =
            JSONArray().put(providerRoute).put(model).toString()
    }
}

internal class DshAcpException(message: String) : Exception(message)

/**
 * 在途 JSON-RPC 请求的结果槽。
 *
 * 这里**不能**用 `Channel.RENDEZVOUS`。它是零容量通道，`trySend` 只在**已经有接收者挂在
 * `receive()` 上**时才成功；而 [DshAcpClient.request] 是先 `send()` 再 `receive()`，所以
 * dsh 秒回时结果会被 `trySend` 静默丢掉——调用方随后一直等到超时。`session/prompt` 的超时
 * 是 **30 分钟**，界面上的表现就是"卡住了、点了没反应"。
 *
 * 越是快工具越容易踩：`job_output` 这种读完就返回的调用，dsh 几乎是立刻回包。
 *
 * [CompletableDeferred] 是一次性结果槽：先到先存，谁在什么时候来取都能拿到，不存在丢的可能。
 * 结果早于 `await()` 到达只是被存下来，而不是被拒收。
 */
internal class AcpResponseSlots {
    private val slots = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()

    /** 登记一个在途请求，返回它的结果槽。必须在 [settle] 之前调用。 */
    fun register(id: Long): CompletableDeferred<JSONObject> =
        CompletableDeferred<JSONObject>().also { slots[id] = it }

    /** 结算一个在途请求。false 表示它已经超时或被取消，没人在等这个结果了。 */
    fun settle(id: Long, message: JSONObject): Boolean = slots.remove(id)?.complete(message) == true

    /** 放弃一个在途请求（超时路径用）。 */
    fun forget(id: Long) {
        slots.remove(id)
    }

    /** 让所有在途请求立刻拿到错误结果，而不是继续等超时。 */
    fun failAll(reason: String) {
        if (slots.isEmpty()) return
        val failure = JSONObject().put("error", JSONObject().put("message", reason))
        slots.values.forEach { it.complete(failure) }
        slots.clear()
    }
}
