# Spec Delta: 全链路 0% / Depleted 额度窗口一致处理

Status: implemented

Source spec: [docs/SPEC.md](./SPEC.md)（§6.4 Quota window、§10.3 Current state factory、§11.1 Alert policy、§12.2 Home screen、§12.3 Account screen、§13.1 MVP widget）

Related PR / Issue: PR #8 (`fix/widget-setup-show-depleted-window`, base `94bc943`) / Issue #6

---

## Objective

彻底解决「额度耗尽（剩余 0% / 余额耗尽，即 `QuotaWindowAvailability.Depleted`）时窗口在各端消失、状态误报或告警失效」的问题，使其覆盖全部 11 个已接入供应商及全链路所有消费面（Widget Setup、Widget 渲染、Home 卡片状态、Account 摘要与告警开关、24h 趋势图、额度耗尽告警、常驻通知回退，以及 Antigravity proto3 省略 `0.0` 的客户端边界）。

---

## Current Behavior

### 1. 11 个供应商在额度为 0 时的现状

| 供应商 | Mapper | 0% / 0 余额时的 `availability` | 现状问题 |
| :--- | :--- | :--- | :--- |
| Antigravity | `AntigravityQuotaMapper` | `Depleted` | 受所有下游 `Available`-only 过滤影响；且客户端在全 bucket 省略 `remainingFraction` 或 `mergeModels` 含省略桶时存在丢失/误回退 `1.0` |
| Claude | `ClaudeUsageMapper` | `Depleted`（`usedPercent >= 100`） | 受所有下游 `Available`-only 过滤影响 |
| Cursor | `CursorUsageMapper` | `Depleted`（`usedPercent >= 100`） | 同上 |
| DeepSeek | `DeepSeekBalanceMapper` | `!is_available` → `Depleted` | 同上 |
| Kimi | `KimiQuotaMapper` | `remaining <= 0` 或 `used >= limit` → `Depleted` | 同上 |
| Kimi Code | `KimiCodeUsageMapper` | `usedPercent >= 100` / `remaining <= 0` / `used >= limit` → `Depleted` | 同上 |
| MiniMax | `MiniMaxUsageMapper` | `totalRemaining <= 0` / `usedPercent >= 100` → `Depleted` | 同上 |
| z.ai Coding Plan | `ZaiQuotaMapper` | `usedPercent >= 100` → `Depleted` | 同上 |
| Codex | `CodexUsageMapper` | `Available`（即使 100% 已用） | 不受影响，但与前 8 家语义不统一 |
| Grok | `GrokMapper` | `Available`（即使 100% 已用） | 同上 |
| z.ai API 余额 | `ZaiBalanceMapper` | `Available`（即使 0.00） | 同上 |

### 2. 下游消费层现状（基于 `main @ a12b346` + PR #8 @ `94bc943`）

- **领域模型 (`QuotaModels.kt`)**：缺少统一的可展示谓词；`QuotaWindow.canAlert()` 仅放行 `Available`，直接拒绝 `Depleted`。
- **Widget Setup & 渲染 (`WidgetConfigurationScreen.kt`、`WidgetQuotaStateFactory.kt`)**：PR #8 已放行 `Depleted`，但存在两处缺口：
  1. `buildWidgetWindowOptions()` 未校验可展示值，与 `WidgetQuotaStateFactory.isDisplayable()` 过滤条件不一致（无值 `Depleted` 窗口配置页可选但微件不渲染）；
  2. `percentTone()` 在判断空值前直接对 `Depleted` 返回 `Danger`，且未复用共享谓词。
- **Account 页 (`AccountViewModel.kt:574-578`)**：`displayableWindows()` 写死 `availability == Available`，导致 8 家供应商任一窗口变 0% 时，账号管理页的配额摘要 (`quotaSummaries`) 和告警开关 (`alertToggles`) 直接消失。
- **Home 页 (`HomeViewModel.kt:441-459`)**：`toQuotaStatus()` 首行 `if (availability != Available) return Unavailable`，导致 8 家供应商 0% 卡片虽显示数字 `0%` / `¥0.00`，但底部状态和圆点误显灰色「暂不可用 (`Unavailable`)」而非红色「已耗尽 (`Exhausted`)」。
- **额度告警 (`AlertPolicy.kt:87`)**：`if (window.availability != Available) return null`，导致 8 家供应商直接跌至 0% 时无法触发 `AlertLevel.Limit` 通知。
- **24h 趋势图 (`HomeTrendHistoryRepository.kt:62, 87`)**：`scalarDiffPoints` 和 `modelBucketSumPoints` 只取 `Available`，导致消耗至 0% 的最后一段增量被丢弃。
- **常驻通知回退 (`CurrentQuotaStateRepository.kt:149`)**：`resolveNotificationPrimaryWindow()` 走 fallback 时只优先 `Available`，会跳过已耗尽的主候选窗口。
- **Antigravity 客户端 (`AntigravityQuotaSummaryDto.kt:28-31`、`AntigravityQuotaClient.kt:197-215`)**：
  1. proto3 JSON 会省略默认 `0.0`；若某账号 `:retrieveUserQuotaSummary` 的所有非 disabled bucket 全部耗尽（均无 `remainingFraction`），`hasKnownQuotaSummary()` 返回 `false`，错误放弃 summary（丢失 5h/weekly 拆分）；
  2. 在 `fetchPerModelQuota()` 校验通过后的 `mergeModels()` 中，若某个真实 bucket 因 0% 省略了 `remainingFraction`，`bucket.remainingFraction ?: model.remainingFraction` 会错误保留 placeholder 的 `1.0`，而仅存在于 `buckets` 的 0% 模型会被 `?: continue` 直接丢弃。

---

## New Behavior

### 1. 领域层统一谓词 (`QuotaModels.kt`)

- 为 `QuotaWindowAvailability` 新增计算属性：
  ```kotlin
  val isPresentable: Boolean
      get() = this == Available || this == Depleted
  ```
- 为 `QuotaWindow` 新增扩展/成员方法 `hasDisplayableValue(): Boolean`：
  - 若 `!availability.isPresentable` → `false`；
  - `Balance` → `!balanceAmount.isNullOrBlank()`；
  - `UsageCount` → `displayPercent != null || (usedCount != null && limitCount != null)`；
  - 其余（`Percent`、`MultiModelFraction`）→ `displayPercent != null`。
- `QuotaWindow.canAlert()`：
  - 将首行 `availability != QuotaWindowAvailability.Available -> false` 改为 `!availability.isPresentable -> false`；
  - **保留** `Balance` 的 `amount > 0.0` 约束（见 `docs/SPEC.md §6.4` 与既有测试 `CurrentQuotaStateFactoryTest.balance window with zero amount is not alertable`），避免新导入的 0 余额 API Key 账号首次刷新即误报耗尽告警；Percent / UsageCount / MultiModelFraction 在 `Depleted`（且具备合法百分比/计数与 `resetAt`）时允许告警。

### 2. Widget Setup & 渲染对齐

- `WidgetConfigurationScreen.kt`：`buildWidgetWindowOptions()` 使用 `window.hasDisplayableValue()` 过滤（与 `WidgetQuotaStateFactory` 严格一致），仍标记 `depleted = window.availability == QuotaWindowAvailability.Depleted` 并在选项文案后展示「· 耗尽 / Exhausted」提示；使用文件顶部 `import` 替代全限定类名。
- `WidgetQuotaStateFactory.kt`：
  - `isDisplayable()` 直接委托 `hasDisplayableValue()`（对 `UsageCount` 仍保持以 `displayPercent != null` 为微件渲染前提，或确保 `UsageCount` 只有 `displayPercent != null` 时渲染百分比，避免微件出现 `--`）；
  - `percentTone()` 先校验可展示数值（`amount == null` / `percent == null` 或 `!availability.isPresentable` 返回 `Neutral`），再处理 `availability == Depleted -> Danger` 与阈值分档。

### 3. Home / Account / AlertPolicy / Trend / Notification 全链路支持

- **Account 页 (`AccountViewModel.kt`)**：`displayableWindows()` 改为 `it.hasDisplayableValue()`；0% / 耗尽窗口在账号卡片摘要和告警开关中保持可见且可切换。
- **Home 页 (`HomeViewModel.kt`)**：`toQuotaStatus()` 首行改为 `if (!availability.isPresentable) return HomeQuotaStatus.Unavailable`；当数值存在且 `availability == Depleted` 时返回 `HomeQuotaStatus.Exhausted`（`HomeStatusTone.Danger`）；若 `Depleted` 但无数值仍返回 `Unavailable`。
- **告警策略 (`AlertPolicy.kt`)**：`evaluateWindow()` 第 87 行改为 `if (!window.availability.isPresentable) return null`，使 8 家产出 `Depleted` 的供应商在跌至 0%（或低于 warning 阈值）时正常产出 `AlertLevel.Limit` / `AlertLevel.Warning`。
- **24h 趋势图 (`HomeTrendHistoryRepository.kt`)**：`scalarDiffPoints()` 和 `modelBucketSumPoints()` 均将 `it.availability == Available` 改为 `it.availability.isPresentable`，确保消耗到 0% 的增量点计入趋势，且 Antigravity 已耗尽 family 的 `modelBuckets` 不丢失。
- **常驻通知回退 (`CurrentQuotaStateRepository.kt`)**：`resolveNotificationPrimaryWindow()` 优先匹配 `it.isPrimaryCandidate && it.availability.isPresentable`，避免跳过已耗尽的主窗口。

### 4. Antigravity proto3 全 0 兜底

- **`AntigravityQuotaSummaryDto.hasKnownQuotaSummary()`**：
  - 保留「空 `groups` / 空 `buckets` / 全 `disabled == true`」返回 `false`；
  - 对于 `bucket.disabled != true` 的 bucket，只要 `bucket.remainingFractionOrNull() != null` **或**具备明确配额标识/周期信号（`!bucket.resetTime.isNullOrBlank() || !bucket.window.isNullOrBlank() || !bucket.bucketId.isNullOrBlank() || !bucket.id.isNullOrBlank()`），即视为包含已知配额（当所有非 disabled bucket 均因 0% 省略 `remainingFraction` 时，依然走 `mapSummary` 产出 `usedPercent = 100, availability = Depleted` 的 5h/weekly 窗口）。
- **`AntigravityQuotaClient.mergeModels()`**：
  - 保留既有 placeholder 防护契约（`AntigravityQuotaClientTest.returns an empty model list when the verification quota reports no fractions`）：若 `:retrieveUserQuota` 的全部 bucket 均无 `remainingFraction`，仍返回 `emptyList()`；
  - 一旦 `:retrieveUserQuota` 校验通过（至少一个 bucket 带 `remainingFraction`），在 `mergeModels()` 中：
    - 已有模型命中 `bucketByModel[model.modelId]` 时，使用 `bucket.remainingFraction ?: 0.0`（不再回退到 placeholder 的 `model.remainingFraction` `1.0`）；
    - 仅存在于 `bucketByModel` 的模型不再因 `bucket.remainingFraction == null` 而跳过，改为以 `remainingFraction = bucket.remainingFraction ?: 0.0` 加入结果列表。

---

## Success Criteria

1. **Domain 契约**：`QuotaWindowAvailability.Available.isPresentable == true`、`Depleted.isPresentable == true`、`Missing/DecodeFailed/Unsupported.isPresentable == false`；`QuotaWindow.hasDisplayableValue()` 覆盖 `Percent`、`Balance`、`UsageCount`、`MultiModelFraction` 及无值边界；`canAlert()` 对 `Depleted` 百分比/计数窗口返回 `true`，对 `Balance == 0.00` 保持 `false`。
2. **Widget 一致性**：`buildWidgetWindowOptions()` 保留有值的 `Available`/`Depleted` 窗口并过滤无值 `Depleted` 与 `Missing/Unsupported/DecodeFailed`；选中 `Depleted` 窗口后微件渲染 `0%` 或 `0.00` 且 tone 为 `Danger`。
3. **Account 页**：8 家产出 `Depleted` 的供应商窗口在 0% 时仍出现在 `quotaSummaries` 与 `alertToggles` 中（可展示 `0%` / `¥0.00` 且告警开关不消失）。
4. **Home 页**：`Depleted` 的百分比窗口（`usedPercent = 100`）与余额窗口（`0.00`）在 Home 页渲染为 `HomeQuotaStatus.Exhausted` / `HomeStatusTone.Danger`（而非 `Unavailable` / `Neutral`）。
5. **告警与通知**：`Depleted`（`usedPercent = 100`、有 `resetAt`）触发 `AlertLevel.Limit`；`resolveNotificationPrimaryWindow()` 回退时优先保留 `isPrimaryCandidate` 的 `Depleted` 窗口。
6. **趋势图**：从 `Available` 消耗至 `Depleted`（如 `80% -> 100%` 或 `remaining 0.3 -> 0.0`）在 24h 趋势图中正确产生正增量点，包括 `MultiModelFraction` 多 family 窗口。
7. **Antigravity proto3 全 0**：
   - Summary 全部非 disabled bucket 省略 `remainingFraction` 时，`hasKnownQuotaSummary() == true`，产出 `Depleted` 的 5h/weekly 窗口；
   - 全 disabled 或空 bucket 时 `hasKnownQuotaSummary() == false`；
   - `fetchPerModelQuota` 校验通过后，`mergeModels` 将省略 `remainingFraction` 的已耗尽 bucket 正确合入为 `0.0` 而非 `1.0` 或被丢弃；同时既有测试 `returns an empty model list when the verification quota reports no fractions` 继续保持全绿。
8. **全量验证**：`./gradlew :app:testDebugUnitTest` 与 `./gradlew assembleDebug` 全绿。

---

## Boundaries

- **Always**：严格遵循 TDD（先写失败单测再写最小实现）；保持 `ui`/`widget`/`notification`/`domain`/`providers` 包边界不变；仅改动与本 Delta Spec 相关的文件。
- **Ask first**：修改 Room schema 或 DataStore 存储键；改动 Codex / Grok / z.ai Balance mapper 的既有 `availability` 语义。
- **Never**：向日志、UI、测试夹具或诊断中写入任何 token / cookie / 原始响应体；破坏已有的 placeholder 防护测试或 0 余额免误报契约；引入新依赖或越层访问。

---

## Out of Scope (Deferred)

- 将 `CodexUsageMapper`、`GrokMapper`、`ZaiBalanceMapper` 在 0% / 0 余额时改为标注 `Depleted`：它们当前标注 `Available` 且功能正常，本轮不做无必要行为变更，避免扩大回归面。
- 重构 `WidgetConfigurationScreenState.DEFAULT_WINDOW_OPTIONS`（无快照账号的 Codex 5h/weekly 兜底列表）：属于无快照初始化体验优化，单列后续改进。
- `CodexMeterWidget.statusLabelResId()` 区分 `Warning` 与 `Exhausted` 文案：Widget 现有设计将 `Danger` 统一映射为紧张文案，本轮不改 Widget 视觉契约。
