package io.github.mangi.eta.agent.voice

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SpeechLifecycleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val results = mutableListOf<String>()
    private lateinit var controller: SpeechInputController

    @Before fun setup() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        controller = SpeechInputController(RuntimeEnvironment.getApplication(), scope, onResult = results::add)
    }
    @After fun close() { controller.cancel(); scope.cancel() }

    private fun start(): ShadowSpeechRecognizer {
        controller.start(SpeechSettings(), SpeechCredentials())
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).also {
            it.triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
            shadowOf(Looper.getMainLooper()).idle()
            it.triggerOnReadyForSpeech(Bundle())
        }
    }
    private fun text(value: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(value)) }

    @Test fun dictationWaitsForUserEvenWhenSystemReturnsEarly() {
        start().triggerOnResults(text("可编辑的草稿"))
        assertTrue(results.isEmpty())
        assertEquals("可编辑的草稿", controller.state.value.preview)
        controller.finish()
        controller.finish()
        assertEquals(listOf("可编辑的草稿"), results)
        assertFalse(controller.state.value.active)
    }

    @Test fun canceledSessionCannotSubmitOrReplaceNewPreview() {
        val old = start()
        controller.cancel()
        val current = start()
        old.triggerOnResults(text("旧结果"))
        current.triggerOnPartialResults(text("新草稿"))
        assertEquals("新草稿", controller.state.value.preview)
        controller.finish()
        current.triggerOnResults(text("新结果"))
        assertEquals(listOf("新结果"), results)
    }

    @Test fun closingOwnerReleasesRecognitionAndTimeouts() {
        val recognizer = start()
        controller.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        recognizer.triggerOnResults(text("迟到"))
        assertTrue(recognizer.isDestroyed)
        assertTrue(results.isEmpty())
        assertNull(controller.state.value.error)
    }

    @Test fun anotherAudioOwnerInterruptsRecording() {
        val recognizer = start()
        val lease = SpeechAudioLease(RuntimeEnvironment.getApplication()) {}
        try {
            lease.acquire(playback = false)
            assertTrue(recognizer.isDestroyed)
            assertFalse(controller.state.value.active)
        } finally { lease.close() }
    }

    @Test fun ownerCancellationReleasesSystemRecognizer() {
        val recognizer = start()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(recognizer.isDestroyed)
        assertFalse(controller.state.value.active)
    }
}
