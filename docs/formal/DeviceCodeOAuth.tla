---- MODULE DeviceCodeOAuth ----
(***************************************************************************)
(* CodexMeter Codex Device-Code OAuth 轮询状态机形式化规约（CM-7 R1）           *)
(*                                                                           *)
(* 对照实现（逐条行号对照在 docs/formal/MAPPING.md）：                         *)
(*   providers/codex/auth/CodexDeviceCodeLoginUseCase.kt —— 状态机核心         *)
(*   providers/codex/auth/CodexDeviceCodeClient.kt        —— CF 检测与 403 分流*)
(*   ui/auth/DeviceCodeLoginViewModel.kt                  —— 轮询调度          *)
(*   ui/auth/WebViewAuthScreen.kt                         —— isVerifying/consumed*)
(*   data/repository/RoomQuotaStores.kt                   —— 会话持久化写点    *)
(*                                                                           *)
(* R1 修正（御史 cm7-review.md 四项之③④）：                                   *)
(*   - startLogin 生产无门控（UseCase.kt:121-134 无条件顶掉 activeAttempt），   *)
(*     初版只在空闲/终态/校验失败可启动——申请或验证过程中启动下一次的路径       *)
(*     不可达是抽象错误。新增独立 stale 在飞响应槽位：顶掉/取消后旧 poll        *)
(*     响应照常交付并按 stale 丢弃（isLatestLocked 复查），分支真实可达。        *)
(*   - 持久化失败回滚补建模：CommitFailRollback 按「提交前有无合法会话」        *)
(*     二选一（RoomQuotaStores.kt:139-172：有旧凭据恢复、无旧凭据删除新写入）。  *)
(*                                                                           *)
(* 抽象边界（如实声明）：                                                      *)
(*   - startLogin 重进顶掉旧 attempt：attemptEpoch 建模代次                    *)
(*   - 过期判决只在 poll 入口（W5）：建模为 Awaiting 状态下的非确定性动作      *)
(*   - token 交换+usage 校验合并为 Validating 一步：其内部网络等待不展开       *)
(*   - OkHttp 在飞不可打断：取消只清控制态，已交付响应照常处理                 *)
(*   - transient/pending 合并为「回 Awaiting 再轮询」                          *)
(*   - Codex 版 commit 全程持锁（W2）：CommitWrite 期间 cancel 不使能           *)
(*   - stale 响应单槽位：第二个孤儿 poll 覆盖第一个（对安全不变量观察等价：      *)
(*     两者都只会被丢弃；槽位占用不阻塞新登录）                                 *)
(***************************************************************************)
EXTENDS Naturals, TLC

CONSTANTS MaxEpoch

CtrlStates == {"Idle", "Requesting", "Awaiting", "PollFlight",
               "Validating", "ReadyToValidate", "CommitWrite",
               "ValidationFailed",
               "Failed", "Expired", "Cancelled", "Saved"}
TERMINAL == {"Failed", "Expired", "Cancelled", "Saved"}

\* 网络响应六分（CodexDeviceCodeClient.pollAuthorization 的真实分流）：
\*   pending      —— authorization_pending / 403·404 但 body 无 cf 标记
\*   transient    —— IOException 类网络抖动（回 Awaiting）
\*   cfMarker     —— body 命中 cf-chl/__cf_chl/just a moment（任何状态码）→ Failed
\*   cfMitigated  —— Cloudflare cf-mitigated 质询但 body 不含三标记——
\*                  ★ 命门：实现里它走 403 分流的 Result.Pending 继续轮询
\*                  （CodexDeviceCodeClient.kt:114-121），docs/plans 明确不做
\*                  header 检测。模型按实现如实建模，不变量①给出反例。
\*   fatal        —— 其他非 2xx 终结
\*   success      —— 拿到 authorization code
Responses == {"pending", "transient", "cfMarker", "cfMitigated", "fatal", "success"}
EPOCHNIL == 0  \* 代次槽哨兵：attemptEpoch 从 0 起且只增，0 永不指代真实 attempt
RESPNIL == "nil"  \* 响应槽哨兵（响应全是字符串，无类型冲突）

VARIABLES
  ctrl,            \* 控制态
  ui,              \* 对外展示态
  attemptEpoch,    \* attempt 代次（startLogin 重进 +1）
  pollEpoch,       \* 在飞 poll 携带的代次（EPOCHNIL=无在飞）
  inFlightResp,    \* 已交付未处理的响应 ∈ Responses ∪ {RESPNIL}
  staleEpoch,      \* R1③：被顶掉/取消的旧 poll 代次（EPOCHNIL=无孤儿在飞）
  staleResp,       \* R1③：旧 poll 已交付未处理的响应 ∈ Responses ∪ {RESPNIL}
  staleDiscarded,  \* 影子计数：stale 响应被丢弃次数（佐证丢弃路径真实可达）
  cfMitigatedLoop, \* 影子：处理过 cfMitigated 但未终结（不变量①判定）
  storedSession,   \* 持久层会话：初值非确定性 ∈ {"Valid","None"}（R1④）
  startedValid,    \* 影子：初始是否有合法会话
  everValid,       \* 影子：是否曾存在过合法会话（不变量③的判定基准——
                   \* 中途经 CommitFinish 建立的合法会话同样受保护）
  hadValidOnCommit,\* R1④：CommitWrite 开始时快照的 storedSession（回滚依据）
  consumed,        \* WebView 单次触发守卫（WebViewAuthScreen.kt:149-154）
  verifyActive,    \* isVerifying（WebViewAuthScreen.kt:144-168）
  verifyCount      \* 影子：当前在飞的校验调用数（重入判定实体——
                   \* consumed 是机制，verifyCount≤1 才是要证的性质）

vars == <<ctrl, ui, attemptEpoch, pollEpoch, inFlightResp, staleEpoch, staleResp,
          staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid,
          hadValidOnCommit, consumed, verifyActive, verifyCount>>

Init ==
  /\ ctrl = "Idle"
  /\ ui = "Idle"
  /\ attemptEpoch = 0
  /\ pollEpoch = EPOCHNIL
  /\ inFlightResp = RESPNIL
  /\ staleEpoch = EPOCHNIL
  /\ staleResp = RESPNIL
  /\ staleDiscarded = 0
  /\ cfMitigatedLoop = FALSE
  \* R1④：持久层初态非确定——两条回滚分支（恢复旧凭据/删除新写入）都要覆盖
  /\ \E s0 \in {"Valid", "None"} :
       /\ storedSession = s0
       /\ startedValid = (s0 = "Valid")
       /\ everValid = (s0 = "Valid")
  /\ hadValidOnCommit = FALSE
  /\ consumed = FALSE
  /\ verifyActive = FALSE
  /\ verifyCount = 0


\* ---- startLogin（UseCase.kt:121-134）：无条件顶掉旧 attempt，epoch+1 ----
\* ★ R1③修正：生产没有任何 ctrl 门控——申请中/轮询中/验证中都可重进。
\* 唯一上界是 MaxEpoch（状态数控制）。
StartLogin ==
  /\ ctrl # "CommitWrite"   \* commit 持锁期间 startLogin 抢不到 stateMutex（W2 同构）
  /\ attemptEpoch < MaxEpoch
  /\ ctrl' = "Requesting"
  /\ ui' = "RequestingDeviceCode"
  /\ attemptEpoch' = attemptEpoch + 1
  /\ pollEpoch' = EPOCHNIL
  /\ staleEpoch' = IF pollEpoch = EPOCHNIL THEN staleEpoch ELSE pollEpoch
  /\ staleResp' = IF pollEpoch = EPOCHNIL THEN staleResp ELSE inFlightResp
  /\ inFlightResp' = RESPNIL
  /\ UNCHANGED <<cfMitigatedLoop, storedSession, startedValid, everValid,
                 hadValidOnCommit, consumed, verifyActive, verifyCount, staleDiscarded>>

\* device code 请求成功（156-164）
CompleteRequestDeviceCode ==
  /\ ctrl = "Requesting"
  /\ ctrl' = "Awaiting"
  /\ ui' = "AwaitingUserAuthorization"
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid,
                 hadValidOnCommit, consumed, verifyActive, verifyCount>>

\* device code 请求失败（136-146，含 cf blocked 与端点 invalid）
FailRequestDeviceCode ==
  /\ ctrl = "Requesting"
  /\ ctrl' = "Failed"
  /\ ui' = "Failed"
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid,
                 hadValidOnCommit, consumed, verifyActive, verifyCount>>

\* poll 入口（168-181）：pollInFlight 重入锁——同 attempt 同时最多一个在飞。
StartPoll ==
  /\ ctrl = "Awaiting"
  /\ pollEpoch = EPOCHNIL
  /\ ctrl' = "PollFlight"
  /\ pollEpoch' = attemptEpoch
  /\ UNCHANGED <<ui, attemptEpoch, inFlightResp, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid,
                 hadValidOnCommit, consumed, verifyActive, verifyCount>>

\* poll 入口过期判决（173-178）：expiresAt 只在入口检查（W5）
ExpireAtPollEntry ==
  /\ ctrl = "Awaiting"
  /\ pollEpoch = EPOCHNIL
  /\ ctrl' = "Expired"
  /\ ui' = "Expired"
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid,
                 hadValidOnCommit, consumed, verifyActive, verifyCount>>

\* 网络层交付响应（OkHttp 在飞不可打断）
DeliverResponse(r) ==
  /\ ctrl = "PollFlight"
  /\ inFlightResp = RESPNIL
  /\ inFlightResp' = r
  /\ UNCHANGED <<ctrl, ui, attemptEpoch, pollEpoch, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid,
                 hadValidOnCommit, consumed, verifyActive, verifyCount>>

\* R1③：被顶掉/取消的旧 poll 响应照常交付（ProviderHttpClient.kt:79-99
\* postJson() 中 OkHttp execute() 不响应协程取消）。交付独立于任何控制态。
DeliverStaleResponse(r) ==
  /\ staleEpoch # EPOCHNIL
  /\ staleResp = RESPNIL
  /\ staleResp' = r
  /\ UNCHANGED <<ctrl, ui, attemptEpoch, pollEpoch, inFlightResp, staleEpoch,
                 staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid,
                 hadValidOnCommit, consumed, verifyActive, verifyCount>>

\* R1③：stale 响应处理——六个 isLatestLocked 写点复查全部失败，
\* 一字不写即丢弃。ctrl/ui/会话任何字段都不动；计数证明路径真实可达。
ProcessStaleResponse ==
  /\ staleResp # RESPNIL
  /\ staleEpoch' = EPOCHNIL
  /\ staleResp' = RESPNIL
  /\ staleDiscarded' = staleDiscarded + 1
  /\ UNCHANGED <<ctrl, ui, attemptEpoch, pollEpoch, inFlightResp,
                 cfMitigatedLoop, storedSession, startedValid, everValid,
                 hadValidOnCommit, consumed, verifyActive, verifyCount>>

\* 处理响应（UseCase.kt:188-213 写点 stale 复查）：
\* fresh 按 CodexDeviceCodeClient.kt:114-138 真实分流；stale 一字不写。
\* 注：本槽位的 stale 情形（pollEpoch # attemptEpoch 但 ctrl 仍是 PollFlight）
\* 在生产中被 StartLogin 移交语义覆盖——移交后进 stale 槽位由
\* ProcessStaleResponse 处理；此处保留 fresh 判定作防御冗余。
ProcessResponse ==
  /\ ctrl = "PollFlight"
  /\ inFlightResp # RESPNIL
  /\ LET r == inFlightResp
         fresh == (pollEpoch = attemptEpoch)
     IN
       /\ pollEpoch' = EPOCHNIL
       /\ inFlightResp' = RESPNIL
       /\ IF ~fresh
          THEN /\ ctrl' = ctrl
               /\ ui' = ui
               /\ cfMitigatedLoop' = cfMitigatedLoop
          ELSE IF r \in {"pending", "transient"}
          THEN /\ ctrl' = "Awaiting"
               /\ ui' = "AwaitingUserAuthorization"
               /\ cfMitigatedLoop' = cfMitigatedLoop
          ELSE IF r = "cfMitigated"
          THEN \* ★ 按实现如实建模：body 无标记的 cf 质询走 403 分流的
               \* Result.Pending（CodexDeviceCodeClient.kt:114-121）——回 Awaiting
               \* 继续轮询，而非终结。影子标记：cf 类封锁响应未终结。
               /\ ctrl' = "Awaiting"
               /\ ui' = "AwaitingUserAuthorization"
               /\ cfMitigatedLoop' = TRUE
          ELSE IF r = "cfMarker"
          THEN \* body 命中 cf 标记：立即终结（cloudflareBlockedError → 195-198）
               /\ ctrl' = "Failed"
               /\ ui' = "Failed"
               /\ cfMitigatedLoop' = cfMitigatedLoop
          ELSE IF r = "fatal"
          THEN /\ ctrl' = "Failed"
               /\ ui' = "Failed"
               /\ cfMitigatedLoop' = cfMitigatedLoop
          ELSE \* success
               /\ ctrl' = "Validating"
               /\ ui' = "ExchangingToken"
               /\ cfMitigatedLoop' = cfMitigatedLoop
       /\ UNCHANGED <<attemptEpoch, staleEpoch, staleResp, staleDiscarded,
                      storedSession, startedValid, everValid, hadValidOnCommit,
                      consumed, verifyActive, verifyCount>>

\* 校验完成（307-316, 342-347）
CompleteValidation ==
  /\ ctrl = "Validating"
  /\ ctrl' = "ReadyToValidate"
  /\ ui' = "ValidatingUsage"
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid,
                 hadValidOnCommit, consumed, verifyActive, verifyCount>>

\* 校验失败——ValidationFailed 驻留可重试，attempt 不清（358-364, W8）
FailValidation ==
  /\ ctrl = "Validating"
  /\ ctrl' = "ValidationFailed"
  /\ ui' = "ValidationFailed"
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid,
                 hadValidOnCommit, consumed, verifyActive, verifyCount>>

\* commit 持锁写入（372-403 + RoomQuotaStores.kt:106-176）：
\* Codex 版整个持久化在 stateMutex 内——写点不可与 cancel 交错（W2）。
\* R1④：进入时快照「提交前有无合法会话」——回滚分支的选择依据
\* （RoomQuotaStores.kt:139-143 previousEnvelope 读取）。
CommitWrite ==
  /\ ctrl = "ReadyToValidate"
  /\ ctrl' = "CommitWrite"
  /\ hadValidOnCommit' = (storedSession = "Valid")
  /\ UNCHANGED <<ui, attemptEpoch, pollEpoch, inFlightResp, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid,
                 consumed, verifyActive, verifyCount>>

CommitFinish ==
  /\ ctrl = "CommitWrite"
  /\ ctrl' = "Saved"
  /\ ui' = "Saved"
  /\ storedSession' = "Valid"   \* 覆盖式写入完成
  /\ everValid' = TRUE
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, startedValid, hadValidOnCommit,
                 consumed, verifyActive, verifyCount>>

\* R1④：持久化失败回滚（RoomQuotaStores.kt:158-173 catch 分支）：
\* 有旧凭据 → 恢复旧凭据（rollback 后仍是 Valid）；
\* 无旧凭据 → 删除本次新写入（rollback 后是 None）。
\* ctrl 落 ValidationFailed——忠实 commitPreparedImport 失败落点
\* （UseCase.kt:382-385 Result.Failure / :399-402 catch 均写 ValidationFailed）。
CommitFailRollback ==
  /\ ctrl = "CommitWrite"
  /\ ctrl' = "ValidationFailed"
  /\ ui' = "ValidationFailed"
  /\ storedSession' = IF hadValidOnCommit THEN "Valid" ELSE "None"
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, startedValid, everValid, hadValidOnCommit,
                 consumed, verifyActive, verifyCount>>

\* retryValidation（227-235）
RetryValidation ==
  /\ ctrl = "ValidationFailed"
  /\ ctrl' = "Validating"
  /\ ui' = "ValidatingUsage"
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid,
                 hadValidOnCommit, consumed, verifyActive, verifyCount>>

\* cancelLatest（252-259 + ViewModel cancelLogin 154-166）：
\* ★ 持锁排斥——CommitWrite 期间 cancel 抢不到锁（W2）。
\* R1③：取消不再清响应——在飞 poll 移交 stale 槽位，迟到响应照常交付后
\* 按 isLatestLocked 丢弃（生产真实路径）。
CancelLogin ==
  /\ ctrl \in {"Requesting", "Awaiting", "PollFlight", "Validating",
               "ReadyToValidate", "ValidationFailed"}
  /\ ctrl' = "Cancelled"
  /\ ui' = "Cancelled"
  /\ pollEpoch' = EPOCHNIL
  /\ staleEpoch' = IF pollEpoch = EPOCHNIL THEN staleEpoch ELSE pollEpoch
  /\ staleResp' = IF pollEpoch = EPOCHNIL THEN staleResp ELSE inFlightResp
  /\ inFlightResp' = RESPNIL
  /\ UNCHANGED <<attemptEpoch, cfMitigatedLoop, storedSession, startedValid, everValid,
                 hadValidOnCommit, consumed, verifyActive, verifyCount, staleDiscarded>>

\* ---- WebView 流 isVerifying/consumed（WebViewAuthScreen.kt:144-168）----
\* 检查（153）与设置（154）在 Compose 主线程快照内相邻无出让点——单原子动作。
SubmitCredential ==
  /\ ~consumed
  /\ consumed' = TRUE
  /\ verifyActive' = TRUE
  /\ verifyCount' = verifyCount + 1
  /\ UNCHANGED <<ctrl, ui, attemptEpoch, pollEpoch, inFlightResp, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid, hadValidOnCommit>>

VerifySuccess ==
  /\ verifyActive
  /\ verifyActive' = FALSE
  /\ verifyCount' = verifyCount - 1
  /\ UNCHANGED <<ctrl, ui, attemptEpoch, pollEpoch, inFlightResp, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid, hadValidOnCommit, consumed>>

\* 校验失败重新武装（163-167：consumed=false 允许重试——设计语义）
VerifyFailure ==
  /\ verifyActive
  /\ verifyActive' = FALSE
  /\ verifyCount' = verifyCount - 1
  /\ consumed' = FALSE
  /\ UNCHANGED <<ctrl, ui, attemptEpoch, pollEpoch, inFlightResp, staleEpoch, staleResp,
                 staleDiscarded, cfMitigatedLoop, storedSession, startedValid, everValid, hadValidOnCommit>>

Next ==
  \/ StartLogin
  \/ CompleteRequestDeviceCode
  \/ FailRequestDeviceCode
  \/ StartPoll
  \/ ExpireAtPollEntry
  \/ \E r \in Responses : DeliverResponse(r)
  \/ \E r \in Responses : DeliverStaleResponse(r)
  \/ ProcessResponse
  \/ ProcessStaleResponse
  \/ CompleteValidation
  \/ FailValidation
  \/ CommitWrite
  \/ CommitFinish
  \/ CommitFailRollback
  \/ RetryValidation
  \/ CancelLogin
  \/ SubmitCredential
  \/ VerifySuccess
  \/ VerifyFailure

Spec == Init /\ [][Next]_vars

\* ================= 派单不变量 =================

\* ① Cloudflare 封锁类 403（含 cf-mitigated 质询）必立即终结进 CfBlocked/Failed——
\*    ★ 预期被抓：现实现把 cfMitigated 当 Pending 回 Awaiting（继续轮询到 expiry）。
InvCfBlockedTerminates ==
  cfMitigatedLoop = FALSE

\* ② isVerifying 期间禁重复提交：
\*    同时在飞的校验调用 never 超过 1——consumed 是机制，本计数是性质本身。
InvNoDuplicateSubmit ==
  verifyCount <= 1

\* ③ 取消/过期/失败绝不删除既有合法 OAuth 会话（R1④强化）：
\*    初始有合法会话的轨迹里，会话永远 Valid——取消/过期/失败不写持久层，
\*    唯一写点是 CommitFinish 覆盖写 Valid 与 CommitFailRollback 按提交前事实回滚。
\*    变异测试（回滚路径变异为「有旧会话仍删除」）必须让它被抓。
InvSessionPreserved ==
  everValid => storedSession = "Valid"

TypeOK ==
  /\ ctrl \in CtrlStates
  /\ storedSession \in {"None", "Valid"}
  /\ consumed \in BOOLEAN
  /\ verifyActive \in BOOLEAN

====
