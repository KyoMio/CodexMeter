# CodexMeter MVP QA Notes

Date: 2026-05-28

## Current QA Baseline

This section supersedes the 2026-05-24 pre-migration QA notes that covered embedded WebView OAuth and user-facing `auth.json` import. Those flows are no longer part of the current product or codebase.

Current UI expectations:

- Home no-account state shows `CodexMeter`, `连接 Codex` / `Connect Codex`, and `登录 Codex` / `Log in to Codex`; it does not show an `auth.json` import action.
- Add Account exposes only the Codex device-code sign-in flow, with code display, copy, external verification-page open, cancellation and validation retry.
- Home signed-in state shows the current-account glass summary, 5h quota, weekly quota, rounded hourly trend bars and refresh status.
- Account tab is the only account-management surface: add, set current, rename, relogin, delete, and per-account quota alert switches.
- Settings groups are persistent notification, alerts, refresh, data, diagnostics and about; there is no account-management card, display group, language picker, font picker, primary-window picker, or refresh-card `Check now` action.
- Widget defaults to current account + 5h unless a specific widget configuration overrides account or compact primary quota window.

Recommended automated gates for current changes:

- `./gradlew assembleDebug`
- `./gradlew test`
- `./gradlew :app:testDebugUnitTest`
- `npx -y @google/design.md lint DESIGN.md`
- `git diff --check`
- `git status --short`

Manual emulator QA should be re-run after any user-visible auth, Widget, notification or navigation change. Do not reuse the old WebView / `auth.json` import evidence as current acceptance evidence.

Security notes:

- No real token, cookie, OAuth code, complete `auth.json`, full callback query, raw usage API response, or encrypted session payload should be printed, copied into fixtures, or committed.
- Legacy raw `auth.json` remains relevant only to redaction and diagnostics safety tests.

---

Date: 2026-05-28

## Multi-Account Refresh and Alert QA

Device:

- Android emulator: `emulator-5554`
- Debug package: `com.kmnexus.codexmeter.debug`
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

Mock accounts used:

- `qa-alpha`: Active, `pro`, 5h used 92%, weekly used 40%, credits 42.5.
- `qa-beta`: Active, `plus`, weekly used 100%, credits unlimited, seeded with three failed refresh attempts.
- `qa-gamma`: Active, `team`, 5h used 96%, weekly used 95%, credits 7.25.
- `qa-delta`: Active, `enterprise`, low usage, credits unlimited.
- `qa-auth`: needs re-authentication.

Checks completed:

- Periodic refresh attempted all active mock accounts with safe mock data; because those mock accounts intentionally had no encrypted sessions, the WorkManager refresh path recorded expected auth-required failures instead of reading or printing any session payload.
- Quota alerts triggered for `qa-alpha` and `qa-gamma` 5h warning conditions, and `alert_states` rows were created only for those enabled 5h windows.
- `qa-beta` weekly usage was exhausted but its weekly alert switch was disabled, so no weekly alert de-dupe row was created.
- Changing the warning threshold in Settings triggered alert evaluation immediately without waiting for another API refresh.
- Auth-required and repeated-failure account/error notifications appeared per account with stable notification IDs.
- Persistent notification settings remained separate from quota alert evaluation; widget default behavior is covered by unit tests as current account + 5h.
- Home displayed a real `prolite` snapshot as `Pro 5x`; an expanded mock `pro` account displayed as `Pro 20x`.
- Account management cards were collapsed by default in the emulator UI, then revealed plan, credits, quota summaries, alert switches and management actions after tapping the account header.
- Account card expansion was rechecked after the chevron update: the visible text button was removed, the chevron exposed accessible `展开` / `收起` descriptions, and tapping it expanded and collapsed the details drawer.
- No token, cookie, OAuth code, complete `auth.json`, encrypted session payload, or raw usage API response was read, printed, captured, or committed.

Limitations:

- Successful real API refresh for multiple independent Codex accounts was not completed in emulator QA because the extra accounts were intentionally mock-only and had no real sessions.
