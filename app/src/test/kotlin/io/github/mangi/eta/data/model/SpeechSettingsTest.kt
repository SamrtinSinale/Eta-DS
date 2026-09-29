package io.github.mangi.eta.data.model

import io.github.mangi.eta.agent.voice.SpeechFailure
import io.github.mangi.eta.agent.voice.validateSpeechSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SpeechSettingsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun oldSettingsPreserveSystemRecognitionAndSilentOutput() {
        val config = json.decodeFromString<SpeechSettings>("{}")
        assertEquals(AsrProvider.SYSTEM, config.asr)
        assertEquals(TtsProvider.NONE, config.tts)
        assertFalse(config.autoSpeak)
        validateSpeechSettings(config, SpeechCredentials(), synthesis = false)
    }

    @Test fun switchingServicesPreservesIndependentConfigurations() {
        val config = SpeechSettings(asr = AsrProvider.QWEN_REALTIME, tts = TtsProvider.DOUBAO,
            qwenAsr = QwenSpeechConfig(SpeechRegion.SINGAPORE),
            doubaoTts = DoubaoSpeechConfig(legacyAuth = true, appId = "test-app"), qwenVoice = "custom-voice")
        val changed = config.copy(asr = AsrProvider.SYSTEM, tts = TtsProvider.NONE)
        assertEquals(changed, json.decodeFromString<SpeechSettings>(json.encodeToString(changed)))
        assertEquals(config.qwenAsr, changed.qwenAsr)
        assertEquals(config.doubaoTts, changed.doubaoTts)
        assertEquals(config.qwenVoice, changed.qwenVoice)
    }

    @Test fun selectedCloudProviderRequiresCredentialsWithoutFallback() {
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(SpeechSettings(asr = AsrProvider.QWEN_FLASH), SpeechCredentials(), false)
        }
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(SpeechSettings(asr = AsrProvider.QWEN_FILE), SpeechCredentials(qwenAsr = "test"), false)
        }
    }

    @Test fun credentialsAreRedactedAndNeverPartOfSettings() {
        val secrets = SpeechCredentials(qwenAsr = "private-key")
        assertFalse(secrets.toString().contains("private-key"))
        val updated = secrets.withValue(SpeechCredentialField.DOUBAO_TTS, "other-key")
        assertEquals("private-key", updated.qwenAsr)
        assertEquals("other-key", updated.doubaoTts)
        assertFalse(json.encodeToString(SpeechSettings()).contains("private-key"))
    }
}
