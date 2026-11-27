# 04 · WebView OAuth 拦截与回环认证

## Sub-features

- Claude OAuth PKCE：内嵌 WebView 拦截 `https://console.anthropic.com/oauth/code/callback`。
- Claude manual paste：页面可把 `code#state` 作为可复制文本交给用户手贴。
- Antigravity OAuth PKCE：拦截 `http://127.0.0.1:8089/callback` 的 loopback 跳转。
- 通用回调判定：`evaluateOAuthCallback()` 统一分类 Capture / Denied / StateMismatch / NotCallback。
- 主框架加载失败兜底：`onReceivedError` 只在 main frame 报错。
- 通用 loopback server：`LoopbackCallbackServer` 供不能内嵌拦截的外部浏览器流程使用。

## User POV

- 用户主要在 App 内完成授权，不需要知道回调 URL 细节。
- Claude 若 WebView 卡住，可以改用浏览器打开并手贴 `code#state`。
- 授权被拒、state 不匹配、页面加载失败，用户会看到明确错误而不是空白页。
- 正在换 token/验证时，会有 verifying overlay 防止重复提交。

## CLI 驱动命令

- `seed-fixture --scenario codex_cf_blocked`：虽属 Codex，但也验证登录受阻类 UI 基线。
- `snapshot --json`：检查 verifying overlay、错误行、manual paste 输入框是否存在。
- `tap --text`：触发 Open in browser / Submit code / Retry 等控件。
- `screenshot --name webview-oauth`：保存授权页与错误态截图。

## Gotchas

- 回调优先级是 **`error` 永远先于 `code+state`**：即使 URL 同时带 code 和 error，也判 Denied。测试 `OAuthCallbackDecisionTest` 已覆盖。
- 只有严格匹配 `state` 才 Capture；少了 `state`、`code` 或 state 不一致，都是 `StateMismatch`。
- Claude 使用 `appendStateToCode=true`，抓到的是 `code#state`；Antigravity 则是纯 `code`。
- `127.0.0.1:8089/callback` 并不真的依赖本机端口监听；它在 WebView 导航前被拦截，源码注释明确「nothing actually binds」。
- `LoopbackCallbackServer` 是另一条路径：只绑 `127.0.0.1`、随机临时端口、单次请求后关闭，不能假设它处理 WebView 内拦截。
- `onReceivedError` 只处理 main frame；子资源失败被忽略，避免误报。
- WebView 对 consent 页会 `requestFocus()`，因为 Claude 授权按钮依赖 `document.hasFocus()`。
