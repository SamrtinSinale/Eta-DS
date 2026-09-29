# 语音识别与播报

Heta 的「设置 → 语音」分别配置语音识别（ASR）与语音播报（TTS）。云端识别由 Heta 自行录音并调用用户配置的服务，不依赖设备预装识别引擎，也不需要 Root。升级后仍默认使用系统识别，播报与助手自动播报默认关闭。

## 使用方式

- **聊天听写**：点击输入框麦克风开始，点击完成后把最终文字插入当前选区，可编辑后发送。预览文字不会覆盖已有草稿。系统服务提前返回时也等待用户确认。
- **助手浮窗**：识别结果直接发起提问。千问实时与豆包流式识别支持停顿后自动提交；千问短音频与文件转写需点击完成。
- **回答播报**：聊天回答可手动朗读。开启「助手自动播报回答」后，仅播报浮窗中语音提问的成功最终回答，不读取思考、工具日志或代码块。
- **取消与退出**：点击取消、开始新的录音、隐藏或关闭所属页面会终止音频任务；耳机断开、熄屏或播放失去音频焦点也会停止。因 GUI 操作隐藏浮窗后完成的任务只保留文字，不补播，不自动继续监听。

助手浮窗在实际录音、播放期间分别声明麦克风、媒体播放前台服务，并显示可停止的通知；结束音频操作即撤下通知。它不延长浮窗隐藏后的音频任务，也不提供后台持续监听或播放。

单次云端录音最多 60 秒。实时接口可显示识别预览，短音频与文件转写在录音完成后返回文字。切换服务不会自动复制凭据或替换已保存的其他服务配置；失败不会自动回退到另一家供应商。

## 服务配置

| 服务 | 模型与模式 | 配置 |
| --- | --- | --- |
| 系统 ASR | 设备已有识别服务 | 麦克风权限；支持时可手动下载语言包 |
| 千问实时 ASR | `qwen3-asr-flash-realtime` | 百炼地域、API Key |
| 千问短音频 ASR | `qwen3-asr-flash` | 百炼地域、API Key |
| 千问文件转写 | `qwen3-asr-flash-filetrans` | 百炼地域、API Key、自有 OSS |
| 豆包流式 ASR | 流式识别 2.0 | API Key；默认资源 ID `volc.seedasr.sauc.duration` |
| 千问 TTS | `qwen3-tts-flash` | 百炼地域、独立播报 API Key、音色；默认 Cherry |
| 豆包 TTS | 语音合成 2.0 | 独立播报 API Key、音色；默认 VV，资源 ID `seed-tts-2.0` |

千问可选择北京或新加坡，API Key 必须匹配所选地域。高级设置可填写服务根地址，包括业务空间专属域名；不要附加 `/api/v1` 或 `/compatible-mode/v1`。模型与协议绑定，不使用聊天模型列表推断语音能力。千问识别语言留空表示自动识别；指定语言时使用服务支持的语言代码。

豆包高级设置可修改资源 ID、服务根地址，或启用旧版 App ID＋Access Token 鉴权。旧版配置的 Access Token 填在对应的凭据输入框。自定义音色应填写所选模型支持的音色 ID，不会在 Heta 内创建或复刻音色。

表单需点击「保存」。识别测试与音色试听使用当前草稿调用正式链路，不自动保存，也不自动开始。测试和试听可能产生对应服务费用。

## 文件转写与 OSS

文件转写使用自有 OSS 私有对象，不使用百炼临时上传服务。录音存储页填写地域、标准地域 Endpoint、Bucket、目录前缀及专用 RAM 用户的 AccessKey。凭据应具备该目录下的 `PutObject`、`GetObject`、`DeleteObject` 权限，并允许将对象 ACL 设置为 private。

Heta 上传 WAV 后生成有效期 15 分钟的读取签名 URL，向百炼提交异步任务，每 1.5 秒查询一次，整个上传与转写流程最多等待 5 分钟。结果 JSON 下载不携带百炼认证头。

上传前先记录待清理对象；成功、失败及取消后尝试删除。删除失败保留记录，下次主进程启动时有界重试。上传结果不确定时，即使立即删除成功仍保留一次恢复清理记录，以处理取消与远端上传完成的竞争。恢复清理不删除当前进程仍在使用的录音。

建议为录音目录设置一天过期的 OSS 生命周期规则，处理设备不再启动、应用卸载或原凭据失效的情况。更换 OSS AccessKey 后，旧 Key 对应的待清理对象不会用新身份删除，需要在 OSS 控制台清理或由生命周期规则回收。取消本地等待不代表撤销已经提交的云端任务或费用。

## 实现边界

- `SpeechInputController` 统一管理 start、finish、cancel 和一次性结果交付。系统服务适配沿用 `EtaSpeechInput`；独立进程 `EtaRecognitionService` 仍只负责系统兼容桥接，不向其他应用开放云端凭据。
- `SpeechRecorder` 采集 16 kHz、单声道 PCM16。录音只暂存在内存中，文件接口封装 WAV；实时发送队列和响应解析均有限额，背压失败明确返回，不静默丢帧。
- 千问实时协议通过 `text + stash` 替换当前 item 预览，以 completed 为最终文字，等待 session.finished 后结束。豆包使用专用二进制 codec，支持 Gzip、头扩展和最终包标记，不能套用 JSON WebSocket。
- `SpeechPlaybackController` 负责合成与播放，`SpeechAudioLease` 协调本进程录音、播放和音频焦点。TTS 正文使用 GFM AST 提取可读文字，按句分块，使用 24 kHz PCM16 顺序播放。仅成功收到终止事件才把音频视为完整。
- `SpeechSettingsRepository` 隔离持久化；普通配置进入现有 DataStore，凭据用 Android Keystore 的 AES-GCM 加密后保存。凭据不进入 RemotePreferences、会话、日志或数据导出；语音配置目前也不纳入手动备份。不可解密的凭据会要求重新填写，不静默当作空配置。
- 设置页、聊天与浮窗复用相同控制器；网络与音频循环不进入 Composable、Hook 或 Agent loop，Runtime IPC 与归档格式保持兼容。

## 官方资料

- [千问 ASR HTTP API：短音频与异步文件转写](https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference)
- [千问实时 ASR 客户端事件](https://help.aliyun.com/zh/model-studio/qwen-asr-realtime-client-events)、[服务端事件](https://help.aliyun.com/zh/model-studio/qwen-asr-realtime-server-events)
- [豆包流式 ASR 协议](https://www.volcengine.com/docs/6561/1354869)
- [千问 TTS API](https://help.aliyun.com/zh/model-studio/qwen-tts-api)、[音频播放示例](https://help.aliyun.com/zh/model-studio/non-realtime-tts-user-guide)
- [豆包单向流式 TTS HTTP](https://docs.volcengine.com/docs/DoubaoVoice/unidirectional-streaming-text-to-speech-http?lang=zh)
- [OSS V4 请求头签名](https://help.aliyun.com/zh/oss/developer-reference/recommend-to-use-signature-version-4)、[URL 签名](https://help.aliyun.com/zh/oss/developer-reference/add-signatures-to-urls)
