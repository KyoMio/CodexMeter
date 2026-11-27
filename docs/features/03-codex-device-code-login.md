# 03 · Codex Device-Code 登录（外部浏览器流）

## Sub-features

- 请求 device code、展示 user code 与 verification URI。
- 外部浏览器/Custom Tab 打开授权页；App 内按 interval 轮询授权状态。
- 授权码换取 token，随后保存会话并验证 usage。
- 重新登录（Relogin）与账号不匹配决策（AccountMismatchDecision）。
- 取消、过期、验证失败重试。

## User POV

- 用户复制/记住一串短码，点按钮跳到浏览器完成 OpenAI 登录，然后回 App 等待自动接入。
- 若登录被 Cloudflare 拦下，用户会很快看到「登录受阻」类错误，而不是 App 卡着转圈十几分钟。
- 若误登了别的账号，App 会明确提示账号不一致，由用户决定是否仍添加。
- 登录过程不会清掉已有合法会话；失败/取消只影响本次 attempt。

## CLI 驱动命令

- `seed-fixture --scenario codex_cf_blocked`：模拟 Cloudflare challenge，验证 UI 快速失败且停止轮询。
- `snapshot --json`：断言 user code、verification URI、取消按钮与错误文案存在。
- `tap --text` / `back`：走取消登录路径，验证状态机回到 Cancelled。
- `logcat-safe`：确认日志没有 userCode、authorizationCode、codeVerifier、token。

## Gotchas

- Cloudflare 识别标记是 `cf-chl`、`__cf_chl`、`just a moment`（见 `CodexDeviceCodeClient.CLOUDFLARE_CHALLENGE_MARKERS`）；**源码里没有字面量 `cf-mitigated`**。命中后以 `error_login_blocked` 快速失败。
- 403/404 不一律失败：若 body 是 pending 类 JSON，仍视为 Pending；只有 HTML challenge 或明确错误才失败。
- verification URI 会做严格校验：scheme/host/path 必须匹配预期，且不允许 query/fragment；异常 URI 直接 `codex_device_code_unsafe_verification_uri`。
- `userCode`、`deviceAuthId`、`authorizationCode`、`codeVerifier` 在 `toString()` 中显式 `[REDACTED]`；诊断里 attemptId 也会 hash。
- 轮询有并发保护：`pollInFlightAttemptId` 防止重入；UI 端 `attemptId` 变更会让旧轮询协程退出。
- `isVerifying` 属于 WebView/PKCE 核验态 UI，不是 device-code 轮询本身的替代；不要把两套状态混为一谈。
