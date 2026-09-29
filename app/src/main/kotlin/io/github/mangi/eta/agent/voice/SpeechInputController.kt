package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal data class SpeechInputState(
    val phase: EtaSpeechPhase = EtaSpeechPhase.IDLE,
    val preview: String = "",
    val level: Float = 0f,
    val progress: String = "",
    val error: String? = null,
    val systemIssue: EtaSpeechIssue? = null,
    val configureAvailable: Boolean = false,
    val downloadAvailable: Boolean = false,
) {
    val active: Boolean get() = phase != EtaSpeechPhase.IDLE
}

internal class SpeechInputController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val automaticEndpoint: Boolean = false,
    private val beforeCapture: () -> Unit = {},
    private val afterCapture: () -> Unit = {},
    private val onResult: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(SpeechInputState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var finishRequested = false
    private var pendingSystemResult: String? = null
    private var job: Job? = null
    private var recorder: SpeechRecorder? = null
    private var system: EtaSpeechInput? = null
    private var lease: SpeechAudioLease? = null
    private var capturing = false

    fun start(settings: SpeechSettings? = null, credentials: SpeechCredentials? = null) {
        cancel()
        val session = generation
        mutableState.value = SpeechInputState(phase = EtaSpeechPhase.STARTING, progress = "正在连接")
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                lease = SpeechAudioLease(context) { cancel() }.also { it.acquire(playback = false) }
                val config = settings ?: SpeechSettingsRepository.settings()
                val secrets = credentials ?: if (config.asr == AsrProvider.SYSTEM) SpeechCredentials()
                    else SpeechSettingsRepository.credentials(context)
                validateSpeechSettings(config, secrets, synthesis = false)
                if (config.asr == AsrProvider.SYSTEM) {
                    startSystem(session)
                    awaitCancellation()
                } else {
                    val audio = SpeechRecorder()
                    recorder = audio
                    fun update(transform: (SpeechInputState) -> SpeechInputState) {
                        scope.launch(Dispatchers.Main.immediate) {
                            if (generation == session) mutableState.value = transform(mutableState.value)
                        }
                    }
                    val text = coroutineScope {
                        var recording: Job? = null
                        val begin = {
                            if (recording == null) {
                                beforeCapture()
                                capturing = true
                                recording = launch {
                                    try {
                                        audio.record(
                                            onReady = { update { it.copy(phase = EtaSpeechPhase.LISTENING, progress = "正在聆听，点击完成结束") } },
                                            onLevel = { level -> update { it.copy(level = level) } },
                                        )
                                    } finally {
                                        if (generation == session) releaseCapture()
                                    }
                                    update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, level = 0f, progress = "正在识别") }
                                }
                            }
                            Unit
                        }
                        try {
                            if (config.asr == AsrProvider.QWEN_REALTIME || config.asr == AsrProvider.DOUBAO) {
                                recognizeSpeechStream(config, secrets, audio.frames, begin,
                                    onPreview = { preview -> update { it.copy(preview = preview) } },
                                    onEndpoint = { audio.finish() }, automaticEndpoint = automaticEndpoint)
                            } else {
                                begin()
                                val wav = withTimeout(65_000) { collectSpeechAudio(audio.frames) }
                                recognizeSpeechFile(context, config, secrets, wav) { progress ->
                                    update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = progress) }
                                }
                            }
                        } finally {
                            audio.cancel()
                            recording?.cancel()
                        }
                    }
                    if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试")
                    if (generation == session) {
                        cancel()
                        onResult(text)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (generation == session) {
                    val failure = error.speechFailure()
                    AndroidAgentLogger.warn("Eta speech recognition failed: code=${failure.code} type=${error.javaClass.simpleName}")
                    cancel()
                    mutableState.value = SpeechInputState(
                        error = failure.userMessage,
                        configureAvailable = failure.code == SpeechErrorCode.CONFIGURATION ||
                            failure.code == SpeechErrorCode.CREDENTIALS,
                    )
                }
            } finally {
                if (generation == session) cancel()
            }
        }
    }

    private fun startSystem(session: Long) {
        fun update(transform: (SpeechInputState) -> SpeechInputState) {
            if (generation == session) mutableState.value = transform(mutableState.value)
        }
        system = EtaSpeechInput(context,
            onListening = { update { it.copy(phase = EtaSpeechPhase.LISTENING, progress = "正在聆听") } },
            onRecognizing = { update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = "正在识别") } },
            onLevel = { level -> update { it.copy(level = level) } },
            onPartial = { text -> update { it.copy(preview = text) } },
            onResult = { text ->
                if (generation == session) {
                    if (automaticEndpoint || finishRequested) { cancel(); onResult(text) }
                    else {
                        pendingSystemResult = text
                        lease?.close(); lease = null
                        update { it.copy(phase = EtaSpeechPhase.LISTENING, preview = text, progress = "识别完成，点击完成插入", level = 0f) }
                    }
                }
            },
            onError = { issue ->
                if (generation == session) { lease?.close(); lease = null }
                update { SpeechInputState(error = context.getString(speechIssueMessage(issue)), systemIssue = issue,
                    downloadAvailable = issue.kind == EtaSpeechIssueKind.DOWNLOAD_AVAILABLE) }
            },
            onDownloadStatus = { status -> update {
                SpeechInputState(progress = context.getString(when (status) {
                    EtaSpeechDownloadStatus.DOWNLOADING -> R.string.voice_model_downloading
                    EtaSpeechDownloadStatus.SCHEDULED -> R.string.voice_model_scheduled
                    EtaSpeechDownloadStatus.READY -> R.string.voice_model_ready
                    EtaSpeechDownloadStatus.FAILED -> R.string.voice_model_download_failed
                }), downloadAvailable = status == EtaSpeechDownloadStatus.FAILED)
            } },
        ).also { it.start() }
    }

    fun finish() {
        if (!mutableState.value.active) return
        pendingSystemResult?.let { text -> cancel(); onResult(text); return }
        if (finishRequested) return
        finishRequested = true
        if (mutableState.value.phase == EtaSpeechPhase.STARTING) { cancel(); return }
        recorder?.finish()
        system?.finish()
        mutableState.value = mutableState.value.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = "正在识别", level = 0f)
    }

    fun downloadModel() { system?.downloadModel() }

    fun cancel() {
        generation++
        finishRequested = false
        pendingSystemResult = null
        recorder?.cancel(); recorder = null
        releaseCapture()
        system?.cancel(); system = null
        job?.cancel(); job = null
        lease?.close(); lease = null
        mutableState.value = SpeechInputState()
    }

    private fun releaseCapture() {
        if (capturing) afterCapture()
        capturing = false
    }
}
