# 视频字幕工具 · Android 社区版

一款**免费、开源**的 Android 视频字幕工具（社区开发版本：V1.8.4）。支持在设备上提取音频，使用 Deepgram Nova-3 或 AssemblyAI STT-3.7 识别英语对白，再经可配置的 AI 翻译、润色和中文字幕连贯性校验，导出单色 ASS 字幕。

## 功能
- Android 原生视频选择及音频提取、截取
- 多种语音识别服务商及兼容 OpenAI Chat Completions 的翻译模型
- 英文俚语与粗口语境翻译、字幕连贯性校验
- `原视频名.zh.ass`：白字黑边单文件导出
- AndroidKeyStore 加密保存各服务商 API Key
- V1.8.4：翻译进度本机保存、失败后续传、HTTP 429 有限重试（[详情](docs/V184_RESUME.md)）

## 构建和测试

需要 JDK 17、Android SDK 35、Gradle 8.11.1 和 Python 3。

```bash
python3 -m unittest discover -s tests -v
gradle :app:assembleDebug --no-daemon
```

**社区仓库中的构建仅使用临时 Debug 签名，不包含维护者的正式 APK 签名文件。** 从社区仓库编译的 APK 不能直接覆盖安装由私人签名证书签署的正式版本，且不得冒充正式发布包。

## 隐私与费用

应用在本地提取视频音频，然后将**音频**传给你选择的在线语音识别服务商，将识别后的**字幕文本和上下文**发送给你选择的翻译模型。用户视频画面不会作为原视频上传，但音频和字幕含义可能涉及隐私；请仔细阅读 [PRIVACY.md](PRIVACY.md)。软件免费，第三方 API 可能收费，用户需要自备密钥。

## 一起开发

欢迎提交 Issue 和 Pull Request，参阅 [CONTRIBUTING.md](CONTRIBUTING.md)、[SECURITY.md](SECURITY.md)、[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。提交时不要包含真实视频、API Key、私人日志或个人信息。

许可证：[Apache-2.0](LICENSE)。

## 源码和正式发布

此仓库是**独立、无旧提交历史的社区源码快照**。它不包含原私人开发仓库、原先的 GitHub Actions 历史、签名证书或正式发布权限。社区贡献经维护者审核后，才可能进入正式 APK 发布流程。
