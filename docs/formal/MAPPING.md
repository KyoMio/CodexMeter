# CM-7 状态转换 ↔ 代码实现逐条对照

本文是审计命门：每条 TLA+ 动作指回实现的具体位置（文件:行号/函数），并说明抽象时丢掉的细节是「真实竞态被漏抽象」还是「实现细节可忽略」及依据。

行号以提交时工作区为准（feat/cm7-formal-models 分支基线 7e9a4ce）。

## 一、RefreshCoordinator.tla ↔ 实现

| TLA+ 动作 | 实现位置 | 语义对照与抽象说明 |
|---|---|---|
| `StartRefresh` | `refresh/RefreshCoordinator.kt:54-86` `refresh()` 主流程；single-flight 查重 `inFlight` map 在 `:57-61`；`captureReadToken` 在 `:77` | epoch 在 provider I/O 前拍（`core/concurrency/VersionedStateGuard.kt:62-63` 原子读 generation）。模型里会话捕获 `epochGen=gen` 忠实此点。`MaxSessions`/`nextVer` 上界仅为状态数控制，与 epoch 语义正交（版本新鲜度不影响 staleness 判定）。 |
| `FinishIO`（成功分支写快照） | `refresh/RefreshCoordinator.kt:147-190` `recordSuccess`；快照写 `:158` `snapshotStore.save(snapshot)`，attempt 写 `:174` | append-only：`data/repository/RoomQuotaStores.kt:46-48` 只 insert 不 update。模型里 snap 单调升忠实此点。**简化声明**：`existsAndNotDeleted` 检查（`:155`）与写之间的 check-then-act 窗口（W1 孤儿行风险）未建模——它影响的是耐久层垃圾行而非呈现层不变量，见 §四未建模项。 |
| `FinishIO`（失败分支不写快照） | `refresh/RefreshCoordinator.kt:192-232` `recordFailure`；`lastKnownGood` 读 `:199`，失败 attempt 写 `:221`；**快照在此函数中无任何写路径** | 不变量③的机制基础：失败路径在机制上不存在快照写入。模型里失败分支 snap 不变忠实此点。`markNeedsReauth`（`:222-224`）折叠未建模（账号状态翻转不进呈现帧，见 §四）。 |
| `EnterValidate` | `refresh/RefreshCoordinator.kt:234-274` `publishCurrentState`；`commitIfFresh` 调用点 `:262` | 抢锁步建模 `commitMutex` 竞争（`VersionedStateGuard.kt:43`）——`commitSerialized` 重发共用同一锁（`:147-152`），排队语义真实存在。 |
| `ValidateCommit` | `core/concurrency/VersionedStateGuard.kt:116-130` `commitIfFresh`：**「重读 selected + isFresh 校验 + write」三步在 commitMutex 内不可分割**；`isFresh` 三条件在 `:105-110`（gen 相等 ∧ inFlightEdits=0 ∧ epoch.account=selected） | ★ 建模关键决策：**合并为单原子动作**。初版曾拆分为校验/写两步，TLC 立即给出反例——但那是抽象错误不是实现 bug：实现里临界区原子性由 mutex 保证，拆分后编辑插入校验与写之间的交错在现实中不存在。这是「抽象错了 TLC 全绿也没用」的反向教训：抽象错了 TLC 反绿也骗人。修正后按实现忠实建模为原子步。 |
| （stale 丢弃路径） | 同上当 `isFresh` 失败时 write 完全不执行，`discardedWriteCounter` 计数（`:127`） | 模型里 `pubViolations` 影子计数同语义。探针验证：合法交错中丢弃路径真实可达（`refreshprobe` 反例证明 pubViolations>0 存在），不是「校验永远通过所以没东西可抓」。 |
| `BeginEdit` | `core/concurrency/VersionedStateGuard.kt:71-80` `beginEdit()`：CAS 循环原子完成 `generation+1 ∧ inFlightEdits+1` | 调用点：`data/repository/AccountMutationRepository.kt:45`（切换选中）、`:98`（重命名）、`AccountDeletionRepository.kt:31`（删除）、`SessionLoginUseCase.kt:116,153`（导入）等。模型折叠为单一编辑类型——它们的 epoch 语义相同（见下）。 |
| `SwitchSelection` | `data/repository/AccountMutationRepository.kt:52` `updateCurrentAccountSelection` | 编辑窗口内的 DataStore 写。模型只允许在 `editing` 时发生，忠实「beginEdit 包裹全程」的实现结构。 |
| `EndEdit` | `core/concurrency/VersionedStateGuard.kt:87-99` `endEdit()`：gen 再 +1 | ★ 关键语义：编辑窗口内捕获的 epoch 在编辑结束后**永久** stale（+2 效果），窗口前捕获的同样 stale（+1 至少一次）。`VersionedStateGuardTest.kt:100-115` overlapping-edits 测试是开发方对此的预期行为描述。 |
| `EditRepublish` | `app/CurrentAccountQuotaStateRepublisher.kt:29-72`（rename 后重发，自带新 epoch 的 guarded commit）；`app/Republishing*UseCase.kt`（删除/清史/偏好用 `commitSerialized` 无条件重发） | ★ 活性假设 W4：呈现层终局正确依赖「编辑方必补发」。模型里 `EditRepublish` 非确定性发生（可能迟到），忠实「不保证立刻」的现实。W3 的「重读在锁外、可能读到次新」折叠进 ver 取 snap 当前值（单调只会更新不更旧）。 |
| perAccountEffects（折叠不占状态位） | `refresh/RefreshCoordinator.kt:253` `publishAccountEffects(state)` 在 guard 之外无条件执行 | 如实记录：这是实现的有意设计（`StaleWriteBackRegressionTest.kt:249-293` 固定为预期）——告警/widget 属于被刷账号本身，哪怕已切走。它不写呈现层当前账号状态，故不进 `published`。widget 侧另有缓解：`WidgetQuotaStateUpdater.kt:34` 重读耐久层而非直接用传入数据。 |

## 二、DeviceCodeOAuth.tla ↔ 实现

| TLA+ 动作 | 实现位置 | 语义对照与抽象说明 |
|---|---|---|
| `StartLogin` | `providers/codex/auth/CodexDeviceCodeLoginUseCase.kt:125-132` `startLogin()` | **无条件覆盖** activeAttempt（`:126-129`），旧 attempt 的在飞 poll 全部变 stale。`pollInFlightAttemptId` 重置（`:130`）。模型用 `attemptEpoch+1` 建模代次。 |
| `CompleteRequestDeviceCode` / `FailRequestDeviceCode` | 成功 `:156-164`（isLatestLocked 检查后写 AwaitingUserAuthorization）；失败 `:136-146`（含 cf blocked、端点 invalid） | stale 复查在每个写点——模型里 ProcessResponse 的 `fresh` 判定同构。 |
| `StartPoll` | `:168-181` `pollLatest()` 入口；`pollInFlightAttemptId` 重入锁 `:169-172` | ViewModel 节奏驱动在 `ui/auth/DeviceCodeLoginViewModel.kt:168-211` `schedulePoll`——模型折叠「延迟调度」为「可再次 StartPoll」，时序细节不影响安全不变量。 |
| `ExpireAtPollEntry` | `:173-178` 过期判决——**只在 poll 入口** | W5：轮询在飞期间跨过过期线不会被察觉，过期 device code 仍可能走完 Saved。建模为 Awaiting 态的非确定性动作忠实此点。 |
| `DeliverResponse` | `core/network/ProviderHttpClient.kt:35-50`：OkHttp 同步 `execute()` **不响应协程取消** | W 取消物理窗口：取消只清控制态，在飞请求跑完。模型里 inFlightResp 交付独立于 ctrl 状态忠实此点。 |
| `ProcessResponse` fresh 分流 | `providers/codex/auth/CodexDeviceCodeClient.kt:114-138` | ★ 命门分流逐条对照：`pending/transient → 回 Awaiting`（`:201-206`/`:188-193`）；`cfMarker → Failed`（`cloudflareBlockedError` `:262-266` + `:195-198`）；**`cfMitigated → Result.Pending`**——`:114-121`：403/404 且 body 无 cf 标记时落入 `return Result.Pending`，而 `isCloudflareChallengeBody`（`:257-260`）只检测 body 三标记 `cf-chl/__cf_chl/just a moment`（`:332`）。`docs/plans/2026-10-25-issue5-fixes-plan.md:44` 明确「不做 cf-mitigated header 检测」。→ **不变量①的反例来源，按实现如实建模**。 |
| `ProcessResponse` stale 分支 | 六个 `isLatestLocked` 写点复查：`:188-193, 201-206, 211-213, 266-268, 290-292, 342-345` | W1 防御结构：检查与写分离、每个写点复查。模型折叠为「stale 一字不写」——折叠依据：六个点的语义都是「代次不符则丢弃」，无分支差异。 |
| `CompleteValidation` / `FailValidation` | 成功 `:307-316, 342-347`；失败 `:358-364` | W8：ValidationFailed 不清 activeAttempt（驻留可重试）——模型 ctrl=ValidationFailed 非终态忠实此点。 |
| `CommitWrite` / `CommitFinish` | `commitPreparedImport` `:372-403`；持久化 `data/repository/RoomQuotaStores.kt:106-176`（`save` `:146` envelope 覆盖写、`withTransaction` `:153-157`） | ★ W2：Codex 版整个持久化在 stateMutex 内——cancel 抢锁阻塞，模型用「CommitWrite 不在 CancelLogin 使能集」忠实表达。覆盖语义：`committedAccountFor`（`:178-194`）relogin 复用 localAccountId、envelope 覆盖旧会话——**覆盖不是删除**，不变量③不受影响。**未建模**：envelope 写与 Room 事务不在同一事务的中间态（进程死亡窗口）——那是崩溃恢复语义不是并发交错，见 §四。 |
| `CancelLogin` | `cancelLatest()` `:252-259`；ViewModel `cancelLogin` `ui/auth/DeviceCodeLoginViewModel.kt:154-166`（pollJob cancel + cancelLatest 双管） | 无 activeAttempt 时 no-op（`:253` 注释）——模型只列非终态为使能。 |
| `SubmitCredential` / `VerifySuccess` / `VerifyFailure` | `ui/auth/WebViewAuthScreen.kt:144-168`：检查（`:153`）与设置（`:154`）在 Compose 主线程快照内相邻、无出让点 | ★ 派单②的 isVerifying 实际属 WebView 流（Kimi/Cursor/Claude/Antigravity），**不属于 device-code**——两条流共用持久层但状态机独立。建模为单原子动作忠实主线程快照语义。失败重新武装（`:163` `consumed=false`）是设计语义（允许重试），不是漏洞。第二层 `RedirectInterceptClient.handled`（`:794,804-807,836-840`）折叠——它依赖 Android 主线程单线程性这一隐含前提，见 §四。 |

## 三、变异测试（able-to-fail）对照

| 变异 | 模拟的破坏 | TLC 结果 | 证据 |
|---|---|---|---|
| OAuth 拆 `consumed` 守卫 | `WebViewAuthScreen.kt:153` 检查丢失 | `InvNoDuplicateSubmit` 被抓（37 状态内反例） | `/tmp/cm7-tlc-mut1.log` |
| OAuth `CancelLogin` 加删除会话 | 取消路径误删既有会话 | `InvSessionPreserved` 被抓 | mut3 日志 |
| OAuth `cfMitigated` 修复变体 → Failed | 修复建议形式化验证 | **0 反例**（346 状态全绿）——证明破坏只在 Pending 分流 | `/tmp/cm7-tlc-oauthfixed.log` |
| Refresh 拆账号比对 | `VersionedStateGuard.kt:109` `epoch.accountId == selectedAccountId` 丢失 | `InvNoCrossAccountPollution` 被抓 | `/tmp/cm7-tlc-mut-b.log` |
| Refresh 失败也写快照 | recordFailure 误写快照（擦除 LKG） | `InvSnapshotNeverErased` 被抓 | `/tmp/cm7-tlc-mut-c.log` |
| Refresh 拆 epoch 比对 | `VersionedStateGuard.kt:107` generation 相等检查丢失 | **未被抓**——见下方诚实分析 | mut-a/a2/a3 日志 |

**变异 A（epoch 比对）未被抓的诚实分析**：拆 epoch 代次比对后，`inFlightEdits=0` 与账号比对仍在，呈现帧的 gen 水位不倒退（因 gen 由编辑推进、refresh 帧落地记当前 gen）。语义上的 staleness（编辑前的过期数据写回同账号）落在「编辑方必补发」的活性假设覆盖区——gen 帧级不变量抓不到它。这不是不变量①失效的证据，而是揭示其真实机制边界：**派单①的「过期 SnapshotEpoch 永不覆盖新编辑」在跨账号/编辑窗口场景由 epoch+账号比对联合保证（已证），在同账号无切换场景依赖编辑方补发收敛（活性假设，非安全不变量）**。御史复验时请注意此边界。

## 四、未建模项（如实声明）

1. **W1 耐久层孤儿行**：`existsAndNotDeleted` 检查与写之间的 check-then-act 窗口可产生已删账号的孤儿 snapshot/attempt 行。它不进呈现层（guard 拦住），是数据库卫生问题不是呈现层不变量违例——未建模，建议单独工单。
2. **W4 轮询 finally 清理的取消敏感窗口**（OAuth）：`pollInFlightAttemptId` 残留可致状态机假死。属活性问题非安全不变量，未建模。
3. **envelope 写与 Room 事务的非原子中间态**：进程死亡窗口（非并发交错），未建模。
4. **`selectedAccountReader` 的 pinned/home 两段式解析**（`AppContainer.kt:791-803`）：建模为原子重读当前值，pinned 删除瞬间的回退链未展开——因重读在 commitMutex 内单次调用自洽。
5. **Grok 变体**：状态机同构但 commit 网络校验在锁外（`GrokDeviceCodeLoginController.kt:393-397` 注释），取消可赢但会话可能已落盘（UI Cancelled 与持久态不一致）。与 Codex 版是不同的不一致方向——本模型按 Codex 版建模（派单指定 Codex）。
6. **slow_down 不改轮询间隔**（Codex 端）：节奏细节不进安全不变量，未建模。
7. **retryValidation/confirmMismatch 无重入守卫**（W7）：实际防护靠主线程串行+持久层 reconcile，逻辑幂等物理不幂等（重复快照写入）——耐久层卫生，未建模。
