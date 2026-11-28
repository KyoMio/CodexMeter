# CM-7 状态转换 ↔ 代码实现逐条对照（R1 修订版）

本文是审计命门：每条 TLA+ 动作指回实现的具体位置（文件:行号/函数），并说明抽象时丢掉的细节是「真实竞态被漏抽象」还是「实现细节可忽略」及依据。

行号以提交时工作区为准（`feat/cm7-formal-models` 分支基线 `7e9a4ce`）。

**R1 修订说明**（对应御史 `Shared/Transfers/cm7-review.md` 四项及复审修补）：初版两处抽象错误已修正——刷新侧 `ValidateCommit` 拆为 `ValidateEpoch` / `WritePublish` 双动作（生产 `commitMutex` 只串行化发布者，编辑可插入），且 `FinishIO` 失败分支回退 `s.ver` 为 `SnapOf(s.account)`；OAuth 侧补 `stale` 在飞响应槽位（`startLogin` 生产无门控）与落库失败回滚路径。

## 一、RefreshCoordinator.tla ↔ 实现

| TLA+ 动作 | 实现位置 | 语义对照与抽象说明 |
|---|---|---|
| `StartRefresh` | `refresh/RefreshCoordinator.kt:54-86` `refresh()` 主流程；single-flight 查重 `inFlight` map 在 `:58-68`；`captureReadToken` 在 `:77` | epoch 在 provider I/O 前拍（`core/concurrency/VersionedStateGuard.kt:62-63` 原子读 generation）。模型里会话捕获 `epochGen=gen` 忠实此点。`MaxSessions`/`nextVer` 上界仅为状态数控制，与 epoch 语义正交。 |
| `FinishIO`（成功分支写快照） | `refresh/RefreshCoordinator.kt:147-190` `recordSuccess`；快照写 `:158`，attempt 写 `:174` | append-only：`data/repository/RoomQuotaStores.kt:46-48` 只 insert 不 update。**简化声明**：`existsAndNotDeleted` 检查（`:155`）与写之间的 check-then-act 窗口（W1 孤儿行风险）未建模——影响耐久层垃圾行而非呈现层不变量，见 §四。 |
| `FinishIO`（失败分支不写快照） | `refresh/RefreshCoordinator.kt:192-232` `recordFailure`；`lastKnownGood` 读 `:199`，失败 attempt 写 `:221`，传入 `publishCurrentState`（`:225-230`）；**快照在此函数中无任何写路径** | 不变量③的机制基础：失败路径在机制上不存在快照写入，且模型在失败分支将 `s.ver` 置为 `SnapOf(s.account)` 以忠实 `latestSnapshot = lastKnownGood`。`markNeedsReauth`（`:222-224`）折叠未建模。 |
| `EnterValidate` | `refresh/RefreshCoordinator.kt:234-274` `publishCurrentState`；`commitIfFresh` 调用点 `:262` | 抢锁步建模 `commitMutex` 竞争（`VersionedStateGuard.kt:43`）——`commitSerialized` 重发共用同一锁（`:147-152`）。 |
| `ValidateEpoch`（R1 新增·授权时刻） | `core/concurrency/VersionedStateGuard.kt:116-130` `commitIfFresh` 临界区**前半**：`selectedAccountIdProvider()` 重读 + `isFresh` 校验（`:105-110` gen 相等 ∧ inFlightEdits=0 ∧ epoch.account=selected） | ★ R1 ②修正：初版把校验与写合并为不可分割动作，但生产的锁只串行化**发布者之间**——编辑（begin/endEdit 的 CAS 与 DataStore 写）不抢锁。御史 `guard.log` 实证「已通过校验的写回挂起，切换 A→B 并结束编辑后恢复」真实发生。拆步后编辑可插入 ValidateEpoch 与 WritePublish 之间，忠实实现交错空间。 |
| `WritePublish`（R1 新增·生效时刻） | 同上书临界区**后半**：`write()` 挂起 lambda 执行 + `committedWriteCounter` | ★ 校验与落地之间**不重查**——忠实 commitIfFresh 只在授权时刻校验一次的实现。帧 `capGen` 记会话捕获 epoch（御史判决①：帧必须保留来源代次）；`gen` 记落地瞬间世界 gen。`WritePublish` 内不分叉验证——`s.validated` 是 ValidateEpoch 时冻结的判定。 |
| （stale 丢弃路径） | `isFresh` 失败时 write 完全不执行，`discardedWriteCounter` 计数（`:127`） | 模型 `pubViolations` 影子计数同语义。 |
| `BeginEdit` | `core/concurrency/VersionedStateGuard.kt:71-80` `beginEdit()` CAS | ★ 不抢 commitMutex（guardState 是独立 AtomicReference）——这是 R1 ②拆步后编辑能插入发布临界区的机制事实。调用点：`data/repository/AccountMutationRepository.kt:45` 等。模型折叠为单一串行编辑窗口（见 §四）。 |
| `SwitchSelection` | `data/repository/AccountMutationRepository.kt:52` | 编辑窗口内 DataStore 写——同样不抢锁。 |
| `EndEdit` | `core/concurrency/VersionedStateGuard.kt:87-99`：gen 再 +1 | 编辑窗口内捕获的 epoch 在编辑结束后**永久** stale（+2 效果）。 |
| `EditRepublish` | `app/RepublishingAccountDeleteUseCase.kt:26-28`、`app/RepublishingNotificationPreferenceStore.kt:30-32`、`app/RepublishingQuotaHistoryClearUseCase.kt:27-29`（`loadCurrentState()` 在 `commitSerialized` **锁内**读取）；`app/ImmediateAccountQuotaAlertEvaluationRequester.kt:32-36`（`loadAccountState()` 在锁外读，随后 `commitSerialized` 发布） | W4 活性假设：部分编辑后的状态补偿依赖 `commitSerialized` 无条件重发（锁内且不做 epoch 校验）。edit 帧 `capGen` 记落地 gen——它就是当前代的表达。**实现差异澄清**：① `app/CurrentAccountQuotaStateRepublisher.kt:33,56-71`（由 `AccountMutationRepository.kt:121` `renameAccount` 调用）走的是 epoch 校验的 `commitIfFresh`，**不是** `commitSerialized`；② 账号切换（`AccountMutationRepository.kt:37-62` 经 `ui/home/HomeViewModel.kt:151-178`）通过 `loadCurrentState()` 刷新 Home UI 并触发 `RefreshCoordinator.refresh`，同样不调 `commitSerialized`；③ 仅 `ImmediateAccountQuotaAlertEvaluationRequester` 在锁外读取状态（W3 读旧风险折叠进 `SnapOf(selected)`）。 |
| perAccountEffects（折叠不占状态位） | `refresh/RefreshCoordinator.kt:253` guard 之外无条件执行 | 告警/widget 属被刷账号本身，不写呈现层当前账号状态，不进 `published`。 |

## 二、DeviceCodeOAuth.tla ↔ 实现

| TLA+ 动作 | 实现位置 | 语义对照与抽象说明 |
|---|---|---|
| `StartLogin`（R1 ③扩门控） | `providers/codex/auth/CodexDeviceCodeLoginUseCase.kt:121-134` | ★ R1 修正：**生产没有任何状态门控**——无条件覆盖 activeAttempt（`:126-129`）、`pollInFlightAttemptId=null`（`:130`），申请中/轮询中/验证中重进全部合法。初版只允许空闲/终态/校验失败启动是抽象错误，导致旧轮询晚到分支不可达（御史 `oauth-stale-reachable/run.log` 216 状态无一携带旧代次）。修正后唯一约束是 CommitWrite 持锁期间不可进（stateMutex 同构）。 |
| `DeliverStaleResponse` / `ProcessStaleResponse`（R1 新增） | 交付机制：`core/network/ProviderHttpClient.kt:79-99` `postJson()` 中 OkHttp `execute()`（`:93-98`）不响应协程取消；丢弃机制：六个 `isLatestLocked` 写点复查 `:188-193, 201-206, 211-213, 266-268, 290-292, 342-345` | ★ 生产真实路径：被顶掉/取消的旧 poll 协程继续跑完，响应回来后重新抢 stateMutex，所有写点因代次不符跳过，**一字不写**。模型用独立 stale 槽位建模，`staleDiscarded` 计数佐证可达（见证轨迹：StartLogin→…→StartPoll→StartLogin(顶掉)→DeliverStaleResponse→ProcessStaleResponse，7 步）。单槽位折叠声明：多孤儿观察等价（都只会被丢弃），见文件头。 |
| `StartPoll` / `ExpireAtPollEntry` | `:167-182` 入口；`pollInFlightAttemptId` 重入锁 `:170-172`；过期判 `:173-178` | W5：过期只在入口判。折叠调度时序不影响安全不变量。 |
| `ProcessResponse` fresh 分流 | `providers/codex/auth/CodexDeviceCodeClient.kt:114-138` | ★ 命门不变：`cfMitigated → Result.Pending`（`:114-121`，`isCloudflareChallengeBody` 只查 body 三标记 `:332`，`docs/plans/2026-10-25-issue5-fixes-plan.md:44` 明确不做 header 检测）。不变量①反例保留，修复归 CM-8。 |
| `CompleteValidation` / `FailValidation` | 成功 `:307-316, 342-347`；失败 `:358-364` | W8：ValidationFailed 驻留可重试。 |
| `CommitWrite` | `commitPreparedImport` `:372-403`（stateMutex 内）；持久化 `data/repository/RoomQuotaStores.kt:127-176` | ★ R1 ④：进入时快照 `hadValidOnCommit`——对照生产 `:139-143` 写入前读取 `previousSelection` / `previousEnvelope`。 |
| `CommitFinish` | 成功路径：`RoomQuotaStores.kt:146-157` 三段写入完成 | 覆盖式写 Valid——覆盖不是删除。 |
| `CommitFailRollback`（R1 新增） | **失败回滚**：`RoomQuotaStores.kt:158-173` catch 分支——恢复 previousSelection，随后按 previousEnvelope 有无二选一：有旧凭据 `sessionStore.save(previousEnvelope)`（`:169`）、无则 `sessionStore.delete(...)`（`:164-167`）；ctrl 落点：`UseCase.kt:382-385` Result.Failure / `:399-402` catch 均写 ValidationFailed | ★ 初版 CommitWrite 只能成功，即使覆盖取消路径也检不出回滚分支写反（御史判决④原文）。模型 `storedSession' = IF hadValidOnCommit THEN "Valid" ELSE "None"` 忠实二选一。 |
| `CancelLogin` | `cancelLatest()` `:252-259`；ViewModel `cancelLogin` `:154-166` | ★ R1 修正：取消不再清响应——在飞 poll 移交 stale 槽位，迟到响应照常交付后按 stale 丢弃。无 activeAttempt 时 no-op（`:254`）。CommitWrite 期间不在使能集（W2 持锁排斥）。 |
| `SubmitCredential` / `VerifySuccess` / `VerifyFailure` | `ui/auth/WebViewAuthScreen.kt:144-168` | 单原子动作忠实主线程快照语义。失败重新武装（`:163`）是设计语义。 |

## 三、变异测试（able-to-fail）对照——R1 全量（6/6 闭环）

所有变异均以临时副本在 `/tmp` 运行 `tlc -deadlock -workers auto` 复现（原始规约保持不变）。

| 变异 | 模拟的破坏 | TLC 结果 | 复现方法（`tlc -deadlock`） |
|---|---|---|---|
| **MutA**：Refresh 拆 epoch 比对（`ValidateEpoch` 去掉 `gen = s.epochGen`） | `VersionedStateGuard.kt:107` generation 相等检查丢失 | **`InvRefreshNeverRegressesEdit` 被抓，exit 12**（9 状态反例，depth 10；5,399 distinct states） | 将 `ValidateEpoch` 的 `fresh` 改为 `(inFlightEdits = 0) /\ (s.account = selected)`，以 `RefreshCoordinator-pass.cfg` 运行 |
| **MutB**：Refresh 拆账号比对（`ValidateEpoch` 去掉 `s.account = selected`） | `VersionedStateGuard.kt:109` `epoch.accountId == selectedAccountId` 丢失 | **`InvNoCrossAccountPollution` 被抓，exit 12**（7 状态反例，depth 7；585 distinct states；对照组保留 `s.account = selected` 时全图 3,632,528 distinct states、depth 31、exit 0） | 在 `BeginEdit` 增加 `~/\E s \in sessions : s.phase = "writing"` 隔离 CM-10 交错后，将 `fresh` 改为 `(gen = s.epochGen) /\ (inFlightEdits = 0)`，以 `RefreshCoordinator-finding.cfg` 运行 |
| **MutC**：Refresh 失败也清除/覆写快照（`FinishIO` 失败分支将对应账号 `snap` 清为 `0`） | `RefreshCoordinator.kt` `recordFailure` 误擦除快照 | **`InvSnapshotNeverErasedA` 被抓，exit 12**（10 状态反例，depth 10；4,162 distinct states） | 将 `FinishIO` 失败分支改为 `snapA' = IF s.account = "A" THEN 0 ELSE snapA` 等，以 `RefreshCoordinator-pass.cfg` 运行 |
| **MutD**：OAuth 回滚写反（`CommitFailRollback` 忽略 `hadValidOnCommit` 恒置 `"None"`） | `RoomQuotaStores.kt:169` save 误写成 delete 分支 | **`InvSessionPreserved` 被抓，exit 12**（10 状态反例，depth 12；1,167 distinct states） | 将 `CommitFailRollback` 的 `storedSession'` 改为 `"None"`，以 `DeviceCodeOAuth-pass.cfg` 运行 |
| **MutE**：OAuth 取消删除会话（`CancelLogin` 置 `storedSession' = "None"`） | 取消路径误删既有会话 | **`InvSessionPreserved` 被抓，exit 12**（5 状态反例，depth 7；118 distinct states） | 在 `CancelLogin` 增加 `storedSession' = "None"`，以 `DeviceCodeOAuth-pass.cfg` 运行 |
| **MutF**：OAuth 拆 `consumed` 守卫（`SubmitCredential` 去掉 `/\ ~consumed`） | `WebViewAuthScreen.kt:153` 检查丢失 | **`InvNoDuplicateSubmit` 被抓，exit 12**（3 状态反例，depth 6；96 distinct states） | 从 `SubmitCredential` 删除 `/\ ~consumed`，以 `DeviceCodeOAuth-pass.cfg` 运行 |

**R1 关键结论更新**：

1. **不变量②（落地瞬间账号相符）出反例如实交付**：ValidateEpoch/WritePublish 拆步后，「校验通过 → BeginEdit+SwitchSelection → WritePublish」交错使 refresh 帧落地瞬间 selected 已是新账号（8 状态 / 7 步反例，`src=A` 帧落在 `selected=B` 的世界，`ver=0` 对应失败分支回退 `lastKnownGood`）。这与生产 `guard.log` 观测一致——生产发布回调本身也有挂起读取，属**真实竞态窗口的忠实刻画**（归口 `CM-10`）。安全不变量 `TypeOK`、`InvRefreshNeverRegressesEdit`、`InvSnapshotNeverErasedA`、`InvSnapshotNeverErasedB` 在 `RefreshCoordinator-pass.cfg` 下 `7,501,510` distinct states（`22,605,047` states generated）、深度 `32` 全图 0 反例（exit 0）。
2. **stale 晚到分支可达性已证实**：探针不变量 `staleDiscarded = 0` 给出 7 步见证轨迹（验证/轮询中重进 StartLogin → 旧 poll 响应交付 → 一字不写丢弃）。
3. **不变量③强化为 everValid 基准**：合法会话一旦存在（初始或中途 CommitFinish 建立）永不消失——比初版 startedValid 更强，覆盖 relogin 后的第二次提交失败场景。

## 四、未建模项（如实声明）

1. **W1 耐久层孤儿行**：`existsAndNotDeleted` 检查与写之间的 check-then-act 窗口（包括 `recordSuccess` `:155`、`recordFailure` `:201`、`publishCurrentState` `:240`）——耐久层卫生问题，未建模。
2. **单编辑串行化折叠**：模型用 `~editing` 将 `inFlightEdits` 限制在 `{0, 1}`，而生产 `VersionedStateGuard` 允许并发重入编辑（`inFlightEdits >= 2`）；同时 `isFresh` 中的 `inFlightEdits = 0` 守卫未被当前不变量独立压测（`BeginEdit` 推进 `gen` 已使已有会话失效，唯独「edit 窗口内新启动的 `StartRefresh` 捕捉了 Window 内 `gen`，若无 `inFlightEdits = 0` 且在 `EndEdit` 前落地」这一子情景依赖 `inFlightEdits = 0`）。
3. **异常与协程取消清理**：`RefreshCoordinator.completeFlightExceptionally`（`RefreshCoordinator.kt:82-85,99-108`）的 `inFlight` 清理路径未建模。
4. **OAuth 账号错配决定与 token 交换直败**：`AccountMismatchDecision` / `confirmAddAccountFromMismatch`（`CodexDeviceCodeLoginUseCase.kt:242-250,294-306`）以及 `tokenExchanger.exchange` 失败直接进入终态 `Failed`（`:281-286`）未建模（模型将 token 交换与 usage 校验合并为 `Validating`，失败一律落 `ValidationFailed`）。
5. **W4 轮询 finally 清理的取消敏感窗口**（OAuth）：活性问题，未建模。
6. **envelope 写与 Room 事务的非原子中间态**：进程死亡窗口（非并发交错），未建模。
7. **`selectedAccountReader` 的 pinned/home 两段式解析**：建模为原子重读。
8. **Grok 变体**：commit 网络校验在锁外，与 Codex 版不一致方向不同——本模型按 Codex 版。
9. **slow_down 不改轮询间隔**：节奏细节不进安全不变量。
10. **retryValidation/confirmMismatch 无重入守卫**（W7）：耐久层卫生，未建模。
11. **stale 响应单槽位**：第二个孤儿覆盖第一个（观察等价：都只会被丢弃）；多孤儿独立代次未展开。
12. **write 挂起 lambda 内部的更细粒度交错**：模型粒度为「校验/生效」两段式；生效段内部编辑再插入不新增可观测维度（帧内容在授权时刻已冻结）。
13. **`InvEpochNeverOverwritesEdit` 代数恒等式**：因 `gen` 单调且 `maxPubGen` 记历史最大值，`published.gen >= maxPubGen` 恒为真（tautology），仅保留在 `RefreshCoordinator.tla` 作观测注解，不列入 `.cfg`。


**CM-10 R1 同步（2026-11-28）**：NotificationTargetBoundary 落地——target-recheck 与系统 post 拆分为 `CheckTarget`/`PostPublish` 两步（判决强制要求，忠实「重核与落地非原子」的实现形态），两步共享 `targetHeld` 互斥；`SwitchSelection` 写侧同样在互斥内（`~targetHeld` 前置）。拆步全不变量翻绿：7,236,915 distinct / 深度 35 / exit 0。反向变异（写侧摘掉 `~targetHeld`）第 9 状态 `InvNoCrossAccountPollution` 违规 exit 12——证明绿是互斥语义的绿而非模型形状。锁序约束：commitMutex → boundary（生产实现）；写侧持 boundary 期间不嵌套 commitMutex。
