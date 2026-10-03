package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.util.Log
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Heta 会话 → dsh 原生会话的映射。
 *
 * dsh 自己会把会话持久化（`session.v3.jsonl.zstd`），ACP 也提供 `session/resume`；
 * 早先 App 没用它，而是每轮 `session/new` 再把整段历史渲染成纯文本塞进 prompt，
 * 结果历史被截断成摘要、每轮还要重发一遍。
 *
 * 这里保存三样东西：
 * - dsh 侧 sessionId：下一轮 resume 用；
 * - 上一轮请求历史的条数与身份指纹：只有新请求的历史仍然以这段前缀开头，才能安全续接；
 *   用户编辑／重新生成导致历史分叉时对不上，必须重新开会话；
 * - 当时的模型与路由：换模型后不能拿旧会话接着跑。
 *
 * 不含任何凭据。
 */
internal data class DshAcpSessionState(
    val sessionId: String,
    /** 上一轮请求历史的消息条数。 */
    val historyCount: Int,
    /** 那 [historyCount] 条消息的身份指纹。 */
    val historyFingerprint: String,
    val model: String = "",
    val providerRoute: String = "",
) {
    fun encode(): String = JSONObject()
        .put(KEY_VERSION, VERSION)
        .put(KEY_SESSION_ID, sessionId)
        .put(KEY_HISTORY_COUNT, historyCount)
        .put(KEY_HISTORY_FINGERPRINT, historyFingerprint)
        .put(KEY_MODEL, model)
        .put(KEY_PROVIDER_ROUTE, providerRoute)
        .toString()

    companion object {
        private const val VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_SESSION_ID = "sessionId"
        private const val KEY_HISTORY_COUNT = "historyCount"
        private const val KEY_HISTORY_FINGERPRINT = "historyFingerprint"
        private const val KEY_MODEL = "model"
        private const val KEY_PROVIDER_ROUTE = "providerRoute"

        /** 解析失败一律当作"没有会话"，宁可重开也不要拿半个状态去 resume。 */
        fun decode(raw: String): DshAcpSessionState? = runCatching {
            val json = JSONObject(raw)
            val sessionId = json.optString(KEY_SESSION_ID)
            if (sessionId.isBlank()) return@runCatching null
            DshAcpSessionState(
                sessionId = sessionId,
                historyCount = json.optInt(KEY_HISTORY_COUNT, 0),
                historyFingerprint = json.optString(KEY_HISTORY_FINGERPRINT),
                model = json.optString(KEY_MODEL),
                providerRoute = json.optString(KEY_PROVIDER_ROUTE),
            )
        }.getOrNull()
    }
}

/**
 * 会话续接的判定与指纹计算：纯函数，便于单测。
 */
internal object DshAcpSessionStateCodec {

    /**
     * 只有请求历史仍然以建会话时那段前缀开头，才允许 resume。
     *
     * 条数只增不减（历史分叉／编辑会变短），前缀的消息身份必须逐条一致；
     * 模型或路由换了也不行。
     */
    fun canResume(
        state: DshAcpSessionState,
        history: List<AgentModelClient.ConversationMessage>,
        model: String,
    ): Boolean {
        if (state.sessionId.isBlank()) return false
        if (state.model != model) return false
        // 这里曾有一条 `state.providerRoute != providerRoute` 守卫，恒为假：调用方传进来的永远是
        // 同一个 OFFICIAL_ROUTE（见 DshAcpRuntime.create）。**不要**改成 providerId —— 那样守卫会
        // 恒真，每轮都重开会话、续接直接废掉；换 provider/model 的失效判断由 dsh 自己在
        // request/context 里做（dsh-agent-loop/lib/index.js）。路由仍写进存档，只作记录。
        val count = state.historyCount
        if (count < 0 || count > history.size) return false
        return fingerprint(history, count) == state.historyFingerprint
    }

    /**
     * 前 [count] 条历史消息的指纹；[count] 为 0 时是空历史的指纹。
     *
     * 只取角色与消息 id，不取内容：同一条消息在 App 侧可能因为图片编码（content vs
     * contentJson）而有不同文本表示，掺进内容会让 resume 永远对不上。id 已经足够区分
     * "是不是同一段历史"——编辑历史会换 id（或截短），两者都能被这条规则拦住。
     */
    fun fingerprint(history: List<AgentModelClient.ConversationMessage>, count: Int): String {
        val bounded = count.coerceIn(0, history.size)
        val digest = MessageDigest.getInstance("SHA-256")
        for (index in 0 until bounded) {
            val message = history[index]
            digest.update(message.role.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(message.messageId.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().toHexString().take(FINGERPRINT_CHARS)
    }

    private const val FINGERPRINT_CHARS = 32

    private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * 会话映射的落盘实现。
 *
 * 文件放在 App 私有目录，用会话键的 SHA-256 当文件名（键可能很长且含任意字符）。
 */
internal class DshAcpSessionStore(
    private val directory: File,
) {
    fun load(key: String): DshAcpSessionState? {
        if (key.isBlank()) return null
        val file = fileFor(key)
        if (!file.isFile) return null
        return runCatching { file.readText() }
            .onFailure { Log.w(TAG, "session state unreadable: ${it.javaClass.simpleName}") }
            .getOrNull()
            ?.let { raw -> DshAcpSessionState.decode(raw) }
    }

    fun save(key: String, state: DshAcpSessionState) {
        if (key.isBlank() || state.sessionId.isBlank()) return
        runCatching {
            directory.mkdirs()
            val target = fileFor(key)
            val temp = File(directory, target.name + TEMP_SUFFIX)
            temp.writeText(state.encode())
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) temp.delete()
            }
            prune()
        }.onFailure { Log.w(TAG, "session state write failed: ${it.javaClass.simpleName}") }
    }

    fun remove(key: String) {
        if (key.isBlank()) return
        runCatching { fileFor(key).delete() }
    }

    /** 会话映射只对"还能接着聊"的对话有意义，超量时按最后写入时间淘汰最旧的。 */
    private fun prune() {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_FILES)
            .forEach { file -> runCatching { file.delete() } }
    }

    private fun fileFor(key: String): File =
        File(directory, sha256(key) + FILE_SUFFIX)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val TAG = "DshAcpSessionStore"
        private const val DIRECTORY_NAME = "dsh-acp-sessions"
        private const val FILE_SUFFIX = ".json"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_FILES = 128

        fun create(context: Context): DshAcpSessionStore =
            DshAcpSessionStore(File(context.filesDir, DIRECTORY_NAME))
    }
}
