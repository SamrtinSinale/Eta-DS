package io.github.mangi.eta.agent.dsh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
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
 * - initialize / session/new / session/set_config_option / session/prompt
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
    private val pending = ConcurrentHashMap<Long, Channel<JSONObject>>()
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

    suspend fun initialize(): JSONObject = request(
        "initialize",
        JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("clientCapabilities", JSONObject()),
        timeoutMs = INIT_TIMEOUT_MS,
    )

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
     */
    suspend fun prompt(sessionId: String, text: String, timeoutMs: Long = PROMPT_TIMEOUT_MS): String {
        val params = JSONObject()
            .put("sessionId", sessionId)
            .put("prompt", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        val result = request("session/prompt", params, timeoutMs = timeoutMs)
        return result.optString("stopReason").ifBlank { "end_turn" }
    }

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
        val channel = Channel<JSONObject>(Channel.RENDEZVOUS)
        pending[id] = channel
        try {
            send(JSONObject()
                .put("jsonrpc", JSONRPC)
                .put("id", id)
                .put("method", method)
                .put("params", params))
            val response = try {
                withTimeout(timeoutMs) { channel.receive() }
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
            pending.remove(id)
            channel.close()
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
                    pending.remove(id)?.trySend(message)
                    continue
                }
                if (message.optString("method") == METHOD_SESSION_UPDATE) {
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
        pending.values.forEach { it.trySend(JSONObject().put("error", JSONObject().put("message", reason))) }
        pending.clear()
        runCatching { listener.onClosed(reason) }
    }

    override fun close() {
        val started = process
        process = null
        closed = true
        runCatching { writer?.close() }
        writer = null
        runCatching { started?.destroy() }
        readerThread?.interrupt()
        errorThread?.interrupt()
    }

    companion object {
        private const val TAG = "DshAcpClient"
        private const val JSONRPC = "2.0"
        private const val PROTOCOL_VERSION = 1
        private const val METHOD_SESSION_UPDATE = "session/update"
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
