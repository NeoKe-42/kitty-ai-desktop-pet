# Kitty AI Android

这是 Kitty AI 的 Android 原生版本，Windows 版仍保留在项目根目录。

## 已实现

- 可拖动的系统悬浮桌宠
- 待机、挥手、跳跃、奔跑、等待和失败动画
- 点击悬浮 Kitty 打开聊天
- DeepSeek `deepseek-v4-flash` 聊天
- 最近 10 轮对话本地保存
- 性格编辑、清空记录、开机恢复选项
- API 密钥通过 Android Keystore 加密保存
- 前台服务通知中可关闭桌宠

## 构建与安装

1. 安装最新版稳定版 Android Studio。
2. 用 Android Studio 打开本目录 `android`。
3. 等待 Gradle 和 Android SDK 36 同步完成。
4. 连接已开启 USB 调试的 Android 手机。
5. 点击 Run，或执行 **Build > Build APK(s)**。

首次开启悬浮桌宠时，需要允许“显示在其他应用上层”。部分国产手机还需要在系统设置中允许后台运行、自启动，并将电池策略设为“不限制”。

## 目录说明

- `app/src/main/java/com/kittyai/pet/MainActivity.java`：聊天和设置
- `app/src/main/java/com/kittyai/pet/OverlayPetService.java`：悬浮桌宠
- `app/src/main/java/com/kittyai/pet/DeepSeekClient.java`：DeepSeek 请求
- `app/src/main/assets/animations/`：从 Windows 版复用的动画帧

## 安全说明

此版本适合安装在自己的手机上。若以后公开发布，建议将 DeepSeek 请求迁移到自己的服务器，避免任何客户端应用直接持有长期 API 密钥。
