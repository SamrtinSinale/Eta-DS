package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeechPlaybackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val outputs = mutableListOf<FakeOutput>()
    private var canceled = 0
    private val playback = SpeechPlaybackController(context, scope,
        synthesize = { _, _, _, audio ->
            try { audio(byteArrayOf(1, 2)); awaitCancellation() }
            finally { canceled++ }
        },
        createOutput = { FakeOutput().also(outputs::add) },
    )
    @After fun close() { playback.stop(); scope.cancel() }

    private fun start(id: String) {
        playback.speak(id, "回答正文", SpeechSettings(tts = TtsProvider.QWEN), SpeechCredentials(qwenTts = "test-key"))
        val deadline = System.nanoTime() + 3_000_000_000
        while (playback.state.value.loading && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertNull(playback.state.value.error)
        assertFalse(playback.state.value.loading)
        assertEquals(id, playback.state.value.messageId)
    }

    @Test fun newPlaybackCancelsOldSynthesisAndClosesItsPlayer() {
        start("old")
        start("new")
        assertEquals(1, canceled)
        assertTrue(outputs.first().closed)
        assertFalse(outputs.last().closed)
        playback.stop()
        assertEquals(2, canceled)
        assertTrue(outputs.last().closed)
        assertNull(playback.state.value.messageId)
    }

    @Test fun microphoneLeaseInterruptsPlayback() {
        start("answer")
        val microphone = SpeechAudioLease(context) {}
        try {
            microphone.acquire(playback = false)
            assertTrue(outputs.single().closed)
            assertEquals(1, canceled)
            assertNull(playback.state.value.messageId)
        } finally { microphone.close() }
    }

    @Test fun microphoneWinsWhileOldPlaybackIsStillPreparing() {
        playback.speak("preparing", "尚未合成的回答", SpeechSettings(tts = TtsProvider.QWEN), SpeechCredentials(qwenTts = "test-key"))
        val microphone = SpeechAudioLease(context) {}
        try {
            microphone.acquire(playback = false)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(playback.state.value.messageId)
            assertTrue(outputs.all { it.closed })
        } finally { microphone.close() }
    }

    @Test fun ownerCancellationClosesAudioWithoutExplicitStop() {
        start("answer")
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(outputs.single().closed)
        assertNull(playback.state.value.messageId)
    }

    @Test fun unpluggingHeadphonesStopsSynthesisAndPlayback() {
        start("answer")
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(outputs.single().closed)
        assertNull(playback.state.value.messageId)
    }

    private class FakeOutput : SpeechAudioOutput {
        var closed = false
        override fun write(bytes: ByteArray) { check(!closed) }
        override fun drained() = true
        override fun close() { closed = true }
    }
}
