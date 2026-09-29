# Eta 发布流程

## 配置签名 Secrets

发布证书和密码不得提交到 Git。首次使用前，在仓库的
`Settings > Secrets and variables > Actions` 中添加：

- `ETA_RELEASE_KEYSTORE_BASE64`：发布证书的 Base64 文本
- `ETA_RELEASE_STORE_PASSWORD`：KeyStore 密码
- `ETA_RELEASE_KEY_ALIAS`：Key alias
- `ETA_RELEASE_KEY_PASSWORD`：Key 密码

macOS 可以用下面的命令复制证书的 Base64 文本：

```bash
base64 < /path/to/Eta-release.jks | tr -d '\n' | pbcopy
```

也可以使用 GitHub CLI。密码类 Secret 不要直接写在命令参数中，运行命令后按提示输入：

```bash
base64 < /path/to/Eta-release.jks | gh secret set ETA_RELEASE_KEYSTORE_BASE64
gh secret set ETA_RELEASE_STORE_PASSWORD
gh secret set ETA_RELEASE_KEY_ALIAS
gh secret set ETA_RELEASE_KEY_PASSWORD
```

## 构建

`Eta Release Build` 工作流**只构建 Release（签名）APK**，不再构建 Debug APK。
三种触发方式：

| 触发方式 | 版本号来源 | 是否发布 GitHub Release |
| --- | --- | --- |
| 推送到 `main` | 仓库中的 `versionName` | 否，只上传 Artifact |
| 推送 `v*` 标签 | 标签名（`v3.0.6` → `3.0.6`） | 否，只上传 Artifact |
| 手动 `workflow_dispatch` | 手动填写的 `version` | 由 `publish_release` 决定 |

每次构建都会先用 `apksigner` 校验签名，再把 APK 作为 Artifact
`eta-<版本号>-release-apk` 保存 14 天。

## 手动构建与发布

在 `Actions > Eta Release Build > Run workflow` 中填写：

- **version**：版本号，可以写 `3.0.6`，也可以只写 `306`（会自动展开成 `3.0.6`）。
  留空则使用 `app/build.gradle.kts` 里的 `versionName`。
- **version_code**：`versionCode`，纯数字。留空时自动使用 `<今天 yyyyMMdd>01`
  （例如 `2026092901`）。它必须大于上一次发布的 `versionCode`，否则 Android
  会拒绝覆盖安装，工作流会给出警告。
- **publish_release**：是否创建 GitHub Release 并上传已签名 APK。默认关闭，
  此时只在 Artifact 中产出 APK。
- **release_tag**：Release 标签，例如 `v3.0.6`。留空时使用 `v<版本号>`。

版本号通过环境变量 `ETA_VERSION_NAME` / `ETA_VERSION_CODE` 传给 Gradle，
**不会**回写 `app/build.gradle.kts`。如果希望仓库里的默认版本号也跟着更新，
发布后手动提交一次：

```bash
# 把 3.0.6 写入 app/build.gradle.kts 的 versionName / versionCode
git commit -am "chore(release): 准备发布 3.0.6"
git push origin main
```

## 标签发布

如果更习惯用标签发布，可以只构建不发布：

```bash
git tag v3.0.6
git push origin v3.0.6
```

标签推送只会生成 Artifact。要真正创建 GitHub Release，仍需在
`Eta Release Build` 中手动运行一次并打开 `publish_release`，
或在工作流跑完后从 `Artifacts` 下载 APK 手动创建 Release。
