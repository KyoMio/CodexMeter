<p align="center">
  <img src="assets/brand/codexmeter-logo.png" alt="CodexMeter logo" width="520">
</p>



<div align="center">

English | [简体中文](README.zh-CN.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-black.svg)](LICENSE) [![Android](https://img.shields.io/badge/Android-12%2B-3DDC84.svg)](https://developer.android.com/about/versions/12) [![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF.svg)](https://kotlinlang.org/) [![Status](https://img.shields.io/badge/status-Release-blue.svg)](https://github.com/KyoMio/CodexMeter/releases)

</div>

> A lightweight Android quota dashboard for Codex and other AI providers.

CodexMeter is an unofficial Android app for keeping an eye on official quota usage across Codex and other AI providers, from the home screen, the app dashboard, and a low-noise persistent notification.

## Screenshots

| Home | Widget | Account | Settings |
| --- | --- | --- | --- |
| ![Home](assets/screenshots/home.png) | ![Home](assets/screenshots/widget.png) | ![Home](assets/screenshots/account.png) | ![Home](assets/screenshots/settings.png) |

## Features

- Official usage / quota / balance API data only.
- Quota windows shown as percentage, balance, usage count, or per-model buckets.
- Automatic currency conversion for balance providers (keeps the native amount).
- Home dashboard with a provider-adaptive hourly usage trend chart.
- Resizable Jetpack Glance home-screen widget.
- Persistent status notification labeled with the provider and account, plus per-account quota alerts.
- "Needs re-login" detection that notifies you.
- Multiple saved accounts with a single current account, and per-account quota alert toggles.
- Local history retention and history clearing.

## Supported Providers

| Provider | Sign-in |
| --- | --- |
| Codex | Device-code authorization (external browser) |
| Claude | OAuth sign-in (in-app WebView) |
| Antigravity | Google OAuth sign-in (in-app WebView) |
| DeepSeek | API key |
| z.ai | API key |
| MiniMax | API key |
| Cursor | Cookie capture (in-app WebView) |
| Kimi | Cookie capture (in-app WebView) |

## Download & Install

Grab the latest signed APK from the GitHub Releases page:

**→ [Download the latest release](https://github.com/KyoMio/CodexMeter/releases/latest)** ([all releases](https://github.com/KyoMio/CodexMeter/releases))

1. On your Android 12+ device, open the release and download the `.apk` asset.
2. When prompted, allow your browser or file manager to "install unknown apps".
3. Open the downloaded APK and tap **Install**.
4. Launch CodexMeter and add an account to start tracking quota.

The app checks GitHub Releases for newer versions from the Settings screen; it never auto-installs and requests no storage permission.

## Background Refresh

CodexMeter refreshes quota in the background with WorkManager (default every ~15 minutes), and never runs a foreground service for refresh. When the screen is off, Android's Doze mode batches background work into maintenance windows, so the actual cadence stretches well beyond your configured interval — this is expected OS behavior, not a bug.

To improve reliability (especially on OEM ROMs that aggressively kill background apps), Settings → Refresh shows an optional **Allow** action that asks the system to exempt CodexMeter from battery optimization. It is never forced and only appears when the app is not yet exempt; even when granted, exact intervals are still subject to system scheduling.

## Privacy

CodexMeter keeps sensitive data on the device.

- OAuth sessions are encrypted with Android Keystore-backed AES-GCM.
- Raw tokens, cookies, auth codes, full callback queries, raw usage responses, and complete `auth.json` content must never be logged, shown, or committed.
- There is no cloud sync, analytics SDK, remote logging, ad SDK, or third-party quota proxy.

## Tech Stack

- Kotlin
- Jetpack Compose + Material 3
- StateFlow + ViewModel
- Room
- DataStore
- WorkManager
- Jetpack Glance
- OkHttp + kotlinx.serialization
- Android Keystore + AES-GCM
- Hand-written `AppContainer`

## Getting Started

Requirements:

- Android Studio with Android SDK support
- JDK 17
- Android 12+ device or emulator

Build a debug APK:

```bash
./gradlew assembleDebug
```

Run unit tests:

```bash
./gradlew test
./gradlew :app:testDebugUnitTest
```

Install the debug APK with adb:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Documentation

- [Product Requirements](docs/PRD.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Implementation Spec](docs/SPEC.md)
- [Design System](DESIGN.md)
- [Development Rules](RULES.md)

## Acknowledgements

Parsing of some providers' usage/quota responses references the [steipete/CodexBar](https://github.com/steipete/CodexBar) project.

## License

MIT License. See [LICENSE](LICENSE).

## Disclaimer

CodexMeter is an unofficial project and is not affiliated with OpenAI. Use it with your own account and follow the relevant service terms.
