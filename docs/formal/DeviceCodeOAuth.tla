---- MODULE DeviceCodeOAuth ----
(***************************************************************************)
(* CodexMeter Codex Device-Code OAuth 轮询状态机形式化规约（CM-7）             *)
(*                                                                           *)
(* 对照实现（逐条行号对照在 docs/formal/MAPPING.md）：                         *)
(*   providers/codex/auth/CodexDeviceCodeLoginUseCase.kt —— 状态机核心         *)
(*   providers/codex/auth/CodexDeviceCodeClient.kt        —— CF 检测与 403 分流*)
(*   ui/auth/DeviceCodeLoginViewModel.kt                  —— 轮询调度          *)
(*   ui/auth/WebViewAuthScreen.kt                         —— isVerifying/consumed*)
(*   data/repository/RoomQuotaStores.kt                   —— 会话持久化写点    *)
(*                                                                           *)
(* 抽象边界（如实声明）：                                                     *)
(*   - startLogin 重进顶掉旧 attempt：attemptEpoch 建模代次，在飞响应对不上     *)
(*     代次即 stale（isLatestLocked 六个写点复查）                             *)
(*   - 过期判决只在 poll 入口（W5）：建模为 Awaiting 状态下的非确定性动作      *)
(*   - token 交换+usage 校验合并为 Validating 一步：其内部网络等待不展开，     *)
(*     取消可见行为由写点 stale 复查决定                                       *)
(*   - OkHttp 在飞不可打断：取消只清控制态，已交付响应照常处理                 *)
(*   - transient/pending 合并为「回 Awaiting 再轮询」                          *)
(*   - Codex 版 commit 全程持锁（W2）：CommitWrite 期间 cancel 不使能           *)
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
NIL == "nil"

VARIABLES
  ctrl,            \* 控制态
  ui,              \* 对外展示态
  attemptEpoch,    \* attempt 代次（startLogin 重进 +1）
  pollEpoch,       \* 在飞 poll 携带的代次（NIL=无在飞）
  inFlightResp,    \* 已交付未处理的响应 ∈ Responses ∪ {NIL}
  cfMitigatedLoop, \* 影子：处理过 cfMitigated 但未终结（不变量①判定）
  storedSession,   \* 持久层会话："Valid" 初值——不变量③要保的就是它
  consumed,        \* WebView 单次触发守卫（WebViewAuthScreen.kt:149-154）
  verifyActive,    \* isVerifying（WebViewAuthScreen.kt:144-168）
  verifyCount      \* 影子：当前在飞的校验调用数（重入判定实体——
                     \* consumed 是机制，verifyCount≤1 才是要证的性质）

vars == <<ctrl, ui, attemptEpoch, pollEpoch, inFlightResp, cfMitigatedLoop,
          storedSession, consumed, verifyActive, verifyCount>>

Init ==
  /\ ctrl = "Idle"
  /\ ui = "Idle"
  /\ attemptEpoch = 0
  /\ pollEpoch = NIL
  /\ inFlightResp = NIL
  /\ cfMitigatedLoop = FALSE
  /\ storedSession = "Valid"
  /\ consumed = FALSE
  /\ verifyActive = FALSE
  /\ verifyCount = 0

\* ---- startLogin（UseCase.kt:125-132）：无条件顶掉旧 attempt，epoch+1 ----
StartLogin ==
  /\ ctrl \in {"Idle", "ValidationFailed", "Failed", "Expired", "Cancelled", "Saved"}
  /\ attemptEpoch < MaxEpoch
  /\ ctrl' = "Requesting"
  /\ ui' = "RequestingDeviceCode"
  /\ attemptEpoch' = attemptEpoch + 1
  /\ pollEpoch' = NIL
  /\ inFlightResp' = NIL
  /\ UNCHANGED <<cfMitigatedLoop, storedSession, consumed, verifyActive, verifyCount>>

\* device code 请求成功（156-164）
CompleteRequestDeviceCode ==
  /\ ctrl = "Requesting"
  /\ ctrl' = "Awaiting"
  /\ ui' = "AwaitingUserAuthorization"
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, cfMitigatedLoop,
                 storedSession, consumed, verifyActive, verifyCount>>

\* device code 请求失败（136-146，含 cf blocked 与端点 invalid）
FailRequestDeviceCode ==
  /\ ctrl = "Requesting"
  /\ ctrl' = "Failed"
  /\ ui' = "Failed"
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, cfMitigatedLoop,
                 storedSession, consumed, verifyActive, verifyCount>>

\* poll 入口（168-181）：pollInFlight 重入锁——同 attempt 同时最多一个在飞。
\* ViewModel schedulePoll（DeviceCodeLoginViewModel.kt:168-211）驱动节奏。
StartPoll ==
  /\ ctrl = "Awaiting"
  /\ pollEpoch = NIL
  /\ ctrl' = "PollFlight"
  /\ pollEpoch' = attemptEpoch
  /\ UNCHANGED <<ui, attemptEpoch, inFlightResp, cfMitigatedLoop,
                 storedSession, consumed, verifyActive, verifyCount>>

\* poll 入口过期判决（173-178）：expiresAt 只在入口检查（W5）
ExpireAtPollEntry ==
  /\ ctrl = "Awaiting"
  /\ pollEpoch = NIL
  /\ ctrl' = "Expired"
  /\ ui' = "Expired"
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, cfMitigatedLoop,
                 storedSession, consumed, verifyActive, verifyCount>>

\* 网络层交付响应（OkHttp 在飞不可打断——取消后响应照常交付）
DeliverResponse(r) ==
  /\ ctrl = "PollFlight"
  /\ inFlightResp = NIL
  /\ inFlightResp' = r
  /\ UNCHANGED <<ctrl, ui, attemptEpoch, pollEpoch, cfMitigatedLoop,
                 storedSession, consumed, verifyActive, verifyCount>>

\* 处理响应（UseCase.kt:188-213 写点 stale 复查）：
\* fresh 按 CodexDeviceCodeClient.kt:114-138 真实分流；stale 一字不写。
ProcessResponse ==
  /\ ctrl = "PollFlight"
  /\ inFlightResp # NIL
  /\ LET r == inFlightResp
         fresh == (pollEpoch = attemptEpoch)
     IN
       /\ pollEpoch' = NIL
       /\ inFlightResp' = NIL
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
       /\ UNCHANGED <<attemptEpoch, storedSession, consumed, verifyActive, verifyCount>>

\* 校验完成（307-316, 342-347）
CompleteValidation ==
  /\ ctrl = "Validating"
  /\ ctrl' = "ReadyToValidate"
  /\ ui' = "ValidatingUsage"
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, cfMitigatedLoop,
                 storedSession, consumed, verifyActive, verifyCount>>

\* 校验失败——ValidationFailed 驻留可重试，attempt 不清（358-364, W8）
FailValidation ==
  /\ ctrl = "Validating"
  /\ ctrl' = "ValidationFailed"
  /\ ui' = "ValidationFailed"
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, cfMitigatedLoop,
                 storedSession, consumed, verifyActive, verifyCount>>

\* commit 持锁写入（372-403 + RoomQuotaStores.kt:106-176）：
\* Codex 版整个持久化在 stateMutex 内——写点不可与 cancel 交错（W2）。
\* 覆盖式写（relogin 设计语义；不是删除）。
CommitWrite ==
  /\ ctrl = "ReadyToValidate"
  /\ ctrl' = "CommitWrite"
  /\ UNCHANGED <<ui, attemptEpoch, pollEpoch, inFlightResp, cfMitigatedLoop,
                 storedSession, consumed, verifyActive, verifyCount>>

CommitFinish ==
  /\ ctrl = "CommitWrite"
  /\ ctrl' = "Saved"
  /\ ui' = "Saved"
  /\ storedSession' = "Valid"   \* 覆盖式写入完成
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, cfMitigatedLoop,
                 consumed, verifyActive, verifyCount>>

\* retryValidation（227-235）
RetryValidation ==
  /\ ctrl = "ValidationFailed"
  /\ ctrl' = "Validating"
  /\ ui' = "ValidatingUsage"
  /\ UNCHANGED <<attemptEpoch, pollEpoch, inFlightResp, cfMitigatedLoop,
                 storedSession, consumed, verifyActive, verifyCount>>

\* cancelLatest（252-259 + ViewModel cancelLogin 154-166）：
\* ★ 持锁排斥——CommitWrite 期间 cancel 抢不到锁（W2：Codex 版 commit 全程在锁内），
\* 用「CommitWrite 不在使能集」忠实表达阻塞语义。
\* 无 attempt 时 cancel 是 no-op——只有非终态驻留才有实质效果。
CancelLogin ==
  /\ ctrl \in {"Requesting", "Awaiting", "PollFlight", "Validating",
               "ReadyToValidate", "ValidationFailed"}
  /\ ctrl' = "Cancelled"
  /\ ui' = "Cancelled"
  /\ pollEpoch' = NIL
  /\ inFlightResp' = NIL   \* 控制态清除后，迟到响应由 stale 检查丢弃——
                           \* 折叠为直接丢弃（效果等价：反正写不进状态）
  /\ UNCHANGED <<attemptEpoch, cfMitigatedLoop, storedSession, consumed, verifyActive, verifyCount>>

\* ---- WebView 流 isVerifying/consumed（WebViewAuthScreen.kt:144-168）----
\* 检查（153）与设置（154）在 Compose 主线程快照内相邻无出让点——单原子动作。
SubmitCredential ==
  /\ ~consumed
  /\ consumed' = TRUE
  /\ verifyActive' = TRUE
  /\ verifyCount' = verifyCount + 1
  /\ UNCHANGED <<ctrl, ui, attemptEpoch, pollEpoch, inFlightResp, cfMitigatedLoop, storedSession>>

VerifySuccess ==
  /\ verifyActive
  /\ verifyActive' = FALSE
  /\ verifyCount' = verifyCount - 1
  /\ UNCHANGED <<ctrl, ui, attemptEpoch, pollEpoch, inFlightResp, cfMitigatedLoop, storedSession, consumed>>

\* 校验失败重新武装（163-167：consumed=false 允许重试——设计语义）
VerifyFailure ==
  /\ verifyActive
  /\ verifyActive' = FALSE
  /\ verifyCount' = verifyCount - 1
  /\ consumed' = FALSE
  /\ UNCHANGED <<ctrl, ui, attemptEpoch, pollEpoch, inFlightResp, cfMitigatedLoop, storedSession>>

Next ==
  \/ StartLogin
  \/ CompleteRequestDeviceCode
  \/ FailRequestDeviceCode
  \/ StartPoll
  \/ ExpireAtPollEntry
  \/ \E r \in Responses : DeliverResponse(r)
  \/ ProcessResponse
  \/ CompleteValidation
  \/ FailValidation
  \/ CommitWrite
  \/ CommitFinish
  \/ RetryValidation
  \/ CancelLogin
  \/ SubmitCredential
  \/ VerifySuccess
  \/ VerifyFailure

Spec == Init /\ [][Next]_vars

\* ================= 三条派单不变量 =================

\* ① Cloudflare 封锁类 403（含 cf-mitigated 质询）必立即终结进 CfBlocked/Failed——
\*    影子判定：cfMitigated 属 Cloudflare 封锁响应（外部事实：CF 质询页），
\*    凡处理它之后回到 Awaiting 继续轮询即「未终结」，标记置位且永不清除。
\*    修复版实现（cfMitigated → Failed）永不置位。
\*    ★ 预期被抓：现实现把 cfMitigated 当 Pending 回 Awaiting（继续轮询到 expiry）。
InvCfBlockedTerminates ==
  cfMitigatedLoop = FALSE

\* ② isVerifying 期间禁重复提交：
\*    同时在飞的校验调用 never 超过 1——consumed 是机制，本计数是性质本身。
\*    变异测试（拆 consumed 守卫）必须让它被抓。
InvNoDuplicateSubmit ==
  verifyCount <= 1

\* ③ 取消/过期/失败绝不删除既有合法 OAuth 会话：
\*    storedSession 一旦 Valid 永远 Valid（唯一写点是覆盖式 CommitFinish）。
InvSessionPreserved ==
  storedSession = "Valid"

TypeOK ==
  /\ ctrl \in CtrlStates
  /\ storedSession \in {"None", "Valid"}
  /\ consumed \in BOOLEAN
  /\ verifyActive \in BOOLEAN

====
