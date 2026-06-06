# CodexMeter MVP Implementation Spec

> **For Hermes / coding agents:** read `AGENTS.md` first. Implement this spec task-by-task with strict TDD for production logic. Do not start production code for a behavior until a failing test for that behavior exists.

**Goal:** build the first Android MVP of `CodexMeter`, a self-use / small-scale sideloaded Android 12+ app for monitoring official Codex quota usage.

**Architecture:** single-module Android app with strict package boundaries. Codex is the first provider, but domain, storage, refresh, widget, notification and diagnostics models must remain provider-aware from v1.

**Tech stack:** Kotlin, Jetpack Compose, Material 3, Room, DataStore, Android Keystore AES-GCM, OkHttp, kotlinx.serialization, WorkManager, Jetpack Glance, hand-written `AppContainer`.

**Status:** implementation contract for MVP v0.1.

---

## 1. Source of truth

Read in this order before implementation:

1. `AGENTS.md`
2. `docs/PRD.md`
3. `docs/ARCHITECTURE.md`
4. `docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md` when touching Codex login, OAuth session, account connection, auth notifications or `auth.json` migration.
5. `docs/SPEC.md`
6. `RULES.md`
7. `DESIGN.md` when touching UI, Widget, notification or UX

If this spec conflicts with PRD, architecture, rules or design, stop and patch the documents or ask for a decision. Do not silently choose the convenient interpretation.

Codex login migration note: `docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md` supersedes older MVP statements that describe `auth.json` as a user-facing fallback or embedded WebView auth. New account connection must use the Hermes-aligned device-code external-browser flow. Existing saved OAuth sessions, including sessions originally imported from `auth.json`, remain valid and must not be deleted automatically.

This file defines the implementation contract and work order. It does not replace:

- `docs/PRD.md` for product scope.
- `docs/ARCHITECTURE.md` for system boundaries and data flow.
- `RULES.md` for maintainability and code quality.
- `DESIGN.md` for UI / Widget / Notification appearance.

---

## 2. MVP scope

### 2.1 Must ship

The MVP must support:

- Android app display name: `CodexMeter`.
- Package: `com.kmnexus.codexmeter`.
- Debug package: `com.kmnexus.codexmeter.debug`.
- Android 12+ only (`minSdk = 31`).
- Multi-provider UI: Codex, DeepSeek, z.ai, MiniMax, Cursor, Kimi, Claude, Antigravity; all providers use a shared provider-aware domain layer.
- Hermes-aligned Codex device-code login through an external browser verification handoff, per `docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md`.
- No new user-facing `auth.json` file / full JSON import path; existing saved OAuth sessions continue to refresh normally.
- Official usage API validation before saving a newly connected account.
- Home dashboard with 5h quota, weekly quota, 24h trend, freshness and refresh state.
- Account tab with current account, account switching, add account, rename and delete.
- Settings tab with persistent notification configuration, thresholds, refresh, account-error notifications, retention, data management, diagnostics, and manual GitHub Releases APK update checks.
- Settings tab includes an appearance preference (Light / Dark / Follow System, default Follow System) that persists independently of accounts and applies immediately to both app and widget; the widget re-renders on theme change, with the known limitation that re-render is deferred to the next periodic refresh or app open when the app process is dead.
- Resizable home-screen widget driven by persisted widget state, with optional per-widget account and compact primary-window configuration.
- Optional persistent status notification.
- Threshold behavior uses remaining quota percent: 30/10/0 defaults; 30% only changes state color/copy; 10% and 0% may notify.
- Notification channels: status, quota alerts, account/errors.
- Local history retention: default 30 days, options 7/30/90/permanent.
- Simplified Chinese and English resources, default following the system language, unsupported system languages falling back to English, and no in-app manual language override.
- Redacted diagnostics safe to copy.

### 2.2 Explicit non-goals

Do not implement:

- Android 12- support.
- Public-store distribution assumptions.
- Lock-screen quota surface.
- Web page scraping or dashboard parsing.
- Token/cost local estimation.
- Manual token input.
- Cookie Header input.
- Manual browser OAuth / local callback flows outside the Codex device-code verification handoff.
- Cloud sync, remote logging, analytics or crash upload.
- Foreground service for refresh.
- Dynamic plugin system.
- Real-time multi-account monitoring; periodic background refresh may refresh all Active accounts with bounded concurrency.
- Independent history page.
- Hilt, Retrofit or Ktor.

---

## 3. Implementation order

Build in this order unless the spec is revised:

1. Android project skeleton and build health.
2. Core package structure, resource baseline and theme tokens.
3. Domain models and common error model.
4. Redaction and diagnostics primitives.
5. Room, DataStore and secure session storage.
6. Provider registry and Codex provider contracts.
7. Codex usage fetch, mapper and token refresh, including optional refresh-token rotation handling.
8. Codex device-code login client/use case and official usage API validation, per `docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md`.
9. Remove user-facing `auth.json` import UI/routes while preserving already-saved OAuth sessions.
10. Refresh coordinator, WorkManager and current-state derivation.
11. Alert policy and notification orchestration, including login-attempt notification behavior.
12. Compose UI navigation and screens.
13. Glance widget.
14. Data retention, deletion and final manual acceptance.

Rationale: device-code login is now the only product login path. Existing session, usage, storage and refresh pipelines stay reusable, but new account connection must no longer depend on `auth.json` import. The migration must be reversible and test-first because it touches auth, notification and account persistence boundaries.

---

## 4. Project skeleton spec

### 4.1 Files to create

Create the Android project in the existing repo root:

- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties`
- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/kmnexus/codexmeter/MainActivity.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-zh-rCN/strings.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/themes.xml`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- launcher icon resources or clearly marked placeholder adaptive icons

### 4.2 Build configuration

Required:

- Single module: `:app`.
- Namespace: `com.kmnexus.codexmeter`.
- `minSdk = 31`.
- `debug` and `release` build types only.
- `debug` uses `applicationIdSuffix = ".debug"`.
- `release` must not be debuggable.
- Use Kotlin official style.
- Enable AndroidX.
- Enable Compose.
- Use a version catalog if the implementation adds more than a minimal dependency set.

Dependency boundaries:

- Allowed: Compose, Material 3, lifecycle ViewModel, navigation-compose, Room, DataStore, WorkManager, Glance, OkHttp, kotlinx.serialization, coroutines, AndroidX test libraries.
- Allowed only if justified in the commit: KSP for Room.
- Not allowed: Hilt, Retrofit, Ktor, dynamic plugin frameworks, analytics SDKs, crash upload SDKs.

Permissions:

- Required: `android.permission.INTERNET`.
- Required: `android.permission.ACCESS_NETWORK_STATE`.
- Optional/runtime: `android.permission.POST_NOTIFICATIONS` for Android 13+.
- Optional: `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, only to offer (never force) the user a system dialog so Doze defers periodic refresh less aggressively. Surfaced from the background-refresh settings card when the app is not yet exempt.
- Not allowed: storage permissions, exact alarm, foreground service, location, contacts, phone, SMS.

Security manifest rules:

- Disable Auto Backup for MVP.
- Disable data extraction of sessions and quota data.
- All non-launcher components must explicitly set `android:exported="false"`, except system-launched AppWidget configuration activities which must be exported and handle only `AppWidgetManager.EXTRA_APPWIDGET_ID` scoped state.
- Cleartext traffic must remain disabled.

### 4.3 Verification

After skeleton creation:

```bash
./gradlew assembleDebug
./gradlew test
```

If Android SDK, JDK or Gradle wrapper is unavailable, record the blocker in the final response and do not claim build success.

Expected first commit:

```bash
git commit -m "chore: initialize android project skeleton"
```

---

## 5. Package layout spec

Use this root package:

```text
app/src/main/java/com/kmnexus/codexmeter/
```

Required package groups:

```text
core/
  app/
  clock/
  dispatchers/
  network/
  result/
  security/
  strings/

domain/
  model/
  provider/
  quota/
  refresh/
  settings/
  diagnostics/

data/
  local/db/
  local/entity/
  local/dao/
  local/mapper/
  preferences/
  secure/
  repository/

providers/
  codex/
    auth/
    dto/
    mapper/
    network/
    session/
    error/

refresh/
notification/
widget/
ui/
  navigation/
  theme/
  components/
  home/
  account/
  settings/
  auth/
  diagnostics/
```

Rules:

- `ui` depends on domain-facing state and ViewModels only.
- `widget` consumes `WidgetQuotaState` and optional `WidgetQuotaConfiguration`; it must not call providers, network or decrypt sessions.
- `notification` consumes `CurrentQuotaState` and `AlertPolicy`; WorkManager glue in `refresh` and repositories must not directly create notifications.
- `providers.codex` owns Codex DTOs, endpoint details, token refresh and provider-private session payload.
- Common domain code must never reference Codex DTO classes.
- Use `AppContainer` for dependency wiring. Do not introduce Hilt.

---

## 6. Domain model spec

### 6.1 Required value objects

Create typed wrappers or strongly named data classes for:

- `ProviderId`
- `ProviderAccountId`
- `LocalAccountId`
- `QuotaWindowId`
- `SnapshotId`
- `RefreshAttemptId`

Do not pass provider/account IDs as anonymous `String` across layers unless the value object is impractical at an Android framework boundary.

### 6.2 Provider account

`ProviderAccount` must represent a user-visible account without exposing credentials.

Required fields:

- `localAccountId`
- `providerId`
- `providerAccountId?`
- `displayName`
- `avatarInitial`
- `avatarColorKey`
- `status`: active, needsReauth, disabled, deleted
- `createdAt`
- `updatedAt`
- `lastSuccessfulRefreshAt?`

Rules:

- `displayName` must be editable by user.
- Default display name must not include token, email from unverified sources, or raw ID token payload.
- Avatar is circular solid color + first display character, per `DESIGN.md`.

### 6.3 Quota snapshot

`QuotaSnapshot` represents one successful official usage response after normalization.

Required fields:

- `snapshotId`
- `providerId`
- `localAccountId`
- `providerAccountId?`
- `fetchedAt`
- `source`: deviceCodeLogin, legacyAuthJsonImport, backgroundRefresh, manualRefresh, widgetRefresh, appOpenRefresh
- `planType?`
- `windows: List<QuotaWindow>`
- `credits?`
- `responseDigest?` redacted hash or summary only; never raw response body

Rules:

- Persist snapshots only after a successful provider response.
- A failed refresh must not overwrite or delete the last successful snapshot.
- If a provider field is missing, represent the relevant window as unavailable instead of inventing a value.

### 6.4 Quota window

`QuotaWindow` supports all providers including balance-based and usage-count-based ones.

Required fields:

- `windowId`: for Codex use `five_hour` and `weekly`.
- `titleKey`: resource key / enum reference for UI copy.
- `usedPercent?`: official percent when available.
- `remainingPercent`: derived as `100 - usedPercent.coerceIn(0, 100)`.
- `resetAt?`: provider reset timestamp when available.
- `limitWindowSeconds?`: provider window duration when available.
- `isPrimaryCandidate`: whether user may choose it as primary quota.
- `availability`: available, depleted, missing, decodeFailed, unsupported.
- `displayKind`: `Percent` (default) / `Balance` / `UsageCount` / `MultiModelFraction`.
- `balanceAmount?` / `balanceCurrency?`: raw balance for balance-type windows.
- `originalBalanceAmount?` / `originalBalanceCurrency?`: pre-conversion balance (presentation-only, never persisted).
- `grantedBalance?` / `toppedUpBalance?`: balance breakdown (e.g. DeepSeek granted vs topped-up); persisted.
- `usedCount?` / `limitCount?`: usage-count windows.
- `subLabel?`: auxiliary display label.
- `modelBuckets: List<QuotaModelBucket>`: per-model fractions for MultiModelFraction windows.
- `usesModelBucketSum`: when true, the home usage chart sums per-model used-fraction deltas instead of diffing `usedPercent` (Antigravity).

`QuotaModelBucket`: `modelId`, `displayName`, `remainingFraction`, `resetAt?`.

Codex mapping:

- `rate_limit.primary_window` maps to `five_hour`.
- `rate_limit.secondary_window` maps to `weekly`.
- `used_percent` maps to `usedPercent`.
- `reset_at` maps to `resetAt`.
- `limit_window_seconds` maps to `limitWindowSeconds`.

Display rules:

- Percent windows: show remaining percent, clamp to 0..100.
- Balance windows: show `balanceAmount`; apply currency conversion when target currency differs.
- UsageCount windows: show used/limit counts.
- MultiModelFraction windows: show per-model fraction tiles.
- Alerts only run for available windows; balance windows alert when balance > 0; percent/count windows require valid data and reset timestamp.

### 6.5 Refresh attempt

`RefreshAttempt` records every refresh try, success or failure.

Required fields:

- `attemptId`
- `providerId`
- `localAccountId`
- `trigger`
- `startedAt`
- `finishedAt?`
- `status`: success, failed, skipped, cancelled
- `errorCode?`
- `httpStatus?`
- `retryable?`
- `userActionRequired?`
- `diagnosticsDigest?`

Rules:

- Do not store raw response bodies.
- Do not store Authorization headers, cookies, OAuth code, callback query or raw auth JSON.
- Failed attempts can update freshness/error UI but cannot erase last-known-good quota.

### 6.6 Current quota state

`CurrentQuotaState` is the only rich read model for App UI, Widget projection, notifications and alert policy.

It must derive from:

- Current account preference.
- Account metadata.
- Latest successful snapshot.
- Latest refresh attempt.
- Provider capabilities.
- User settings.
- Current clock.

State categories:

- unauthenticated
- loading
- fresh
- possiblyStale
- expired
- authRequired
- errorWithLastKnownGood
- noData

Freshness policy:

- 0–30 minutes: fresh.
- 30 minutes–2 hours: possibly stale.
- 2 hours+: expired.
- 401/403 or refresh token invalid/expired/reused/revoked: auth required.

---

## 7. Storage spec

### 7.1 Room

Database name:

```text
codexmeter.db
```

Required tables:

- `provider_accounts`
- `quota_snapshots`
- `refresh_attempts`
- `alert_states`

Recommended columns:

`provider_accounts`:

- `local_account_id` primary key
- `provider_id`
- `provider_account_id`
- `display_name`
- `avatar_initial`
- `avatar_color_key`
- `status`
- `created_at`
- `updated_at`
- `last_successful_refresh_at`

`quota_snapshots`:

- `snapshot_id` primary key
- `provider_id`
- `local_account_id`
- `provider_account_id`
- `fetched_at`
- `source`
- `plan_type`
- `windows_json`
- `credits_json`
- `response_digest`

`refresh_attempts`:

- `attempt_id` primary key
- `provider_id`
- `local_account_id`
- `trigger`
- `started_at`
- `finished_at`
- `status`
- `error_code`
- `http_status`
- `retryable`
- `user_action_required`
- `diagnostics_digest`

`alert_states`:

- `alert_state_id` primary key
- `provider_id`
- `local_account_id`
- `window_id`
- `threshold`
- `reset_at`
- `last_notified_at`

Indexes:

- `(provider_id, local_account_id)` on account-owned tables.
- `(provider_id, provider_account_id)` unique for known provider accounts; nullable `provider_account_id` rows remain allowed.
- `(provider_id, local_account_id, fetched_at)` on snapshots.
- `(provider_id, local_account_id, started_at)` on attempts.
- `(provider_id, local_account_id, window_id, reset_at, threshold)` unique for alert de-duplication.

Rules:

- `windows_json` is normalized domain JSON, not raw provider response JSON.
- Room entities must not contain access token, refresh token, ID token, cookies or raw auth JSON.
- Add migrations from schema version 1 onward; do not use destructive migrations except in debug builds.

### 7.2 DataStore

Use DataStore for non-sensitive preferences:

- current provider id
- current local account id
- legacy primary quota window for migration only; new default display behavior is owned by each surface
- persistent notification account selection: default follow-current
- persistent notification display window: default `five_hour`
- per-account quota alert window toggles
- threshold values are remaining-percent based: defaults `30`, `10`, `0`
- notification toggles
- persistent status notification enabled/disabled
- refresh preference metadata
- no in-app language-mode preference is consumed at runtime; the app follows the system language and falls back to English when unsupported
- retention: 7, 30, 90, forever
- onboarding dismissed flags

Rules:

- DataStore must not store token/session payloads.
- Preferences must be typed behind a repository; UI must not read raw keys.

### 7.3 Secure session store

Use Android Keystore + AES-GCM for provider-private session payloads.

Required public envelope fields:

- `providerId`
- `localAccountId`
- `providerAccountId?`
- `schemaVersion`
- `payloadCiphertext`
- `payloadNonce`
- `createdAt`
- `updatedAt`

Codex private encrypted payload fields:

- `accessToken`
- `refreshToken`
- `idToken?`
- `accountId?`
- `lastRefresh?`

Rules:

- Public layers see only envelope metadata.
- Only `providers.codex.session` may decode Codex payload fields.
- Replacing refreshed tokens must be serial per account.
- Legacy imported `auth.json` raw content must never be persisted; new product flows must not accept raw `auth.json` input.
- Keystore loss must map to a safe `authRequired` / re-login state.

---

## 8. Provider spec

### 8.1 Current provider wiring

All 8 providers are wired by hand in `AppContainer` using `ProviderRegistry`.

- `ProviderRegistry` holds a `ProviderConfig` per provider (displayName, icon, `ProviderAuthKind`, capability flags).
- Each provider's `<Name>RefreshProvider` implements `RefreshProvider` used by `RefreshCoordinator`.
- Each provider's `<Name>SessionImporter` implements `SessionImporter`; `SessionImportRouter` routes by `ProviderAuthKind`.
- `ProviderSelectionSheet` is shown when the user taps "add account"; it lists all registered providers as a bottom sheet that expands from the tab bar. Selecting a provider navigates to the corresponding auth screen.
- `auth.json` import must not be exposed; legacy saved sessions remain readable as ordinary encrypted OAuth sessions.

Auth screen routing by kind:

- `OAuthWebView` (Codex) → existing device-code flow (`AddAccountScreen` / `DeviceCodeLoginViewModel`).
- `ApiKeyImport` → `ApiKeyAuthScreen`.
- `CookieAuth` → `WebViewAuthScreen` with cookie capture mode.
- `OAuthPkceLogin` → `WebViewAuthScreen` with OAuth intercept or loopback mode per provider.

### 8.2 RefreshProvider contract

`RefreshProvider` / current Codex provider path must:

- load/decrypt session through provider session store.
- refresh session if needed.
- fetch official quota data.
- map DTO to `QuotaSnapshot`.
- map provider-private errors to `QuotaError`.

Rules:

- Provider implementations return domain models only.
- Provider DTOs never leave `providers.codex.dto`.
- Provider-private exceptions never reach UI directly.

### 8.3 Device-code login contract

`CodexDeviceCodeLoginUseCase` and `CodexSessionImporter` must normalize device-code login success into the same saved account/session/snapshot shape used by the rest of the app. See `docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md` for the complete device-code state machine, notification behavior and account-mismatch handling.

Return a safe login result with:

- provider id
- local account id
- provider account id if known
- display name suggestion
- encrypted session envelope reference
- initial successful quota snapshot

Save account only after the official usage API validates the session.

Rules:

- Do not expose `auth.json` file or paste import as new user-facing auth methods.
- Existing encrypted sessions that were created by the old import flow must remain readable and refreshable.
- Re-login for an existing account must handle account-id mismatch with an explicit user decision before saving a different account.

---

## 9. Codex provider spec

### 9.1 Official usage endpoint

Default endpoint:

```text
GET https://chatgpt.com/backend-api/wham/usage
```

Headers:

- `Authorization: Bearer <access_token>`
- `Accept: application/json`
- `User-Agent: CodexMeter/<version>`
- `ChatGPT-Account-Id: <account_id>` when account id is available

Rules:

- HTTPS only.
- 30 second timeout maximum.
- `401` and `403` map to auth required.
- 5xx maps to retryable provider/server error.
- Network exceptions map to retryable network error.
- Invalid/missing quota fields map to parse/decode error with safe diagnostics.
- Do not log response bodies.

### 9.2 Usage DTO

Codex usage DTO must tolerate nullable and partially missing fields.

Observed shape to support:

- `plan_type`
- `rate_limit.primary_window.used_percent`
- `rate_limit.primary_window.reset_at`
- `rate_limit.primary_window.limit_window_seconds`
- `rate_limit.secondary_window.used_percent`
- `rate_limit.secondary_window.reset_at`
- `rate_limit.secondary_window.limit_window_seconds`
- `credits.has_credits`
- `credits.unlimited`
- `credits.balance`

Parsing rules:

- DTO fields should be nullable unless the endpoint contract is proven strict.
- Unknown plan types are preserved as safe strings or mapped to `unknown`.
- `credits.balance` may be number or numeric string.
- A single bad window must not crash parsing of the whole response if the other window is valid.
- Missing official fields produce unavailable display state, not estimated values.

### 9.3 Token refresh

Refresh endpoint:

```text
POST https://auth.openai.com/oauth/token
```

Refresh body fields:

- `client_id`
- `grant_type = refresh_token`
- `refresh_token`
- `scope = openid profile email`

Rules:

- The Codex OAuth client id must be stored once in provider-private config with a source comment.
- Refresh should be attempted before usage fetch when provider rules indicate the access token may be stale.
- Refresh must also be attempted after a 401/403 when a refresh token exists, unless the error is already known terminal.
- `refresh_token_expired`, `refresh_token_reused`, `invalid_grant`, `refresh_token_invalidated` map to auth required.
- New access/id tokens replace encrypted payload atomically.
- If a refresh response includes a non-empty new refresh token, replace it atomically.
- If a refresh response omits a new refresh token, keep the existing refresh token, matching Hermes Agent behavior.
- If token refresh fails terminally, keep last-known-good quota visible and mark account needs reauth.

### 9.4 Legacy `auth.json` migration

`auth.json` import is no longer a user-facing MVP login method. It remains relevant only as legacy compatibility for sessions already saved before this migration.

Migration rules:

- Remove or hide the Add Account `导入 auth.json` / `Import auth.json` button.
- Remove or hide the paste-full-JSON import field and navigation route.
- Do not add any new token, cookie or raw JSON manual input path.
- Do not automatically delete existing accounts or sessions that were originally imported from `auth.json`.
- Treat already-saved sessions as ordinary encrypted Codex OAuth sessions and keep refreshing them normally.
- If implementation removes obsolete parser/import code, delete its tests in the same commit and verify no product path references remain.
- If implementation temporarily keeps parser/import internals for staged removal, they must be unreachable from UI and app navigation.

Required migration checks:

- Add Account UI no longer renders `导入 auth.json` / `Import auth.json`.
- Home/auth-required UI no longer offers import as a retry action.
- Existing accounts can still refresh after the import UI is removed.
- Diagnostics and logs still redact the phrase and raw contents of `auth.json` when encountered in legacy paths.

### 9.5 Device-code external-browser login

Codex login uses the Hermes-aligned device-code flow defined in `docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md`.

Required flow summary:

1. Start a new login attempt and cancel any previous attempt.
2. Request a user-facing device code from the OpenAI Codex device auth endpoint.
3. Show the normal Codex verification URI and open it through the external browser.
4. Render a compact one-line code row in the app: device code + copy button + open-verification action.
5. Publish an account/error-channel login notification. Notification body opens the same external verification URI; notification action copies the latest code.
6. Poll according to the server interval until authorized, failed, cancelled or expired.
7. Exchange the returned authorization code and code verifier for OAuth tokens.
8. Validate tokens through the official usage endpoint.
9. Save account, encrypted session and initial snapshot only after validation succeeds.
10. Update the login notification to connected briefly, then let the persistent quota notification take over.

Rules:

- Notification permission is not required for login; the page code row is the fallback.
- The verification page must not be embedded in WebView; OpenAI/Codex browser state stays in the user's external browser.
- The normal verification page is opened; do not auto-open a code-filled verification URL in MVP.
- Login attempts are process-bound. A killed app process cancels the attempt; no foreground service or durable background login task is introduced.
- Every attempt has an attempt id; stale async results and stale notification actions must be ignored.
- Re-login account mismatch requires explicit user choice before saving the authorized account as a new account.
- Do not log or diagnose access token, refresh token, id token, authorization code, code verifier, device auth id, raw token response or raw usage response.

---

## 10. Refresh and state spec

### 10.1 Refresh triggers

All refreshes must go through `RefreshCoordinator`.

Supported triggers:

- app open / foreground
- manual refresh in Home
- widget tap refresh
- device-code login success
- account switch
- periodic WorkManager refresh
- settings change that affects primary display state
- threshold changes and account alert-toggle changes that require alert re-evaluation

Rules:

- Single-flight per `(providerId, localAccountId)`.
- Periodic WorkManager refresh loads every Active account and refreshes them with bounded concurrency `2`.
- Disabled, deleted and auth-required accounts are skipped by periodic quota refresh.
- Token refresh serialized per account.
- WorkManager uses unique work.
- Background refresh interval should respect Android WorkManager limits; target around 15 minutes, not real-time.
- Workers write refresh attempts and snapshots; notification orchestration happens through `NotificationOrchestrator`, not directly in provider/network code.

### 10.2 Refresh outcomes

Success:

- Save `QuotaSnapshot`.
- Save success `RefreshAttempt`.
- Update account `lastSuccessfulRefreshAt`.
- Update `CurrentQuotaState`.
- Update `WidgetQuotaState`.
- Update status notification if enabled.
- Evaluate alert policy for the refreshed account and every alert-enabled available quota window.

Failure:

- Save failed `RefreshAttempt`.
- Preserve last successful snapshot.
- Update `CurrentQuotaState` with error/staleness/auth state.
- Update widget/status notification with safe stale/error state.
- Notify via account/error channel only when user action is required or repeated failure crosses policy threshold.

### 10.3 Current state factory

Create `CurrentQuotaStateFactory` as a pure, testable component.

Inputs:

- account
- latest snapshot
- latest refresh attempt
- preferences
- provider capabilities
- clock

Outputs:

- screen status
- primary window state
- secondary window state
- trend input state
- freshness label key
- next reset label data
- action buttons to show
- widget projection
- notification projection

Required tests:

- unauthenticated when no current account.
- fresh within 30 minutes.
- possibly stale after 30 minutes.
- expired after 2 hours.
- auth required on 401/403 or terminal refresh error.
- failed refresh keeps last-known-good quota visible.
- missing or disabled alert window suppresses quota alert.

---

## 11. Alert and notification spec

### 11.1 Alert policy

Default thresholds use remaining quota percent:

- 30% left: caution state only, no notification.
- 10% left: warning notification.
- 0% left: limit notification.

Rules:

- Any alert-enabled account quota window can trigger threshold alerts.
- Alert de-duplication key: `(providerId, localAccountId, windowId, resetAt, threshold)`.
- A reset time change allows alerts again.
- Increasing remaining quota above a threshold does not spam a new alert for the same reset window.
- Missing percent or missing reset time suppresses alerts.
- User can adjust caution/warning thresholds, but caution remaining percent must stay higher than warning remaining percent; limit remains fixed at 0%.
- The same remaining-percent thresholds also drive Home quota status colors/copy and Widget tone/copy.
- Per-account 5-hour and weekly alert toggles live on the Account screen.
- Quota refresh success, threshold updates, and enabling an account/window alert all re-evaluate alerts.
- Notification permission denial suppresses notification posting and must not mark alert de-dupe state.

Required tests:

- 30% left never creates notification event.
- 10% left creates one event per reset window.
- 0% left creates one event per reset window.
- Duplicate refresh below or at the same remaining-percent threshold does not create duplicate event.
- Reset timestamp change allows a new event.
- Disabled or missing alert window creates no event.

### 11.2 Notification channels

Create channels:

- `quota_status`: quiet persistent status notification.
- `quota_alerts`: warning/limit quota threshold alerts.
- `account_errors`: reauth and repeated error alerts.

Rules:

- App must still work without notification permission.
- Request notification permission only when enabling notifications or when first needed with rationale.
- Status notification is optional and user-controlled.
- Notification text must come from string resources.
- PendingIntent must use immutable flags unless mutability is required.
- Notification content must not include account secrets or raw diagnostics.

---

## 12. UI implementation spec

### 12.1 Navigation

Bottom tabs, fixed order:

1. Home
2. Account
3. Settings

Rules:

- Use real vector icons, not CSS/Canvas-style fake icons.
- Every tab has content description in both supported languages.
- Bottom-tab destination changes must disable NavHost fade/slide transitions, keep the lightweight page cascade, and draw the fixed backdrop cover in full-window coordinates; only the translated content layer consumes Scaffold inner padding so the previous destination never shows through and the status bar has no seam.
- Scrollable content must reserve bottom tab and gesture insets.
- Last content item must be fully scrollable above the tab bar.

### 12.2 Home screen

Home states:

- Unauthenticated: show login guide card.
- Loading: show skeleton/progress without losing last-known-good data if available.
- Fresh quota: show 5h, weekly, trend and refresh state.
- Stale/expired: show last quota with stale/expired copy.
- Auth required: show re-login action only; do not offer `auth.json` import.
- Error with last-known-good: show quota plus safe error summary.

Required visible content when authenticated:

- Title `CodexMeter`.
- Current account identity chip or compact account label.
- 5h quota card.
- Weekly quota card.
- 24h compact trend.
- Refresh status and manual refresh action.
- Widget preview / widget hint per `DESIGN.md`.

Rules:

- Do not add ambiguous top-right action button.
- Quota cards are not clickable detail pages in MVP.
- Status color must be paired with status text.
- Percent text should use tabular/monospace digits.
- 24h trend uses fixed hourly buckets in the rolling 24h window; multiple samples in one hour are averaged before drawing rounded bars.

### 12.3 Account screen

Required content:

- Current account card.
- Account list.
- Add account action.
- Rename account action.
- Delete account action with confirmation.
- Current-account switching through account cards in the Account tab.

Rules:

- Account avatar is circular solid color + initial.
- Account list cards are collapsed by default; the header uses a chevron icon button, and the details drawer expands to reveal plan, credits, quota summaries, alert switches and management actions.
- Delete confirmation must state that session and local history are deleted.
- Deleting current account must select another account if available, otherwise return to unauthenticated state.
- Account screen is the only place for account management; Settings must not duplicate account cards.
- `Set current` keeps a visible outline even when disabled for the active account.
- `Delete` uses the same outlined button shape as the other account actions while keeping danger-colored text.

### 12.4 Settings screen

Required groups:

- Appearance: a standalone card with a 3-option segmented pill control (Light / Dark / Follow System); default is Follow System; the selection applies immediately to both the app and widget; preference is persisted independently of accounts.
- Persistent notification: status notification switch, notification account selection, notification display quota.
- Alerts: account/error notification switch and thresholds.
- Refresh: background refresh switch and latest-result summary.
- Refresh summary includes the latest current-account periodic background refresh result; the refresh card must not expose a separate `Check now` / `立即检测` button.
- Data: history retention, clear current account history, clear all history.
- Diagnostics: collapsed redacted diagnostics card and copy action.
- About: app version and privacy summary.

Rules:

- Destructive actions require confirmation.
- Diagnostics must be safe to copy.
- Long localized labels must not break layout.

### 12.5 Add account flow

Entry points:

- Home unauthenticated card.
- Account tab add button (circular `+` icon).
- Account re-login action.

Provider selection:

- Tapping "add account" opens `ProviderSelectionSheet`, a bottom sheet that expands up from the floating tab bar and collapses back down on dismiss.
- The sheet lists all `ProviderRegistry.all` providers with their brand icon and display name.
- Selecting a provider navigates to the corresponding auth screen (device-code, API key input, WebView cookie, or OAuth PKCE).
- The full-screen `ProviderSelectionScreen` still exists as a fallback route but the primary entry point is the sheet.

Shared auth chrome:

- All per-provider auth screens use `AuthScaffold`: fixed-height center-aligned top bar with a left back icon and trailing action slot (clear/confirm/reload icons).
- WebView-based auth (Kimi/Cookie, Claude/Antigravity OAuth) shows a one-time tip dialog on first open; the tip renders the matching top-bar action icon inline.
- OAuth intercept WebViews use a hardware layer; cookie-capture pages use a software layer workaround.

Device-code layout (Codex only):

- After the user starts login and the background polling attempt is created, show the in-app code row and expose the external verification URI.
- Cancelling login clears the user-facing device code, verification URI, expiry, and poll interval from the page before a new login attempt is started.
- Keep the user-facing device code, copy button, and browser-open action readable in the login page.
- Do not let status copy crowd out the code row or primary browser-open action.
- If notification permission is missing or denied, the page code row remains sufficient to complete login.

Fallback behavior:

- Expired/cancelled/failed device-code attempts can generate a new code.
- Token exchange success still requires usage validation before account save.
- Validation failure must show safe reason and not save session.
- `auth.json` import is no longer shown as a fallback.

---

## 13. Widget spec

### 13.1 MVP widget

Implement one horizontally and vertically resizable Glance widget with four layout variants driven by `SizeMode.Responsive`:

| Variant | Reference size (dp) | Field capacity |
|---|---|---|
| `ThreeByOne` | 180×60 | 1 |
| `FourByOne` | 300×60 | 2 |
| `ThreeByTwo` | 180×150 | 3 |
| `FourByTwo` | 300×150 | 4 |

Content layout:

- Each field tile shows window label, main value (percent / balance / count), and reset/refresh time.
- `ThreeByOne` / `FourByOne`: horizontal row of field tiles with dividers.
- `ThreeByTwo` / `FourByTwo`: grid of field tiles with horizontal and vertical dividers.
- All variants show the provider icon, account name, and status in a header row.
- When no fields are configured, an onboarding hint is shown.

Configuration:

- Launcher reconfiguration opens a configuration activity. Users select up to 4 quota windows (`selectedWindowIds` multi-select) and the account.
- The widget renders the first `fieldCapacity` windows from the ordered selection.
- If a widget has no explicit configuration, it follows the current account and uses the provider's default window order.
- Widget configuration is independent from persistent notification account/window settings.

Behavior:

- Tap opens app Home (or add-account flow when unauthenticated).
- Widget reads `WidgetQuotaState` (persisted projection from `CurrentQuotaState`) and never calls network directly.
- Non-current configured accounts use the latest local snapshot from multi-account background refresh.

States:

- no account (onboarding)
- unconfigured (field selection hint)
- fresh
- stale
- expired
- auth required
- error with last-known-good

Rules:

- Widget text is resource-backed.
- Widget must remain readable on light/dark launchers.
- Widget must not show secrets or diagnostics.
- Widget tone and status copy use the configured remaining-percent caution/warning thresholds.

---

## 14. Diagnostics and redaction spec

### 14.1 Redactor

Create a central redactor used by logs, diagnostics and error mapping.

Must redact:

- access token
- refresh token
- ID token
- auth code
- state when associated with OAuth callback
- Cookie
- Authorization header
- complete `auth.json`
- full OAuth callback query
- raw provider response body

Rules:

- Do not include token prefix/suffix.
- Do not include token length when it helps fingerprint secrets.
- Diagnostics may include safe enum codes, HTTP status, human-readable timestamps with relative age, provider id, account local id hashes, app/build/device/OS info, background-execution signals (battery optimization, background restriction, network type, data saver), WorkManager state, notification permission, app configuration, and per-account refresh/alert summaries.

Required tests:

- Redacts bearer tokens.
- Redacts JSON token fields.
- Redacts callback query.
- Redacts cookies.
- Leaves safe operational fields readable.

### 14.2 Diagnostics card

Copyable diagnostics are grouped into sections (generated, environment, workmanager, config, accounts, current account) and must include only:

- app version / build type / Android SDK and release
- device model and locale
- background-execution signals: battery-optimization-ignored, background-restricted, network type, data saver
- app first-install / last-update time
- WorkManager state, run-attempt count, next schedule, stop reason; notification permission status
- Room schema version, retention days, refresh interval, notification/alert toggles, alert thresholds, widget count
- per-account alert windows (with provider id and hashed account id)
- per-account one-line summary: provider id, hashed account id, status, last attempt result, last success / snapshot age
- current account detail: provider id, hashed account id or short stable diagnostic id, current state category, session status, consecutive failure count
- last success timestamp, last attempt status, safe error code, HTTP status if present
- device-code login status, hashed attempt id, verification URI category, poll info

Must not include:

- raw auth JSON
- raw API response
- raw request/response headers
- token values
- cookies
- OAuth query

---

## 15. Data retention and deletion spec

### 15.1 Retention

Default retention:

- 30 days.

Options:

- 7 days.
- 30 days.
- 90 days.
- forever.

Rules:

- Retention cleanup removes old successful snapshots and refresh attempts.
- Alert states may be cleaned when their reset window is older than retained history.
- Cleanup must not remove current account/session.

### 15.2 Delete account

Deleting an account removes:

- provider account record
- secure session envelope and encrypted payload
- quota snapshots for that account
- refresh attempts for that account
- alert states for that account
- widget state if it references the deleted account
- current account preference if it references the deleted account

Rules:

- Requires confirmation.
- Must be transactional where possible.
- Must not affect other accounts.

### 15.3 Clear history

Clear current account history:

- Deletes snapshots and refresh attempts for current account.
- Keeps account and session.

Clear all history:

- Requires stronger confirmation.
- Deletes snapshots and refresh attempts for all accounts.
- Keeps accounts and sessions.

---

## 16. i18n and accessibility spec

### 16.1 i18n

Supported languages:

- Simplified Chinese.
- English.

Rules:

- Default follows system.
- Unsupported system languages fall back to English.
- The app does not expose an in-app manual language override.
- Startup clears any platform app-locale override left by older builds so a previously selected language cannot keep overriding the system setting.
- All user-visible UI, Widget, notification, error and diagnostics labels must be resource-backed or resolved through a localized string resolver.
- `Codex`, `OpenAI`, `auth.json`, `OAuth`, `WebView` are not translated.
- Tests should not rely on localized visible strings where stable test tags are possible.

### 16.2 Accessibility

Rules:

- Touch targets minimum 48dp.
- Interactive elements need meaningful content descriptions.
- Status color must be paired with text.
- Percent, reset time and freshness state must be readable by screen readers.
- Destructive actions must be confirmable and cancellable.
- Large font mode must not hide core actions.

---

## 17. Testing spec

### 17.1 Required unit tests

Create local JVM tests for:

- `CodexDeviceCodeClientTest`
- `CodexDeviceCodeLoginUseCaseTest`
- `CodexDeviceCodeNotificationTest`
- `CodexUsageDtoTest`
- `CodexUsageMapperTest`
- `CodexTokenRefresherTest`
- `QuotaErrorMapperTest`
- `SecureSessionStoreTest`
- `CurrentQuotaStateFactoryTest`
- `RefreshCoordinatorTest`
- `AlertPolicyTest`
- `DiagnosticsRedactorTest`
- `RetentionPolicyTest`

Use fakes over live network.

Recommended tools:

- JUnit.
- kotlinx-coroutines-test.
- MockWebServer or a small fake HTTP client seam.
- Turbine for Flow/StateFlow.
- Room in-memory database for DAO tests.
- Robolectric only when Android framework behavior is required.

### 17.2 Test-first rules

For every production behavior:

1. Write the failing test.
2. Run the focused test and confirm it fails for the expected reason.
3. Implement the minimal code.
4. Run the focused test and confirm it passes.
5. Run the relevant package test set.
6. Commit.

Do not write production logic first and retrofit tests later.

### 17.3 Manual acceptance

Manual checks are required for:

- Device-code external-browser login on a real device.
- Notification runtime permission flow, including missing-permission login fallback.
- Widget add/update/tap behavior.
- UI layout at normal and large font.
- Light/dark launcher widget readability.
- Account deletion confirmation.
- Diagnostics copy output redaction.

Record manual acceptance notes in commit or handoff summary.

### 17.4 Build verification commands

Baseline commands:

```bash
./gradlew assembleDebug
./gradlew test
./gradlew :app:testDebugUnitTest
npx -y @google/design.md lint DESIGN.md
```

If instrumentation is configured and a device/emulator is available:

```bash
./gradlew connectedDebugAndroidTest
```

---

## 18. Milestone checklist

### M0 — Skeleton

Acceptance:

- Project builds with `assembleDebug`.
- Empty Compose shell launches.
- App name and package ids are correct.
- Auto Backup is disabled.
- Permissions are minimal.

Commit:

```bash
git commit -m "chore: initialize android project skeleton"
```

### M1 — Domain and storage foundations

Acceptance:

- Domain models compile.
- Room schema v1 exists.
- DataStore preference repository exists.
- Secure session store interface and fake test implementation exist.
- Redactor tests pass.

Commit:

```bash
git commit -m "feat: add quota domain and local storage foundations"
```

### M2 — Codex usage and refresh foundations

Acceptance:

- Official usage API fetcher works against fake HTTP server.
- Usage validation can create account, secure session and initial snapshot from a provider-supplied OAuth session.
- Token refresh tests pass, including the case where refresh response omits a new refresh token and the old value is retained.
- Failed validation saves no session.

Commit:

```bash
git commit -m "feat: add codex usage and refresh foundations"
```

### M3 — Device-code login and auth migration

Acceptance:

- Device-code client/use-case tests pass.
- New login attempts cancel previous attempts and ignore stale results.
- Device-code login validates usage before saving account/session/snapshot.
- `auth.json` import UI/routes are removed or unreachable.
- Existing saved sessions continue to refresh.

Commit:

```bash
git commit -m "feat: add codex device code login"
```

### M4 — Refresh pipeline

Acceptance:

- Refresh coordinator single-flight tests pass.
- Failed refresh preserves last-known-good quota.
- WorkManager unique work is registered.
- 401/403 handling attempts refresh/retry once when safe, then maps terminal failures to auth required.

Commit:

```bash
git commit -m "feat: add quota refresh pipeline"
```

### M5 — Current state, alerts and notifications

Acceptance:

- `CurrentQuotaStateFactory` tests pass.
- Alert threshold/de-dupe tests pass.
- Notification channels are created.
- App works without notification permission.

Commit:

```bash
git commit -m "feat: add quota state and alerts"
```

### M6 — Compose UI

Acceptance:

- Home / Account / Settings bottom tabs exist in confirmed order.
- UI follows `DESIGN.md` Air Glass Dashboard direction.
- Unauthenticated, fresh, stale, auth required and error states render.
- Account add/device-code-login/switch/rename/delete flows are wired to ViewModels.
- Strings are available in Chinese and English.

Commit:

```bash
git commit -m "feat: build codexmeter app screens"
```

### M7 — Widget

Acceptance:

- Resizable Glance widget with 3×1 / 4×1 / 3×2 / 4×2 layout variants.
- Widget configuration supports multi-field selection (up to 4 windows) and account selection.
- Widget consumes persisted projection only; does not call network directly.
- Widget displays unconfigured onboarding, no account, fresh, stale and auth-required states.
- Provider brand icon shown in widget header.
- Tap opens app; refresh action, if present, routes through refresh coordinator.

Commit:

```bash
git commit -m "feat: add quota home screen widget"
```

### M8 — Device-code external-browser manual acceptance

Acceptance:

- The login page's browser button opens the normal Codex verification page in the external browser.
- Compact code row remains readable and does not crowd the primary action.
- Notification body opens the external verification page; notification action copies the latest code.
- Login still works when notification permission is denied.
- Success notification is brief and persistent quota notification takes over.
- Real-device or emulator manual acceptance is recorded without exposing secrets.

Commit:

```bash
git commit -m "test: record device code login acceptance"
```

### M9 — Final hardening

Acceptance:

- Retention cleanup works.
- Delete account and clear history flows are confirmed.
- Diagnostics copy is redacted.
- `assembleDebug`, unit tests and design lint pass.
- No secrets or generated build outputs are committed.

Commit:

```bash
git commit -m "chore: harden codexmeter mvp"
```

---

## 19. Definition of done

MVP implementation is done only when:

- Device-code external-browser login can create a Codex account and fetch official quota.
- `auth.json` is no longer available as a new user-facing login/import path, while existing saved OAuth sessions continue to refresh.
- Home, Account, Settings, Widget and notifications all derive from shared state.
- Failed refresh never erases last-known-good quota.
- Diagnostics are redacted by test.
- All user-facing strings are localized in Chinese and English.
- `./gradlew assembleDebug` passes.
- `./gradlew test` or `./gradlew :app:testDebugUnitTest` passes.
- `npx -y @google/design.md lint DESIGN.md` passes.
- Git status is clean after final commit.
