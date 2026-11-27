# 视频字幕工具 · Windows PC 测试版

面向 Windows 10 / 11 的桌面字幕工作室。暖象牙白 × 森林绿、固定宽度桌面布局、方正按钮、**不提供视频画面预览**。

## 功能

- 选择本地 MP4/MKV/MOV/AVI/MP3/WAV 等文件；本机 FFmpeg 转换为 16 kHz 单声道 WAV，原始视频画面**不会上传**。
- Deepgram Nova-3 / AssemblyAI STT-3.7 云端英文识别；DeepSeek/GLM/Kimi/硅基流动/火山方舟/百炼/自定义 HTTPS Chat Completions 翻译接口。
- 同一模型快速（初译1轮）/标准（初译+润色+校验3轮）/高质量（加第四轮复核4轮）。
- 复用 Android 正式版的 **110 条中文释义对白词库**，仅按命中的英文短语提示上下文，不会机械替换台词。
- 识别阶段之后的翻译批次可暂停/续传，清除断点后按钮消失；本地字幕质量报告；英文/中文/时间码文字列表逐句修改。
- 仅输出 **原视频名.zh.ass**，单色白字黑边、62 字号；不输出 TXT、SRT、多份报告。
- API 返回的真实翻译 Token 数及按用户填写的输入/输出单价估算美元费用；语音识别费和未上报用量不包括在估算中。
- 可选把 API Key 用 Windows 当前账号的 DPAPI 加密保存于 `%LOCALAPPDATA%\SubtitleToolPC`；未点击“安全保存 API”之前不会保存任何密钥。断点、运行日志和输出文件中不保存密钥。

## 使用

**方法 A（推荐）**：下载 GitHub Actions 的 **SubtitleTool-PC-Windows** 构建产物，解压运行 `SubtitleTool-PC.exe`，无需安装 Python。该 `.exe` 暂为 Windows 测试包，不是签名的正式安装程序；首次运行可能出现 Windows SmartScreen 提示。

**方法 B（源代码）**：在 Windows 10/11 安装 Python 3.11 或 3.12，双击 `run_windows.bat` 自动安装依赖并启动。

填写识别 API Key、翻译平台和模型，选择视频，点击 **开始生成字幕**。只有原始音频 WAV 会发送到选定的识别平台，识别后的英文和上下文会发往翻译模型。第三方 API 可能收费。

本项目无视频预览。需要使用模型/接口真实测译文质量；无法保证语音识别能够捕获原音中全部喘息或低声对白。**测试版尚未经过用户 PC 真机验收。**

## 断点与编辑

断点放在 `%LOCALAPPDATA%\SubtitleToolPC\checkpoint.json`，包含已经识别的英文、已完成的中文译文、当前轮次和累计的 Token，不包含 API Key。相同视频、路径、大小、修改时间、识别平台、翻译平台、模型及质量模式才能恢复。不同视频或模式不会误恢复。

输出文件在你选择的目录，默认是视频所在目录。成功后可点击 **字幕文字编辑**，修改中文时不改变英语原文和时间码，自动更新 ASS 文件。重新打开程序仍可编辑上一次保存在本机的字幕。

## 安全和限制

- 安装包不会收集 API Key，也不通过个人服务器中转请求。
- 使用 HTTP 429 显式限流时才会重试；其他网络故障/超时可能已经在提供商端扣费，不自动重试。
- Windows EXE 内捆绑开源 FFmpeg（imageio-ffmpeg）；可提供独立许可证及来源信息。请遵守对应发行条款。
- 已完成的字幕导出成功才清除断点。如果使用移动硬盘且源文件路径/状态变化，可能无法复用原断点。
- PC 项目与现有 Android 独立，不影响 Android 原签名配置；未得到额外授权前不发布新的正式 PC 安装器。

## 本机自动测试

```console
python -m pip install -r requirements.txt
python -m unittest discover -s tests -v
```
