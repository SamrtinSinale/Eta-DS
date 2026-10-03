package io.github.mangi.eta.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.DoubaoSpeechConfig
import io.github.mangi.eta.data.model.QwenSpeechConfig
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.data.model.SpeechRegion
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.components.EtaDropdownPreference
import io.github.mangi.eta.ui.components.EtaOverlayDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AsrProvider.label(): String = stringResource(
    when (this) {
        AsrProvider.SYSTEM -> R.string.speech_asr_system
        AsrProvider.QWEN_REALTIME -> R.string.speech_asr_qwen_realtime
        AsrProvider.QWEN_FLASH -> R.string.speech_asr_qwen_flash
        AsrProvider.QWEN_FILE -> R.string.speech_asr_qwen_file
        AsrProvider.DOUBAO -> R.string.speech_asr_doubao
    },
)

@Composable
internal fun TtsProvider.label(): String = stringResource(
    when (this) {
        TtsProvider.NONE -> R.string.speech_tts_none
        TtsProvider.QWEN -> R.string.speech_tts_qwen
        TtsProvider.DOUBAO -> R.string.speech_tts_doubao
    },
)

/** 卡片下方的辅助说明，与分组标题使用同一套留白。 */
@Composable
internal fun SpeechNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

/** 卡片内的表单字段块，与 Provider 配置页使用同一套内边距与字段间距。 */
@Composable
internal fun SpeechFieldColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun SpeechField(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    var visible by rememberSaveable(label) { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onChange,
        label = label,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = stringResource(
                            if (visible) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
                        ),
                    )
                }
            }
        } else {
            null
        },
    )
}

/** 识别与播报共用的服务商连接配置：地域或旧版鉴权、凭据与高级地址设置。 */
@Composable
internal fun SpeechConnectionSection(store: SpeechSettingsStore, synthesis: Boolean) {
    val settings = store.settings
    val qwen = if (synthesis) settings.tts == TtsProvider.QWEN else settings.asr != AsrProvider.DOUBAO
    val keyField = when {
        qwen && synthesis -> SpeechCredentialField.QWEN_TTS
        qwen -> SpeechCredentialField.QWEN_ASR
        synthesis -> SpeechCredentialField.DOUBAO_TTS
        else -> SpeechCredentialField.DOUBAO_ASR
    }
    val secret = when (keyField) {
        SpeechCredentialField.QWEN_TTS -> store.credentials.qwenTts
        SpeechCredentialField.QWEN_ASR -> store.credentials.qwenAsr
        SpeechCredentialField.DOUBAO_TTS -> store.credentials.doubaoTts
        SpeechCredentialField.DOUBAO_ASR -> store.credentials.doubaoAsr
        else -> error("Not a provider credential")
    }
    var advanced by rememberSaveable(keyField) { mutableStateOf(false) }

    Column {
        EtaPreferenceGroupTitle(stringResource(R.string.speech_group_connection))
        EtaPreferenceGroup {
            if (qwen) {
                val config = if (synthesis) settings.qwenTts else settings.qwenAsr
                val change: (QwenSpeechConfig) -> Unit = {
                    store.edit(if (synthesis) settings.copy(qwenTts = it) else settings.copy(qwenAsr = it))
                }
                EtaOverlayDropdownPreference(
                    title = stringResource(R.string.speech_region),
                    items = listOf(
                        stringResource(R.string.speech_region_beijing),
                        stringResource(R.string.speech_region_singapore),
                    ),
                    selectedIndex = config.region.ordinal,
                    onSelectedIndexChange = { index ->
                        SpeechRegion.entries.getOrNull(index)?.let { change(config.copy(region = it)) }
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
                SpeechFieldColumn {
                    SpeechField(
                        label = "API Key",
                        value = secret,
                        secret = true,
                        onChange = { store.credential(keyField, it.trim()) },
                    )
                }
            } else {
                val config = if (synthesis) settings.doubaoTts else settings.doubaoAsr
                val change: (DoubaoSpeechConfig) -> Unit = {
                    store.edit(if (synthesis) settings.copy(doubaoTts = it) else settings.copy(doubaoAsr = it))
                }
                SpeechFieldColumn {
                    SpeechField(
                        label = stringResource(
                            if (config.legacyAuth) R.string.speech_access_token else R.string.speech_api_key,
                        ),
                        value = secret,
                        secret = true,
                        onChange = { store.credential(keyField, it.trim()) },
                    )
                    if (config.legacyAuth) {
                        SpeechField(
                            label = stringResource(R.string.speech_app_id),
                            value = config.appId,
                            onChange = { change(config.copy(appId = it.trim())) },
                        )
                    }
                }
            }

            EtaPreferenceDivider(hasLeading = false)
            val chevronRotation by animateFloatAsState(if (advanced) 180f else 0f)
            EtaPreference(
                title = stringResource(R.string.speech_advanced),
                endActions = {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(
                            if (advanced) R.string.speech_collapse else R.string.speech_expand,
                        ),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.rotate(chevronRotation),
                    )
                },
                onClick = { advanced = !advanced },
            )
            AnimatedVisibility(visible = advanced) {
                Column {
                    EtaPreferenceDivider(hasLeading = false)
                    if (qwen) {
                        val config = if (synthesis) settings.qwenTts else settings.qwenAsr
                        SpeechFieldColumn {
                            SpeechField(
                                label = stringResource(R.string.speech_qwen_base_url_hint),
                                value = config.baseUrl,
                                onChange = { value ->
                                    store.edit(
                                        if (synthesis) settings.copy(qwenTts = config.copy(baseUrl = value.trim()))
                                        else settings.copy(qwenAsr = config.copy(baseUrl = value.trim())),
                                    )
                                },
                            )
                        }
                    } else {
                        val config = if (synthesis) settings.doubaoTts else settings.doubaoAsr
                        EtaSwitchPreference(
                            title = stringResource(R.string.speech_legacy_auth),
                            checked = config.legacyAuth,
                            onCheckedChange = { enabled ->
                                store.edit(
                                    if (synthesis) settings.copy(doubaoTts = config.copy(legacyAuth = enabled))
                                    else settings.copy(doubaoAsr = config.copy(legacyAuth = enabled)),
                                )
                            },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        SpeechFieldColumn {
                            SpeechField(
                                label = stringResource(R.string.speech_base_url),
                                value = config.baseUrl,
                                onChange = { value ->
                                    store.edit(
                                        if (synthesis) settings.copy(doubaoTts = config.copy(baseUrl = value.trim()))
                                        else settings.copy(doubaoAsr = config.copy(baseUrl = value.trim())),
                                    )
                                },
                            )
                            SpeechField(
                                label = stringResource(R.string.speech_resource_id_hint),
                                value = config.resourceId,
                                onChange = { value ->
                                    store.edit(
                                        if (synthesis) settings.copy(doubaoTts = config.copy(resourceId = value.trim()))
                                        else settings.copy(doubaoAsr = config.copy(resourceId = value.trim())),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        if (advanced) {
            SpeechNote(
                stringResource(
                    when {
                        qwen -> R.string.speech_qwen_base_url_note
                        synthesis -> R.string.speech_doubao_tts_note
                        else -> R.string.speech_doubao_asr_note
                    },
                ),
            )
        }
    }
}

/** 播报音色：预设音色或自定义音色 ID。 */
@Composable
internal fun SpeechVoiceSection(store: SpeechSettingsStore) {
    val settings = store.settings
    val qwen = settings.tts == TtsProvider.QWEN
    val voice = if (qwen) settings.qwenVoice else settings.doubaoVoice
    val presets = SpeechVoicePresets.forProvider(settings.tts)
    val presetIndex = presets.indexOfFirst { it.id == voice }
    var editingCustom by rememberSaveable(settings.tts) { mutableStateOf(false) }
    val custom = editingCustom || presetIndex < 0
    val change: (String) -> Unit = {
        store.edit(if (qwen) settings.copy(qwenVoice = it) else settings.copy(doubaoVoice = it))
    }

    Column {
        EtaPreferenceGroupTitle(stringResource(R.string.speech_group_voice))
        EtaPreferenceGroup {
            EtaDropdownPreference(
                title = stringResource(R.string.speech_voice),
                items = presets.map { preset ->
                    DropdownItem(text = stringResource(preset.name), summary = stringResource(preset.summary))
                } + DropdownItem(text = stringResource(R.string.speech_voice_custom)),
                selectedIndex = if (custom) presets.size else presetIndex,
                useWindow = false,
                onSelectedIndexChange = { index ->
                    val preset = presets.getOrNull(index)
                    editingCustom = preset == null
                    if (preset != null) change(preset.id) else if (!custom) change("")
                },
            )
            AnimatedVisibility(visible = custom) {
                Column {
                    EtaPreferenceDivider(hasLeading = false)
                    SpeechFieldColumn {
                        SpeechField(
                            label = stringResource(R.string.speech_voice_id),
                            value = voice,
                            onChange = { change(it.trim()) },
                        )
                    }
                }
            }
        }
        SpeechNote(
            stringResource(
                if (qwen) R.string.speech_qwen_voice_note else R.string.speech_doubao_voice_note,
            ),
        )
    }
}

/** 页面底部的保存主按钮与结果反馈，对齐 Provider 配置页的操作分层。 */
@Composable
internal fun SpeechSaveBlock(store: SpeechSettingsStore, validate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EtaTextButton(
            text = stringResource(if (store.saving) R.string.speech_saving else R.string.speech_save),
            enabled = store.loaded && !store.saving,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = { store.save(validate) },
        )
        store.message?.let { message ->
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote2,
                color = if (store.messageIsError) StatusError else StatusSuccess,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
