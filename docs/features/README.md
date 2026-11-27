# CodexMeter Feature Map

本目录是面向开发与验收的 **功能事实地图**：只记录仓库当前代码里真实存在的行为、边界与坑位，**不是未来设计稿**。写文档前已对照 `main` 分支源码逐项核对；若代码再改，请同步更新对应文件。

## 总览

| 文档 | 覆盖范围 | 关键事实 |
| --- | --- | --- |
| `01-home-dashboard-and-2x2.md` | Home 卡片、趋势图、账号选择器、主窗口判定 | 无固定 `MAX_SUMMARY_WINDOWS=4`；2×2 是窗口数量排版结果 |
| `02-provider-mappers.md` | 11 个 Provider DTO 映射、resetTime 兜底 | Antigravity proto3 `0.0` 省略即视为耗尽 |
| `03-codex-device-code-login.md` | Codex 外部浏览器 device-code 流 | Cloudflare 拦截快速失败；源码标记不是字面量 `cf-mitigated` |
| `04-webview-and-loopback-auth.md` | Claude/Antigravity OAuth 拦截、回环回调 | `error` 参数优先于 `code+state`；`127.0.0.1` 回调在 WebView 内拦截 |
| `05-widget-and-notifications.md` | Glance Widget 尺寸变体、低噪通知 | 4 种尺寸变体；通知按 `(账号,窗口,resetAt,threshold)` 去重 |
| `06-diagnostics-and-redaction.md` | 诊断导出脱敏 | `DiagnosticsRedactor` 是唯一出口；复制前统一过脱敏器 |

## 每份文档固定四节

- **Sub-features**：这个模块实际包含哪些子能力。
- **User POV**：用户视角能看到/感到什么。
- **CLI 驱动命令**：未来 `scripts/control-codexmeter.mjs`（CM-5）应提供的验证命令；当前 CLI 尚未交付，命令名为约定规格，作为验收输入。
- **Gotchas**：最容易踩错的边界、反直觉事实与历史变更。

## 重要纠偏：先读这两条

1. **`MAX_SUMMARY_WINDOWS=4` 已不在 main**：commit `a926e0b` 移除了账号页 4 窗口截断，因为 Claude 5 个窗口会丢 Sonnet 卡片。任何新文档或 PR 不得再把它当现状。
2. **Cloudflare 拦截标记不是 `cf-mitigated`**：Codex device-code 客户端识别的 body 标记是 `cf-chl`、`__cf_chl`、`just a moment`（见 `CodexDeviceCodeClient`），只要命中就以 `error_login_blocked` 快速失败，不再轮询到死。
