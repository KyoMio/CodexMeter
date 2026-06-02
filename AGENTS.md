# CodexMeter Agent Guide

This repository is the Android project for **CodexMeter** (`codexbar-apk`): a self-use / small-scale sideloaded Android 12+ app for monitoring official Codex quota usage.

This file is the canonical operating guide for AI agents and human maintainers working in this repo. `CLAUDE.md` must stay aligned with this file.

## 1. Mandatory reading order

Before making any non-trivial change, read these files in order:

1. `docs/PRD.md` — product scope and non-goals.
2. `docs/ARCHITECTURE.md` — architecture, data, auth, refresh and security boundaries.
3. `docs/SPEC.md` — implementation contract, work order, acceptance and verification.
4. `docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md` — required when touching Codex login, OAuth session, account connection, auth UI, auth notifications or legacy `auth.json` migration.
5. `RULES.md` — development quality, maintainability, testing and safety rules.
6. `DESIGN.md` — required for any UI / Widget / Notification / UX work.
7. `AGENTS.md` — this guide.

If these documents conflict, stop and update the documents or ask for a decision. Do not guess.

## 2. Product baseline

- Display name: `CodexMeter`.
- Project directory: `codexbar-apk`.
- Package name: `com.kmnexus.codexmeter`.
- Debug package name: `com.kmnexus.codexmeter.debug`.
- Target platform: Android 12+.
- Distribution: self-use / small-scale sideloaded APK.
- MVP provider: Codex.
- Future provider expansion: Gemini / Claude-style quota providers may be added later.

MVP core surfaces:

- App dashboard.
- Resizable home-screen Widget.
- Low-noise persistent notification.

## 3. Hard product boundaries

Do not implement these unless the PRD is explicitly revised:

- No public app-store distribution.
- No Android 12- support.
- No tablet / foldable / Wear OS support in MVP.
- No lock-screen quota surface.
- No web-page parsing.
- No token / cost local estimation.
- No Cookie Header manual input.
- No access-token / refresh-token manual input fields.
- No embedded WebView / manual-credential Codex login flow; Codex verification uses the provider device-code external-browser handoff.
- No cloud sync, export report, third-party proxy, remote logging or analytics.
- No real-time multi-account monitoring in MVP; periodic background refresh may refresh all Active accounts with bounded concurrency.

Allowed quota data sources only:

- Codex / OpenAI OAuth session obtained through the Hermes-aligned device-code external-browser login flow.
- Existing saved OAuth sessions, including sessions originally imported from Codex CLI `auth.json` before this migration; keep them refreshable and do not auto-delete them.
- Official usage / quota API data returned by the provider.

## 4. Architecture baseline

Use the architecture in `docs/ARCHITECTURE.md`.

Technology baseline:

- Kotlin.
- Jetpack Compose + Material 3.
- StateFlow + ViewModel.
- Room for business history.
- DataStore for preferences.
- Android Keystore + AES-GCM for sensitive session payloads.
- OkHttp + kotlinx.serialization for network and DTO parsing.
- WorkManager for background refresh.
- Jetpack Glance for Widget.
- Hand-written `AppContainer` for dependency assembly.

MVP must not introduce:

- Hilt.
- Retrofit.
- Ktor Client.
- Foreground Service.
- Dynamic plugin system.
- Multi-module Gradle architecture.
- Remote analytics / crash upload / ad SDK.

## 5. Package boundaries

Follow the package map from `docs/ARCHITECTURE.md`:

```text
com.kmnexus.codexmeter
├── app
├── core
├── data
├── domain
├── notification
├── providers
├── refresh
├── ui
└── widget
```

Rules:

- `ui` must not directly access Room, DataStore, OkHttp, Keystore or provider-private sessions.
- `widget` must not directly access network, providers or decrypted sessions.
- WorkManager glue in `refresh` must go through `RefreshCoordinator` and must not directly create notifications.
- Provider-private token, DTO and endpoint details must stay under `providers.<providerId>`.
- Common UI / Widget / Notification state must derive from `CurrentQuotaState` or its clipped read models.

## 6. Security red lines

Never commit, log, print, show in UI, place in screenshots, write into fixtures or include in diagnostics:

- access token.
- refresh token.
- id token.
- OAuth authorization code.
- Cookie.
- complete `auth.json`.
- complete OAuth callback query string.
- raw usage API response body.

Rules:

- Legacy raw `auth.json` content must never be persisted and remains relevant only for redaction / diagnostics handling.
- Do not add `auth.json` import UI, file picker, paste-JSON flow or manual credential path.
- Do not add single-token input fields.
- Do not add Cookie Header input fields.
- Diagnostics must be redacted and safe to copy.
- Release builds must not print request / response bodies.

## 7. UI / UX baseline

Follow `DESIGN.md`.

Confirmed design direction:

- Air Glass Dashboard.
- Light premium tool style.
- Restrained dashboard, not a dense analytics suite.
- Black / white / gray + blue primary interaction.
- Green / orange / red only for semantic status.
- Light glass-like surface treatment, without sacrificing readability.

Confirmed navigation:

1. Home.
2. Account.
3. Settings.

Important UI rules:

- Bottom tabs must use real SVG / Vector icons.
- Account management lives in the Account tab.
- Settings must not duplicate account-management cards.
- Home must not contain an ambiguous top-right action button.
- Account avatars use circular solid-color initials.
- Account switching lives in the Account tab; Home only shows the current-account summary.
- Last scrollable content must be able to scroll above the bottom tab bar.

## 8. Testing policy

Use strict TDD for production behavior changes.

Mandatory test-first areas:

- Provider DTO mappers.
- Codex device-code client / login use case.
- device-code attempt cancellation, expiry and stale-result handling.
- token exchange / refresh error mapping.
- session envelope migration.
- `QuotaError` mapping.
- `RefreshCoordinator` degraded-state behavior.
- last-known-good snapshot protection.
- `AlertPolicy` and alert de-duplication.
- redaction / diagnostics.
- Room migrations.

TDD loop:

1. Write a failing test.
2. Run it and verify it fails for the expected reason.
3. Write the minimal code to pass.
4. Run the specific test and then the relevant suite.
5. Refactor only while tests stay green.

UI visuals, Widget visuals, notification appearance and true external-browser device-code login behavior may use manual acceptance first, but architecture boundaries still apply.

## 9. Build and verification commands

After Android project initialization, prefer:

```bash
./gradlew assembleDebug
./gradlew test
./gradlew :app:testDebugUnitTest
```

For release work:

```bash
./gradlew assembleRelease
```

For design docs:

```bash
npx -y @google/design.md lint DESIGN.md
```

If Android SDK / JDK / Gradle wrapper is not yet available, state that verification is limited. Do not claim an Android build passed when it was not run.

## 10. Git rules

- Keep changes small and grouped by intent.
- Use conventional commits.
- Separate documentation, refactor and feature changes when practical.
- Do not commit generated build outputs, local credentials, keystores, local.properties, secrets files or IDE noise.
- Before commit, run the strongest available validation for the change type.
- If validation cannot run, record why in the final summary.

Commit message examples:

- `docs: add project agent guides`
- `feat: add codex device code login client`
- `test: cover quota alert policy`
- `fix: keep last quota snapshot on refresh failure`

## 11. Documentation synchronization

Update docs when behavior changes:

- Product scope changes → `docs/PRD.md`.
- Architecture changes → `docs/ARCHITECTURE.md`.
- Implementation contract / work order changes → `docs/SPEC.md`.
- Development rules changes → `RULES.md`.
- UI / interaction changes → `DESIGN.md`.
- Agent workflow changes → `AGENTS.md` and `CLAUDE.md`.

Do not leave code and docs knowingly inconsistent.

## 12. Agent working style

When acting as an implementation agent:

- Make reversible patches.
- Prefer targeted edits over broad rewrites.
- Do not dump large code blocks into chat; write files.
- Report only result, paths, validation and remaining risk.
- If a task touches secrets or destructive data deletion, stop for human confirmation.
- If the repo is dirty with unrelated changes, inspect and preserve them; do not overwrite.

## 13. Coding behavior guardrails

These rules are adapted from `multica-ai/andrej-karpathy-skills/CLAUDE.md` and apply to coding agents in this repo.

### 13.1 Think before coding

- State assumptions explicitly when they affect implementation.
- If multiple interpretations exist, surface the options instead of silently choosing.
- If a simpler approach exists, say so and prefer it when it satisfies the task.
- If the requirement is unclear enough to change the code path, stop and ask.

### 13.2 Simplicity first

- Write the minimum code that solves the requested problem.
- Do not add speculative features, generic abstractions or configurability.
- Do not create single-use abstractions unless they materially improve readability.
- If a solution is clearly overcomplicated, simplify before moving on.

### 13.3 Surgical changes

- Touch only files and lines needed for the task.
- Do not refactor adjacent code, comments or formatting just because it looks improvable.
- Match existing project style unless it violates this guide or `RULES.md`.
- Remove only dead imports / variables / functions introduced by the current change; mention unrelated dead code instead of deleting it.
- Every changed line should trace back to the user request or the active plan task.

### 13.4 Goal-driven execution

- Convert work into verifiable goals before implementing.
- For bugs, write or identify a failing check that reproduces the issue before fixing.
- For features, define the focused test or acceptance check before implementation.
- For multi-step tasks, keep a brief plan where each step has a verification command or check.
- Continue until the goal is verified, or report the exact blocked check.

## 14. Definition of done

A task is done only when:

- It satisfies the relevant PRD / user request.
- It respects `ARCHITECTURE.md`, `RULES.md` and `DESIGN.md`.
- Sensitive data has no leakage path.
- Tests or manual checks appropriate to the change have been run.
- Documentation has been updated if behavior or UI changed.
- The change is small enough for a human to review and maintain.
