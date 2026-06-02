# Claude Code Guide for CodexMeter

This file gives Claude Code / Claude-style coding agents the project-specific rules for `CodexMeter`.

`AGENTS.md` is the canonical agent guide. If there is a conflict, follow `AGENTS.md` and update this file to match.

## Communication language

Always communicate with the user in Chinese (始终使用中文沟通).

## Read first

Before editing code or docs, read in this order:

1. `AGENTS.md`
2. `docs/PRD.md`
3. `docs/ARCHITECTURE.md`
4. `docs/SPEC.md`
5. `docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md` when touching Codex login, OAuth session, account connection, auth UI, auth notifications or legacy `auth.json` migration
6. `RULES.md`
7. `DESIGN.md` if touching UI / Widget / Notification / UX

Do not rely on memory of previous sessions. Read the repo files.

## Project summary

- Product: `CodexMeter`
- Repo directory: `codexbar-apk`
- Android package: `com.kmnexus.codexmeter`
- Platform: Android 12+
- MVP purpose: show official Codex quota usage through app dashboard, resizable Widget and persistent notification.
- MVP provider: Codex only, with future provider structure preserved.

## Non-negotiable boundaries

Never add:

- Web scraping / page parsing.
- Local token or cost estimation.
- Manual token fields.
- Cookie Header input.
- Embedded WebView or manual-credential flow **for Codex**; Codex verification uses the provider device-code external-browser handoff. (Non-Codex providers may use an embedded WebView for cookie capture / OAuth interception, and Antigravity may use a 127.0.0.1 loopback server — see `docs/ARCHITECTURE.md` §11.3 multi-provider exemption.)
- New `auth.json` file import, paste-JSON import or manual credential path.
- Cloud sync or third-party proxy.
- Remote analytics / crash upload.
- Foreground service for quota refresh.
- Real-time multi-account monitoring; periodic background refresh may refresh all Active accounts with bounded concurrency.
- Hilt / Retrofit / Ktor / dynamic plugin framework.

Never expose:

- access token.
- refresh token.
- id token.
- auth code.
- Cookie.
- complete `auth.json`.
- complete OAuth callback query.
- raw API response body.

Device-code migration rule: new Codex account connection uses the Hermes-aligned device-code external-browser flow from `docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md`. Existing saved OAuth sessions, including sessions originally imported from `auth.json`, remain valid and must not be auto-deleted.

## Architecture reminders

Use the single-module app architecture from `docs/ARCHITECTURE.md`.

Allowed stack:

- Kotlin.
- Jetpack Compose + Material 3.
- StateFlow + ViewModel.
- Room.
- DataStore.
- Android Keystore + AES-GCM.
- OkHttp + kotlinx.serialization.
- WorkManager.
- Jetpack Glance.
- Hand-written `AppContainer`.

Layer rules:

- UI emits intents and renders state only.
- UI must not directly access Room, DataStore, network, Keystore or provider-private payloads.
- Providers own private DTOs, endpoints, token refresh and private session payloads.
- Public layers consume common domain models only.
- Widget and notification state must derive from shared quota state, not recompute logic separately.

## UI rules

Follow `DESIGN.md` exactly.

Confirmed IA:

1. Home
2. Account
3. Settings

Important confirmed choices:

- Air Glass Dashboard light tool style.
- Bottom tabs use real vector / SVG-style icons.
- Account management is an Account tab responsibility.
- Settings must not duplicate account-management cards.
- Home has no ambiguous top-right settings button.
- Account avatars are circular solid-color initials.
- Account switching lives in the Account tab; Home only shows the current-account summary.
- Scrollable content must not be hidden behind the bottom tab bar.

## TDD and validation

Use test-first development for production logic.

Must test first:

- Provider DTO mapping.
- Codex device-code client / login use case.
- device-code attempt cancellation, expiry and stale-result handling.
- token exchange / refresh error mapping.
- session envelope migration.
- `QuotaError` mapping.
- refresh failure downgrade behavior.
- last-known-good snapshot protection.
- alert thresholds and de-duplication.
- diagnostics redaction.
- Room migrations.

When project initialization is complete, use:

```bash
./gradlew assembleDebug
./gradlew test
./gradlew :app:testDebugUnitTest
```

For design token checks:

```bash
npx -y @google/design.md lint DESIGN.md
```

If a command cannot run because SDK / JDK / Gradle wrapper is missing, say so explicitly.

## Implementation style

- Make small, reviewable changes.
- Prefer clear domain names over `Manager`, `Helper`, `Utils`.
- Keep files and functions small as described in `RULES.md`.
- Do not add large abstractions until the second provider actually requires them.
- Do not cross layer boundaries for convenience.
- User-visible strings must be resource-backed and support Simplified Chinese + English.
- Comments should explain invariants and security reasons, not restate code.

## Coding behavior guardrails

Think before coding:

- State assumptions explicitly when they affect implementation.
- If multiple interpretations exist, surface the options instead of silently choosing.
- If a simpler approach exists, say so and prefer it when it satisfies the task.
- If the requirement is unclear enough to change the code path, stop and ask.

Simplicity first:

- Write the minimum code that solves the requested problem.
- Do not add speculative features, generic abstractions or configurability.
- Do not create single-use abstractions unless they materially improve readability.
- If a solution is clearly overcomplicated, simplify before moving on.

Surgical changes:

- Touch only files and lines needed for the task.
- Do not refactor adjacent code, comments or formatting just because it looks improvable.
- Match existing project style unless it violates `AGENTS.md` or `RULES.md`.
- Remove only dead imports / variables / functions introduced by the current change; mention unrelated dead code instead of deleting it.
- Every changed line should trace back to the user request or the active plan task.

Goal-driven execution:

- Convert work into verifiable goals before implementing.
- For bugs, write or identify a failing check that reproduces the issue before fixing.
- For features, define the focused test or acceptance check before implementation.
- For multi-step tasks, keep a brief plan where each step has a verification command or check.
- Continue until the goal is verified, or report the exact blocked check.

## Git and commit style

Use conventional commits:

```text
docs: add agent development guides
feat: add codex auth connector shell
fix: preserve last known quota on refresh failure
test: cover alert threshold policy
```

Do not commit:

- build outputs.
- `.gradle/`, `build/`, APK/AAB outputs.
- local Android SDK config.
- keystores or signing config.
- `.env`, secrets files, credentials, real `auth.json`.
- IDE noise.

## Final response expectations

When done, summarize only:

- files changed.
- validation commands and results.
- commit hash if committed.
- remaining risks or blocked checks.

Do not paste large file contents into chat.
