# Kitty AI Desktop Pet

这个仓库现在按平台分区：

- `windows/`：Windows 桌面宠物，Python + Tkinter 实现。
- `android/`：Android 版本工程和调试 APK。
- `tools/`：资源生成和处理脚本。
- `launcher/`：启动器相关文件。

## Windows 版

双击根目录的 `启动桌宠.bat`，它会自动进入 `windows/` 并启动 `windows/app.py`。

Windows 版主要文件：

- `windows/app.py`：桌面端主程序。
- `windows/assets/`：桌宠图片和动画帧。
- `windows/api.txt`：DeepSeek API Key，仅本地使用。
- `windows/性格.md`：Kitty 核心人格，右键菜单可编辑。
- `windows/config.json`：模型与功能开关配置。
- `windows/conversation.json`：短期上下文，保留最近 40 条 message，约 20 轮对话。
- `windows/conversation_full.jsonl`：完整聊天归档，只追加，不放进 prompt。
- `windows/long_memory.json`：长期记忆。
- `windows/personality_delta.json`：性格/语气微调。
- `windows/pending_questions.json`：待问问题。

也可以在命令行运行：

```powershell
cd windows
python app.py
```

## Android 版

Android 工程在 `android/` 中：

- `android/app/`：Android 应用源码。
- `android/gradle/`、`android/gradlew.bat`：Gradle 构建文件。
- `android/Kitty-AI-Android-debug.apk`：当前调试包。

构建 Android：

```powershell
cd android
.\gradlew.bat assembleDebug
```

## 资源工具

`tools/build_asset.py` 和 `tools/extract_contact_sheet.py` 默认读写 Windows 桌面端资源：

- 输入主图：`windows/kitty 照片.jpg`
- 输出主图：`windows/assets/kitty.png`
- 输出动画：`windows/assets/animations/`
