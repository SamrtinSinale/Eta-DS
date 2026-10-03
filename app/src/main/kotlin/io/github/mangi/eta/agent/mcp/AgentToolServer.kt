package io.github.mangi.eta.agent.mcp

import android.util.Log
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 Eda 的本地工具以 MCP(streamable-http) 暴露给 dsh 内核。
 *
 * 设计约束：
 * - 只绑定回环地址，不对局域网或公网暴露；
 * - 必须携带 Bearer token，token 由调用方生成并保存在应用私有目录；
 * - 工具清单来自 AgentToolCatalog，与模型可见的工具完全一致；
 * - 调用转发给 AgentLocalTools.execute，Root / 无障碍 / 敏感工具的权限检查仍在该执行器内部完成，
 *   这里不做任何权限放行，也不解释用户开关。
 */
internal class AgentToolServer(
    private val serverName: String,
    private val authToken: String,
    private val toolSchemaProvider: () -> JSONArray,
    private val toolExecutor: (AgentModelClient.ToolCall) -> AgentModelClient.ToolResult,
) {
    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(4)

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    val endpoint: String get() = "http://$LOOPBACK:$port$PATH_MCP"

    fun start(): Int {
        if (!running.compareAndSet(false, true)) return port
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 16)
        }
        socket = server
        port = server.localPort
        Thread({ acceptLoop(server) }, "eta-tool-server").apply { isDaemon = true }.start()
        Log.i(TAG, "tool server listening at $endpoint")
        return port
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        workers.shutdownNow()
        Log.i(TAG, "tool server stopped")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { workers.execute { serve(client) } }.onFailure { runCatching { client.close() } }
        }
    }

    private fun serve(client: Socket) {
        client.use { connection ->
            runCatching { connection.soTimeout = SOCKET_TIMEOUT_MS }
            val input = BufferedInputStream(connection.getInputStream())
            val output = BufferedOutputStream(connection.getOutputStream())
            val request = readRequest(input) ?: return
            val response = runCatching { route(request) }
                .getOrElse { failure ->
                    Log.w(TAG, "request failed", failure)
                    HttpResponse(500, CONTENT_JSON, jsonRpcError(null, -32603, "internal error"))
                }
            writeResponse(output, response, request.closeAfter)
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        if (request.path == PATH_HEALTH) return HttpResponse(200, CONTENT_JSON, """{"ok":true}""")
        if (request.path != PATH_MCP) return HttpResponse(404, CONTENT_JSON, jsonRpcError(null, -32601, "not found"))
        if (!authorized(request)) return HttpResponse(401, CONTENT_JSON, jsonRpcError(null, -32001, "unauthorized"))
        if (request.method == "GET") return HttpResponse(405, CONTENT_JSON, jsonRpcError(null, -32600, "use POST"))
        if (request.method != "POST") return HttpResponse(405, CONTENT_JSON, jsonRpcError(null, -32600, "method not allowed"))
        val payload = runCatching { JSONObject(request.body) }.getOrNull()
            ?: return HttpResponse(400, CONTENT_JSON, jsonRpcError(null, -32700, "parse error"))
        val id = payload.opt("id")
        val method = payload.optString("method")
        val params = payload.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> HttpResponse(200, CONTENT_JSON, jsonRpcResult(id, initializeResult(params)))
            "notifications/initialized", "initialized" -> HttpResponse(202, CONTENT_JSON, "")
            "tools/list" -> HttpResponse(200, CONTENT_JSON, jsonRpcResult(id, JSONObject().put("tools", mcpTools())))
            "tools/call" -> HttpResponse(200, CONTENT_JSON, jsonRpcResult(id, callTool(params)))
            "ping" -> HttpResponse(200, CONTENT_JSON, jsonRpcResult(id, JSONObject()))
            else -> HttpResponse(200, CONTENT_JSON, jsonRpcError(id, -32601, "unknown method: $method"))
        }
    }

    private fun authorized(request: HttpRequest): Boolean {
        val header = request.headers[HEADER_AUTHORIZATION] ?: return false
        val prefix = "Bearer "
        if (!header.startsWith(prefix)) return false
        return constantTimeEquals(header.substring(prefix.length).trim(), authToken)
    }

    /**
     * 回显客户端请求的协议版本。
     *
     * 官方 MCP SDK 只认自己那份版本清单（2025-06-18 及更早），服务端若坚持报一个更新的
     * 版本号，SDK 会直接判定握手失败——dsh 接不上 Eta 就是这个原因。本端点是无状态的，
     * 这些版本都能服务，所以按客户端说的回。
     */
    private fun initializeResult(params: JSONObject): JSONObject = JSONObject()
        .put(
            "protocolVersion",
            params.optString("protocolVersion").takeIf { it.isNotBlank() } ?: PROTOCOL_VERSION,
        )
        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
        .put("serverInfo", JSONObject().put("name", serverName).put("version", SERVER_VERSION))

    /** 把 AgentToolCatalog 的 OpenAI 风格工具定义投影为 MCP tools。 */
    private fun mcpTools(): JSONArray {
        val source = runCatching { toolSchemaProvider() }.getOrDefault(JSONArray())
        val result = JSONArray()
        for (index in 0 until source.length()) {
            val entry = source.optJSONObject(index) ?: continue
            val function = entry.optJSONObject("function") ?: entry
            val name = function.optString("name").takeIf { it.isNotBlank() } ?: continue
            val tool = JSONObject()
                .put("name", name)
                .put("description", function.optString("description"))
                .put("inputSchema", function.optJSONObject("parameters") ?: emptyObjectSchema())
            result.put(tool)
        }
        return result
    }

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.optString("name").takeIf { it.isNotBlank() }
            ?: return toolError("missing tool name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val call = AgentModelClient.ToolCall(
            id = "mcp-" + System.nanoTime().toString(36),
            name = name,
            argumentsJson = arguments.toString(),
        )
        val outcome = runCatching { toolExecutor(call) }
            .getOrElse { failure ->
                Log.w(TAG, "tool $name failed", failure)
                return toolError("tool execution failed: ${failure.message ?: failure.javaClass.simpleName}")
            }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", outcome.content))
        return JSONObject().put("content", content).put("isError", false)
    }

    private fun toolError(message: String): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
        .put("isError", true)

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
        val closeAfter: Boolean,
    )

    private data class HttpResponse(val status: Int, val contentType: String, val body: String)

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val current = input.read()
            if (current == -1) return null
            headerBytes.write(current)
            if (previous == CR && current == LF && headerBytes.size() >= 4) {
                val tail = headerBytes.toByteArray()
                if (tail[tail.size - 4] == CR.toByte() && tail[tail.size - 3] == LF.toByte() &&
                    tail[tail.size - 2] == CR.toByte() && tail[tail.size - 1] == LF.toByte()
                ) {
                    break
                }
            }
            previous = current
            if (headerBytes.size() > MAX_HEADER_BYTES) return null
        }
        val lines = String(headerBytes.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n")
        val requestLine = lines.firstOrNull()?.trim().orEmpty()
        if (requestLine.isEmpty()) return null
        val segments = requestLine.split(' ')
        if (segments.size < 2) return null
        val method = segments[0].uppercase()
        val path = segments[1].substringBefore('?')
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY_BYTES) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count == -1) break
            read += count
        }
        val connection = headers["connection"]?.lowercase()
        return HttpRequest(method, path, headers, String(body, 0, read, StandardCharsets.UTF_8), connection == "close")
    }

    private fun writeResponse(output: BufferedOutputStream, response: HttpResponse, close: Boolean) {
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(statusText(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: ").append(if (close) "close" else "keep-alive").append("\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Internal Server Error"
    }

    private fun jsonRpcResult(id: Any?, result: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)
        .toString()

    private fun jsonRpcError(id: Any?, code: Int, message: String): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))
        .toString()

    private fun emptyObjectSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
        .put("required", JSONArray())

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        var diff = 0
        for (index in left.indices) diff = diff or (left[index].code xor right[index].code)
        return diff == 0
    }

    companion object {
        private const val TAG = "AgentToolServer"
        private const val LOOPBACK = "127.0.0.1"
        private const val PATH_MCP = "/mcp"
        private const val PATH_HEALTH = "/healthz"
        private const val HEADER_AUTHORIZATION = "authorization"
        private const val CONTENT_JSON = "application/json"
        private const val PROTOCOL_VERSION = "2026-07-28"
        private const val SERVER_VERSION = "1.0.0"
        private const val SOCKET_TIMEOUT_MS = 120_000
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
        private const val CR = '\r'.code
        private const val LF = '\n'.code
    }
}
