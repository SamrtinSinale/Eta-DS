package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import java.util.Base64
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal suspend fun synthesizeSpeech(settings: SpeechSettings, credentials: SpeechCredentials,
    text: String, onAudio: (ByteArray) -> Unit) = withTimeout(90_000) {
    val qwen = settings.tts == TtsProvider.QWEN
    val request = if (qwen) {
        SpeechHttp.jsonRequest(speechBaseUrl(settings.qwenTts.endpoint()) + "/api/v1/services/aigc/multimodal-generation/generation",
            credentials.qwenTts, JSONObject().put("model", "qwen3-tts-flash")
                .put("input", JSONObject().put("text", text).put("voice", settings.qwenVoice).put("language_type", "Auto")))
            .header("X-DashScope-SSE", "enable").build()
    } else {
        Request.Builder().url(speechBaseUrl(settings.doubaoTts.baseUrl) + "/api/v3/tts/unidirectional")
            .doubaoHeaders(settings.doubaoTts, credentials.doubaoTts, "seed-tts-2.0")
            .post(JSONObject().put("req_params", JSONObject().put("text", text).put("speaker", settings.doubaoVoice)
                .put("audio_params", JSONObject().put("format", "pcm").put("sample_rate", 24000)))
                .toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }
    SpeechHttp.read(request) { response ->
        val decoder = SpeechAudioStreamDecoder(qwen, onAudio)
        val source = response.body.source()
        while (!source.exhausted() && !decoder.finished) {
            val line = try { source.readUtf8LineStrict(SpeechHttp.MAX_JSON_BYTES) } catch (error: java.io.EOFException) {
                if (source.buffer.size > SpeechHttp.MAX_JSON_BYTES) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音音频块过大")
                source.readUtf8()
            }
            decoder.line(line)
        }
        decoder.end()
    }
}

/** SSE 事件边界与 HTTP chunk 边界无关；豆包则每行一个 JSON 对象。 */
internal class SpeechAudioStreamDecoder(private val qwen: Boolean, onAudio: (ByteArray) -> Unit) {
    private val event = StringBuilder()
    private val pcm = SpeechPcmDecoder(onAudio)
    var finished = false
        private set
    private var audioBytes = 0L

    fun line(line: String) {
        if (finished) return
        if (!qwen) {
            if (line.isNotBlank()) payload(line)
        } else if (line.isEmpty()) {
            flush()
        } else if (line.startsWith("data:")) {
            if (event.isNotEmpty()) event.append('\n')
            event.append(line.removePrefix("data:").removePrefix(" "))
            if (event.length > SpeechHttp.MAX_JSON_BYTES) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音音频块过大")
        }
    }

    private fun flush() {
        if (event.isEmpty()) return
        val value = event.toString()
        event.clear()
        payload(value)
    }

    private fun payload(value: String) {
        val message = JSONObject(value)
        val encoded: String
        if (qwen) {
            if (message.optString("code").isNotBlank() || message.optInt("status_code", 200) != 200) {
                throw SpeechFailure(SpeechErrorCode.SERVER, "千问语音合成失败，请检查配置")
            }
            val output = message.getJSONObject("output")
            encoded = output.optJSONObject("audio")?.optString("data").orEmpty()
            finished = output.optString("finish_reason") == "stop"
        } else {
            when (message.getInt("code")) {
                0 -> Unit
                20000000 -> finished = true
                else -> throw SpeechFailure(SpeechErrorCode.SERVER, "豆包语音合成失败（${message.getInt("code")}）")
            }
            encoded = message.optString("data")
        }
        if (encoded.isNotEmpty()) {
            val bytes = Base64.getDecoder().decode(encoded)
            audioBytes += bytes.size
            if (audioBytes > 24_000 * 2 * 180) {
                throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音音频格式或长度无效")
            }
            pcm.write(bytes)
        }
    }

    fun end() {
        if (qwen && !finished) flush()
        if (!finished || audioBytes == 0L) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音音频不完整，请重试")
        pcm.end()
    }
}
