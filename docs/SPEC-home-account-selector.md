# Spec Delta: 首页账号选择器（V2）遗留问题修复与规范同步

Status: implemented

Source spec: [docs/SPEC.md](./SPEC.md)（§12.2 Home screen、§12.3 Account screen、§12.4 Settings screen、§15.2 Delete account）、[DESIGN.md](../DESIGN.md)、[AGENTS.md](../AGENTS.md)、[CLAUDE.md](../CLAUDE.md)

Related PR / Issue: PR #9 (`feat/home-account-selector`, base `d91cd13`) / Issue #7（V2 已批）

---

## Objective

在 PR #9（V2：首页新增账号选择器，废弃全局「当前账号」概念、统一为「默认账号 / 最近查看」、通知移除「跟随当前账号」并惰性锁定）基础上，修复审查发现的 4 项 Required 缺陷与 2 项交互/死代码问题，并将仓库规范文档同步至最新产品行为。

---

## Current Behavior

PR #9（`d91cd13`）已实现首页账号胶囊选择器、移除 Account 页「设为当前」按钮、通知账号惰性迁移及删除回退重绑，但存在以下遗留缺陷：

1. **`HomeViewModel.selectAccount()` 失败仍刷新 + 并发连切竞态**（`HomeViewModel.kt:153-178`）：
   - `if (saved.await()) { ... loadCurrentState() }` 之后缺少 `return`，当 `persistDefaultAccountSelection` 返回 `false` 时依然执行 `refreshSelectedAccount()` 和第二次 `loadCurrentState()`；
   - 未追踪或取消上一次选择任务，快速连切 B → C 时两个协程并发写存储，可能发生旧选择覆盖新选择及冗余刷新；
   - 第 38 行存在未使用的 `import kotlinx.coroutines.yield`。
2. **`HomeAccountSelector` 展示边界与跨供应商区分度不足**（`HomeScreen.kt:157-162, 238-313`）：
   - 选择器放在 `if (contentStatus == Unauthenticated)` 分支之外，冷启动竞态下可能短暂展示「未连接账号 ▾」；
   - 下拉菜单仅渲染 `choice.displayName`，`HomeAccountChoiceUi` 已准备的 `providerIconResId` / `avatarInitial` / `avatarColorKey` 完全未使用，不同供应商下的同名账号无法区分。
3. **删除账号后常驻通知栏未及时刷新**（`AccountDeletionRepository.kt:82-103`、`AppContainer.kt:537-543, 834-839`）：
   - `deleteAccount()` 调用 `rebindNotificationAccountAfterDelete()` 更新了 DataStore 里的 `persistentNotificationAccount`，但未通知 `currentQuotaStatePublisher` 重新发布状态；常驻通知栏仍停留在被删除账号的旧数据（删光最后一个账号时亦未刷新到未登录/清空状态）。
4. **`NotificationPreferencesDataStore.migrateNotificationAccountSelection()` 存在 TOCTOU 竞态**（`NotificationPreferencesDataStore.kt:154-166`）：
   - 先读 `dataStore.data.first()` 判断键为空，再另起 `dataStore.edit { ... }` 直接覆盖写入默认账号，事务块内未二次检查；若期间有并发显式设置通知账号，可能被迁移覆盖；
   - 判断条件仅检查 `PERSISTENT_NOTIFICATION_PROVIDER_ID != null`，未与 `toPersistentNotificationAccount()` 保持一致。
5. **死资源**：`app/src/main/res/values/strings.xml` 与 `values-zh-rCN/strings.xml` 中新增的 `settings_notification_account_description_text` 无任何引用。
6. **文档未同步（违反 `AGENTS.md §11`）**：`AGENTS.md`、`CLAUDE.md`、`DESIGN.md`、`docs/SPEC.md` 仍保留「Account tab 通过 `Set current` 切换当前账号、Home 不提供切换、通知支持跟随当前账号」的旧规范。

---

## New Behavior

### 1. `HomeViewModel.selectAccount()` 串行防竞态与失败早退

- 在 `HomeViewModel` 中维护单个 `private var selectAccountJob: Job? = null`（或在最新选择到来时取消上一个 `selectAccountJob`）。
- 每次调用 `selectAccount(providerId, localAccountId)`：
  1. 取消前一个未完成的 `selectAccountJob`（防止旧选择的后续 reload 或刷新干扰新选择）；
  2. 执行 `persistDefaultAccountSelection(providerId, localAccountId)`；若返回 `false` 或抛出非取消异常，**立即 `return`**，不再触发 `refreshSelectedAccount()` 或二次 `loadCurrentState()`；
  3. 持久化成功后等待当前 `currentStateLoadJob` 并调用 `loadCurrentState()` 立即渲染缓存快照与趋势；
  4. 调用 `refreshSelectedAccount(providerId, localAccountId)` 刷新远端数据，完成后再次等待并 `loadCurrentState()`；
  5. 若期间有新的 `selectAccount` 到来，旧协程被取消，不会再执行多余的刷新或状态覆盖。
- 删除未使用的 `import kotlinx.coroutines.yield`。

### 2. `HomeAccountSelector` 渲染条件与供应商标识

- `HomeScreen` 仅在 `uiState.contentStatus != HomeContentStatus.Unauthenticated && uiState.accountChoices.size > 1` 时渲染 `HomeAccountSelector`。
- `HomeAccountSelector` 下拉项左侧展示账号来源标识：
  - 优先渲染 `choice.providerIconResId`（圆形柔和底色 + 供应商向量图标，与 `AccountLabel` 视觉语言一致）；
  - 若 `providerIconResId == null`，退回圆形纯色首字母头像（使用 `choice.avatarColorKey` 与 `choice.avatarInitial`）。
  - 保证所有 `HomeAccountChoiceUi` 字段都被实际消费，且跨供应商同名账号在下拉列表中一眼可辨。

### 3. 删除账号后实时刷新常驻通知

- 保持 `AccountDeletionRepository` 处于数据层，不直接依赖通知发布器。
- 在 `AppContainer.kt` 的 `accountDeleteUseCase`（或向 `AccountDeletionRepository` 注入 `RepublishingNotificationPreferenceStore` + 删后兜底发布）中：
  - `accountDeletionRepository.deleteAccount(providerId, localAccountId)` 成功返回后，调用 `currentQuotaStatePublisher.publish(currentQuotaStateRepository.loadCurrentState())`（捕获非 `CancellationException` 异常，不因通知刷新失败回滚已完成的删除）。
  - 无论是删除「通知显式绑定的账号（已重绑到新默认账号）」、「删除默认账号」，还是「删除最后一个账号（回落 `Unauthenticated`）」，通知栏与默认微件均立即反映最新状态。
- 同时确认 `AccountDeletionRepository` 仍继续完成 `rebindNotificationAccountAfterDelete()` 持久化写入。

### 4. `NotificationPreferencesDataStore` 事务内原子迁移与死资源清理

- `migrateNotificationAccountSelection()`：
  - 快速路径判断改为 `if (stored.toPersistentNotificationAccount() != null) return stored`；
  - 进入 `dataStore.edit { mutable -> ... }` 后**在事务块内重新检查** `if (mutable.toPersistentNotificationAccount() != null) return@edit`，并从 `mutable` 中读取最新 `CURRENT_PROVIDER_ID` / `CURRENT_LOCAL_ACCOUNT_ID`，仅当两者均非空且通知账号仍未设置时才写入，彻底消除 TOCTOU 竞态。
- 从 `app/src/main/res/values/strings.xml` 和 `app/src/main/res/values-zh-rCN/strings.xml` 删除未使用的 `settings_notification_account_description_text`。

### 5. 规范文档同步

同步更新以下 4 份权威文档（保持 `AGENTS.md` 与 `CLAUDE.md` 一致）：
- `AGENTS.md`（§7 UI/UX baseline）：更新账号切换入口与首页账号选择器说明；
- `CLAUDE.md`（UI rules）：与 `AGENTS.md` 同步；
- `DESIGN.md`（Components §2/§3/§4/§8/§9、Screen Specifications Home/Accounts、Interaction Rules Navigation/Account Switching）：更新首页账号选择器、Account 页移除 `设为当前` 改为「默认账号」、通知固定绑定账号无「跟随当前」；
- `docs/SPEC.md`（§6.4 显示/告警边界、§12.2 Home screen、§12.3 Account screen、§12.4 Settings screen、§15.2 Delete account）：对齐首页账号选择器、默认账号、删除跨供应商回退与通知重绑行为。

---

## Success Criteria

1. `HomeViewModel.selectAccount()` 在 `persistDefaultAccountSelection` 返回 `false` 或抛异常时，不调用 `refreshSelectedAccount`，不发起冗余 `loadCurrentState()`。
2. 连续快速调用 `selectAccount(B)` → `selectAccount(C)` 时，前一次选择协程被取消，最终持久化与 UI 展示锁定在 C，且不会触发 B 的过时刷新/加载。
3. `HomeScreen` 在 `Unauthenticated` 状态下即使 `accountChoices` 非空也不渲染 `HomeAccountSelector`；下拉菜单项展示供应商图标（无图标退回首字母头像）。
4. 删除常驻通知绑定的账号（或最后一个账号）后，`currentQuotaStatePublisher.publish(...)` 被触发一次，通知状态同步至回退账号或 `Unauthenticated`；发布失败不导致删除抛错。
5. `NotificationPreferencesDataStore` 在并发显式写入通知账号时，惰性迁移不会覆盖显式选择；半写键状态可正确修复。
6. 无未使用 import（`yield`）与未使用字符串资源（`settings_notification_account_description_text`）。
7. `AGENTS.md`、`CLAUDE.md`、`DESIGN.md`、`docs/SPEC.md` 全部与代码一致，无遗留「`Set current` / `设为当前` / 首页不提供切换 / 跟随当前账号」的矛盾表述。
8. `./gradlew :app:testDebugUnitTest` 与 `./gradlew assembleDebug` 全绿。

---

## Boundaries

- **Always**：严格 TDD（先写失败单测再改实现）；遵守 `AGENTS.md` 包边界与安全红线；中英文字符串双份同步。
- **Ask first**：重构 `SettingsViewModel.kt`（1100+ 行拆分或 6 个版本计数器重构）；修改 DataStore key 名称或 Room schema。
- **Never**：在文档或测试中泄露敏感凭据；修改与 PR #9 无关的模块；强制 push 覆盖远端历史。

---

## Out of Scope (Deferred)

- `SettingsViewModel.kt` 体积拆分及 6 个并发防抖计数器的结构化重构（作为独立 refactor PR 跟进，避免扩大 PR #9 变更面）。
- `CodexMeterWidgetConfigurationActivity` 在 PR #8 合并后的 rebase 冲突解决：在终验阶段演练合并兼容性，实际 rebase/push 待用户确认后执行。
