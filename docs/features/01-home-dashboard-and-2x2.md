# 01 · Home 仪表盘、卡片与 2×2 布局

## Sub-features

- Home 顶部账号选择器（Home account selector）：切换默认查看账号，并通过 `AccountMutationRepository.persistDefaultAccountSelection` 持久化写入 DataStore，同步更新全局默认账号。
- 配额卡片区：每个可展示窗口一张卡，按 `Percent / Balance / UsageCount / MultiModelFraction` 分渲染。
- 趋势图：跟随当前用户看到的主窗口加载历史点。
- 状态文案：Fresh / PossiblyStale / Expired / ErrorWithLastKnownGood / AuthRequired / NoData。
- 刷新动作与主操作按钮（AuthRequired/Unauthenticated 时给 LoginToCodex）。
- 英雄玻璃层与状态卡片（视觉壳，不改变数据语义）。

## User POV

- 用户看到的是「按窗口卡片网格」：一屏通常两张一排，窗口多时成 2×2；奇数尾卡独占一行。
- Antigravity 账号常见四张卡：Gemini 5h、Gemini weekly、Claude/GPT 5h、Claude/GPT weekly；这是 **数据产生 4 个窗口后的排版结果**，不是写死的 2×2 组件。
- 卡片只显示窗口剩余百分比/余额/次数，不直接显示模型明细；模型 bucket 明细在账号页。
- 账号选择器切到其他账号时，持久化写入 DataStore 并立即切换卡片与趋势；被杀进程重开后保持上次选中的账号。

## CLI 驱动命令

> 以下命令由 `scripts/control-codexmeter.mjs`（CM-5）提供，用于 debug 包的端到端验证。

- `doctor`：确认模拟器/真机、`.debug` 包与 adb 环境可用。
- `install-launch`：安装并启动 `com.kmnexus.codexmeter.debug/.MainActivity`。
- `seed-fixture --scenario antigravity_2x2_depleted`：注入 Antigravity 双 family 5h+weekly 且部分耗尽的场景，验证 Home 卡片布局与耗尽态。
- `seed-fixture --scenario multi_account_race`：注入双账号静态初始态（Codex 与 Antigravity 各 1 个账号，默认选中第二个），作为多账号切换场景的初始测试数据。
- `snapshot --json`：抓取无障碍树，断言存在账号选择器与至少一张 quota card。
- `screenshot --name home-2x2`：保存 Home 2×2 卡片截图。

## Gotchas

- **`MAX_SUMMARY_WINDOWS=4` 已删除**：commit `a926e0b` 明确移除账号页四窗口截断（原值确为 4），因为 Claude 返回 5 个窗口时 Sonnet 摘要与告警开关会丢。当前 main 中 `displayableWindows(snapshot).map { ... }` 无 `take(MAX_SUMMARY_WINDOWS)`。
- Home 的 2×2 不是「最多 4 个窗口」：`HomeQuotaCards` 用 `quotaCards.chunked(2)` 两两成排，5 个窗口也会排成 2+2+1。
- 主窗口不是简单取第一个：`CurrentQuotaStateFactory` 先找全局配置的 `primaryWindowId`；Home 趋势图若找不到，会回退到快照自己的 `isPrimaryCandidate` 窗口，再回退第一个窗口。
- `MultiModelFraction` 卡当前按 Percent 卡渲染，模型求和逻辑只影响趋势计算，不改变卡片类型。
- 耗尽窗口也算可展示：`availability.isPresentable` 包含 `Available` 与 `Depleted`，所以 0% 卡仍会出现在 Home。
