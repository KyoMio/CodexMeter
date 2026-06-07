<p align="center">
  <img src="assets/brand/codexmeter-logo.png" alt="CodexMeter 标志" width="520">
</p>

<div align="center">

[English](README.md) | 简体中文

[![License: MIT](https://img.shields.io/badge/License-MIT-black.svg)](LICENSE) [![Android](https://img.shields.io/badge/Android-12%2B-3DDC84.svg)](https://developer.android.com/about/versions/12) [![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF.svg)](https://kotlinlang.org/) [![Status](https://img.shields.io/badge/status-Release-blue.svg)](https://github.com/KyoMio/CodexMeter/releases)

</div>

> 一个轻量的 AI 订阅配额 Android 看板。

CodexMeter 是一个非官方 Android 应用，用来在应用首页、桌面小组件和低打扰常驻通知里查看 Codex 及其它 AI 服务商的官方配额使用情况。

## 截图

| 首页 | 小组件 | 账号 | 设置 |
| --- | --- | --- | --- |
| ![首页](assets/screenshots/home.png) | ![小组件](assets/screenshots/widget.png) | ![账号](assets/screenshots/account.png) | ![设置](assets/screenshots/settings.png) |

## 功能特性

- 仅使用官方 usage / quota / balance API 数据。
- 额度窗口支持百分比 / 余额 / 次数 / 多模型分桶等展示。
- 余额型供应商自动按目标货币换算（保留原始货币）。
- 首页仪表盘提供供应商自适应的小时级使用趋势图。
- 可调整大小的 Jetpack Glance 桌面小组件。
- 常驻状态通知带供应商与账号标识，并支持按账号配额提醒。
- 「需要重新登录」检测并弹出通知。
- 支持保存多个账号并选择一个当前账号，可按账号开关配额提醒。
- 本地历史记录保留与清理。

## 后台刷新

CodexMeter 使用 WorkManager 在后台刷新额度（默认约 15 分钟一次），刷新不使用前台服务。熄屏后 Android 的 Doze（低电耗模式）会把后台任务批量推迟到「维护窗口」执行，因此实际刷新频率会明显长于你设置的间隔——这是系统的预期行为，并非 Bug。

**若未将 CodexMeter 加入电池优化白名单，后台刷新可能被延迟，你看到的额度可能不是最新的。** 此时请打开 App 手动刷新（首页下拉，或点击右上角刷新按钮）。为提升后台刷新可靠性，**设置 → 刷新** 提供了一键 **去允许** 快捷入口，引导系统将 CodexMeter 加入电池优化白名单——仅在应用尚未豁免时显示，且从不强制；即便授予，精确间隔仍受系统调度限制。

## 支持供应商

| 供应商 | 登录方式 |
| --- | --- |
| Codex | device-code 授权（外部浏览器） |
| Claude | OAuth 登录（应用内 WebView） |
| Antigravity | Google OAuth 登录（应用内 WebView） |
| DeepSeek | API Key |
| z.ai | API Key |
| MiniMax | API Key |
| Cursor | Cookie 采集（应用内 WebView） |
| Kimi | Cookie 采集（应用内 WebView） |

## 下载与安装

从 GitHub Releases 页面获取最新签名 APK：

**→ [下载最新版本](https://github.com/KyoMio/CodexMeter/releases/latest)**（[全部版本](https://github.com/KyoMio/CodexMeter/releases)）

1. 在 Android 12+ 设备上打开该 Release，下载 `.apk` 安装包。
2. 出现提示时，允许浏览器或文件管理器“安装未知应用”。
3. 打开下载好的 APK，点击 **安装**。
4. 启动 CodexMeter 并添加账号即可开始监控额度。

应用可在设置页检查 GitHub Releases 的新版本；它不会自动安装，也不申请存储权限。

## 隐私与安全

CodexMeter 会把敏感数据保存在设备本地。

- OAuth 会话使用 Android Keystore 支持的 AES-GCM 加密保存。
- raw token、Cookie、授权码、完整回调 query、原始 usage 响应和完整 `auth.json` 内容都不应被日志、界面、截图或仓库记录泄露。
- 不包含云同步、分析 SDK、远程日志、广告 SDK 或第三方配额代理。

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- StateFlow + ViewModel
- Room
- DataStore
- WorkManager
- Jetpack Glance
- OkHttp + kotlinx.serialization
- Android Keystore + AES-GCM
- 手写 `AppContainer`

## 快速开始

环境要求：

- 安装 Android SDK 的 Android Studio
- JDK 17
- Android 12+ 设备或模拟器

构建 debug APK：

```bash
./gradlew assembleDebug
```

运行单元测试：

```bash
./gradlew test
./gradlew :app:testDebugUnitTest
```

使用 adb 安装 debug APK：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 项目文档

- [产品需求](docs/PRD.md)
- [架构说明](docs/ARCHITECTURE.md)
- [实现规格](docs/SPEC.md)
- [设计系统](DESIGN.md)
- [开发规则](RULES.md)

## 致谢

部分供应商的用量 / 配额响应数据解析参考了 [steipete/CodexBar](https://github.com/steipete/CodexBar) 项目。

## 许可证

MIT License。详见 [LICENSE](LICENSE)。

## 免责声明

CodexMeter 是非官方项目，与 OpenAI 无关联。请使用你自己的账号，并遵守相关服务条款。
