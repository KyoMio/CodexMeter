# 06 · 诊断导出与脱敏

## Sub-features

- 设置页诊断卡片：展开、复制、重新检查。
- 诊断输入聚合：账号状态、最近刷新、WorkManager、通知权限、设备环境、保留策略、widget 数等。
- 敏感信息统一脱敏：复制出口集中走 `DiagnosticsRedactor.redact()`。
- 诊断安全哈希：账号/attemptId 以 `sha256:<hash>` 形式展示。

## User POV

- 用户可以把诊断信息复制给开发者，而不必担心把登录态、cookie、授权码一起发出去。
- 诊断里看到的是状态、时间、错误码、hash 后的账号标识，不是完整身份或 token。
- 点「重新检查」会刷新当前诊断快照，而不是改账号或重新登录。

## CLI 驱动命令

- `snapshot --json`：断言诊断卡存在展开/复制/重新检查控件。
- `tap --text`：触发复制与 recheck，验证 UI 状态更新。
- `logcat-safe`：验证日志流中没有 bearer、cookie、auth code、refresh/access token。
- `screenshot --name diagnostics`：保存诊断卡展开态截图。

## Gotchas

- 真正复制前在 `SettingsViewModel.copyDiagnostics()` 调用 `DiagnosticsRedactor.redact(rawDiagnostics)`；不要绕过这个出口自行拼字符串。
- `DiagnosticsRedactor` 的规则不是单条正则，而是多层：整段敏感 JSON 直接折叠、labeled payload 折叠、callback state 片段折叠、通用 pattern 替换。
- 安全 operational JSON 只有白名单 key 才能保留原样；任何不在白名单或含复杂结构的 JSON 都可能被整体 `[REDACTED]`。
- 允许的独立 state 值非常窄：`failed / authRequired / stale`；OAuth state、expected/actual state 在上下文中会被脱敏。
- 账号标识不是明文：`accountIdHash` / `deviceCodeLoginAttemptId` 以 `sha256:<diagnosticsSafeHash>` 输出。
- `DiagnosticsRedactorTest` 覆盖大量正向/反向样例；改规则必须同步补测试，尤其「保留安全错误码」与「折叠敏感 payload」两类。
