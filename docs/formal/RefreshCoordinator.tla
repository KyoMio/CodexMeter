---- MODULE RefreshCoordinator ----
(***************************************************************************)
(* CodexMeter RefreshCoordinator + 账号切换 + 本地编辑 并发状态机（CM-7 R1）    *)
(* 对照实现逐条行号见 docs/formal/MAPPING.md。抽象边界见文件末尾注释。         *)
(*                                                                           *)
(* R1 修正（御史 cm7-review.md 四项之①②）：                                   *)
(*   - 帧保留捕获 epoch（capGen），不再只盖落地瞬间的世界 gen——                *)
(*     「旧帧覆盖已提交编辑帧」独立可检；                                      *)
(*   - commitIfFresh 拆成 ValidateEpoch（授权时刻）与 WritePublish（生效时刻）  *)
(*     两步：生产 commitMutex 只串行化发布者之间，编辑（原子计数/DataStore 写）   *)
(*     不抢锁、可以插入两步之间——初版合并为不可分割动作是抽象错误。              *)
(***************************************************************************)
EXTENDS Naturals, TLC, FiniteSets

CONSTANTS MaxGen, MaxSessions

Accounts == {"A", "B"}

Max(S) == CHOOSE x \in S : \A y \in S : x >= y

VARIABLES
  gen,             \* VersionedStateGuard.generation（全局共享，非 per-account）
  inFlightEdits,   \* guard 在飞编辑计数（isFresh 要求其 = 0）
  selected,        \* 当前选中账号（DataStore）
  editing,         \* 是否有编辑处于 beginEdit/endEdit 窗口内
  sessions,        \* 在飞刷新会话集合
  nextVer,         \* 单调数据版本号
  snapA, snapB,    \* 各账号 last-known-good 快照版本（0=无；append-only 只增）
  published,       \* 呈现层最后一帧 [src, ver, kind, gen, capGen]
  commitHeld,      \* commitMutex 持有标志
  maxSnapA,        \* 影子历史：snapA 峰值（不变量③）
  maxSnapB,        \* 影子历史：snapB 峰值
  maxPubGen,       \* 影子历史：published 帧 gen 水位
  maxPubEditGen,   \* 影子历史：edit 帧 gen 峰值（R1 ①判定——旧帧不得倒退它）
  lastWriteSel,    \* 影子：最近一次 refresh 帧落地瞬间的 selected（不变量②）
  pubViolations    \* 影子计数：stale/错账号写入被拦截次数（佐证丢弃路径真实存在）

vars == <<gen, inFlightEdits, selected, editing, sessions, nextVer, snapA, snapB,
          published, commitHeld, maxSnapA, maxSnapB, maxPubGen, maxPubEditGen,
          lastWriteSel, pubViolations>>

Init ==
  /\ gen = 0
  /\ inFlightEdits = 0
  /\ selected = "A"
  /\ editing = FALSE
  /\ sessions = {}
  /\ nextVer = 1
  /\ snapA = 0 /\ snapB = 0
  /\ published = [src |-> "A", ver |-> 0, kind |-> "none", gen |-> 0, capGen |-> 0]
  /\ commitHeld = FALSE
  /\ maxSnapA = 0 /\ maxSnapB = 0
  /\ maxPubGen = 0
  /\ maxPubEditGen = 0
  /\ lastWriteSel = "A"
  /\ pubViolations = 0

SnapOf(acct) == IF acct = "A" THEN snapA ELSE snapB

\* ---- 刷新会话（RefreshCoordinator.kt:54-86）----
\* captureReadToken（VersionedStateGuard.kt:62-63）原子读 generation；
\* single-flight（57-61 行）同账号最多一个会话。
StartRefresh(acct) ==
  /\ Cardinality(sessions) < MaxSessions
  /\ nextVer <= 4   \* 状态数控制：数据版本有界化（版本新鲜度与 epoch 语义正交）
  /\ ~\E s \in sessions : s.account = acct
  /\ sessions' = sessions \cup {[account |-> acct, epochGen |-> gen, ver |-> nextVer,
                                 phase |-> "io", validated |-> FALSE]}
  /\ nextVer' = nextVer + 1
  /\ UNCHANGED <<gen, inFlightEdits, selected, editing, snapA, snapB, published,
                 commitHeld, maxSnapA, maxSnapB, maxPubGen, maxPubEditGen,
                 lastWriteSel, pubViolations>>

\* provider I/O 完成 → 耐久层写（recordSuccess:158-174 / recordFailure:221）。
\* 成功：快照追加（单调升）；失败：快照不动（recordFailure 只写 attempt——不变量③的机制）。
FinishIO(s) ==
  /\ s \in sessions
  /\ s.phase = "io"
  /\ \E successOutcome \in BOOLEAN :
       /\ IF successOutcome
          THEN IF s.account = "A"
               THEN /\ snapA' = s.ver
                    /\ maxSnapA' = Max({maxSnapA, s.ver})
                    /\ snapB' = snapB
                    /\ maxSnapB' = maxSnapB
               ELSE /\ snapB' = s.ver
                    /\ maxSnapB' = Max({maxSnapB, s.ver})
                    /\ snapA' = snapA
                    /\ maxSnapA' = maxSnapA
          ELSE /\ snapA' = snapA /\ snapB' = snapB
               /\ maxSnapA' = maxSnapA /\ maxSnapB' = maxSnapB
       /\ sessions' = (sessions \ {s}) \cup {[s EXCEPT !.phase = "durable"]}
       /\ UNCHANGED <<gen, inFlightEdits, selected, editing, nextVer, published,
                      commitHeld, maxPubGen, maxPubEditGen, lastWriteSel, pubViolations>>

\* 耐久写完 → 进入发布段，先抢 commitMutex（publishCurrentState，234-274 行）。
\* commitMutex 只串行化发布者之间——编辑不抢锁（见 ValidateEpoch/WritePublish）。
EnterValidate(s) ==
  /\ s \in sessions
  /\ s.phase = "durable"
  /\ ~commitHeld
  /\ commitHeld' = TRUE
  /\ sessions' = (sessions \ {s}) \cup {[s EXCEPT !.phase = "validating"]}
  /\ UNCHANGED <<gen, inFlightEdits, selected, editing, nextVer, snapA, snapB,
                 published, maxSnapA, maxSnapB, maxPubGen, maxPubEditGen,
                 lastWriteSel, pubViolations>>

\* R1 ② 核心修正：授权时刻与生效时刻分离。
\* commitIfFresh（VersionedStateGuard.kt:116-130）的「重读 selected + isFresh 校验」
\* 是临界区前半；write 是后半。两半之间 write 可挂起（生产 write 是 suspend lambda，
\* 含帧构造），而编辑（beginEdit/endEdit CAS、DataStore 写）不抢 commitMutex——
\* 御史 guard.log 实证：「切换 A→B 并结束编辑」可插入已通过校验的写回之前。
\* 因此建模为两个动作，编辑可插入其间。
ValidateEpoch(s) ==
  /\ s \in sessions
  /\ s.phase = "validating"
  /\ LET fresh == (gen = s.epochGen) /\ (inFlightEdits = 0) /\ (s.account = selected)
     IN sessions' = (sessions \ {s}) \cup
                    {[s EXCEPT !.phase = "writing", !.validated = fresh]}
  /\ UNCHANGED <<gen, inFlightEdits, selected, editing, nextVer, snapA, snapB,
                 published, commitHeld, maxSnapA, maxSnapB, maxPubGen, maxPubEditGen,
                 lastWriteSel, pubViolations>>

\* 生效时刻：write 执行（validated=TRUE）或丢弃（FALSE），随后释放锁。
\* ★ 校验与落地之间不重查——忠实 commitIfFresh 只在授权时刻校验一次的实现。
\* 帧的 capGen 记捕获 epoch（R1 ①修正）；gen 记落地瞬间世界 gen（呈现序观测）。
WritePublish(s) ==
  /\ s \in sessions
  /\ s.phase = "writing"
  /\ IF s.validated
     THEN /\ published' = [src |-> s.account, ver |-> s.ver, kind |-> "refresh",
                           gen |-> gen, capGen |-> s.epochGen]
          /\ maxPubGen' = Max({maxPubGen, gen})
          /\ lastWriteSel' = selected
          /\ pubViolations' = pubViolations
     ELSE /\ published' = published
          /\ maxPubGen' = maxPubGen
          /\ lastWriteSel' = lastWriteSel
          /\ pubViolations' = pubViolations + 1   \* 拦截计数：证明丢弃路径真实存在
  /\ commitHeld' = FALSE
  /\ sessions' = sessions \ {s}
  /\ UNCHANGED <<gen, inFlightEdits, selected, editing, nextVer, snapA, snapB,
                 maxSnapA, maxSnapB, maxPubEditGen>>

\* perAccountEffects（RefreshCoordinator.kt:253）guard 之外无条件发出——
\* 不进 published（通知/widget 副作用），模型里折叠不占状态位。

\* ---- 本地编辑（切换选中账号：AccountMutationRepository.kt:45-62）----
\* beginEdit（VersionedStateGuard.kt:71-80）CAS 原子步：gen+1 且 inFlightEdits+1。
\* ★ 不抢 commitMutex——可在 WritePublish 生效前插入（生产 guard.log 实证）。
BeginEdit ==
  /\ ~editing
  /\ gen < MaxGen
  /\ gen' = gen + 1
  /\ inFlightEdits' = inFlightEdits + 1
  /\ editing' = TRUE
  /\ UNCHANGED <<selected, sessions, nextVer, snapA, snapB, published, commitHeld,
                 maxSnapA, maxSnapB, maxPubGen, maxPubEditGen, lastWriteSel, pubViolations>>

\* 编辑窗口内的 DataStore 写（AccountMutationRepository.kt:52）
SwitchSelection(acct) ==
  /\ editing
  /\ acct # selected
  /\ selected' = acct
  /\ UNCHANGED <<gen, inFlightEdits, editing, sessions, nextVer, snapA, snapB,
                 published, commitHeld, maxSnapA, maxSnapB, maxPubGen, maxPubEditGen,
                 lastWriteSel, pubViolations>>

\* endEdit（87-99 行）：gen 再 +1——窗口内捕获的 epoch 永久 stale（+2 语义）
EndEdit ==
  /\ editing
  /\ gen' = gen + 1
  /\ inFlightEdits' = inFlightEdits - 1
  /\ editing' = FALSE
  /\ UNCHANGED <<selected, sessions, nextVer, snapA, snapB, published, commitHeld,
                 maxSnapA, maxSnapB, maxPubGen, maxPubEditGen, lastWriteSel, pubViolations>>

\* 编辑后的补偿重发（CurrentAccountQuotaStateRepublisher.kt:29-72 /
\* Republishing* 系列）：commitSerialized 不做 epoch 校验，内容按重读耐久层。
\* edit 帧的 capGen 记落地瞬间 gen（重发不携带历史捕获——它就是当前代的表达）。
EditRepublish ==
  /\ ~editing
  /\ ~commitHeld   \* commitSerialized 与 commitIfFresh 共用 commitMutex（147-152 行）
  /\ published' = [src |-> selected, ver |-> SnapOf(selected), kind |-> "edit",
                   gen |-> gen, capGen |-> gen]
  /\ maxPubGen' = Max({maxPubGen, gen})
  /\ maxPubEditGen' = Max({maxPubEditGen, gen})
  /\ UNCHANGED <<gen, inFlightEdits, selected, editing, sessions, nextVer, snapA, snapB,
                 commitHeld, maxSnapA, maxSnapB, lastWriteSel, pubViolations>>

Next ==
  \/ \E acct \in Accounts : StartRefresh(acct)
  \/ \E s \in sessions : FinishIO(s) \/ EnterValidate(s) \/ ValidateEpoch(s) \/ WritePublish(s)
  \/ BeginEdit
  \/ \E acct \in Accounts : SwitchSelection(acct)
  \/ EndEdit
  \/ EditRepublish

Spec == Init /\ [][Next]_vars

\* ================= 派单不变量（R1 拆分报告） =================

\* ①a（派单①·安全方向）：已发布的新编辑不被旧刷新帧覆盖——
\*    帧保留捕获 epoch（capGen）后独立可检：refresh 帧落地时其 capGen
\*    不得严格小于历史 edit 帧的 gen 峰值。
\*    机制：isFresh 的 gen 相等检查（VersionedStateGuard.kt:107）使通过校验的
\*    refresh capGen == 授权时刻 gen ≥ 任何已提交 edit 帧 gen（编辑推进 gen）。
\*    able-to-fail：拆掉 epoch 比对后授权可在陈旧 capGen 通过——必须被抓。
InvRefreshNeverRegressesEdit ==
  published.kind = "refresh" => published.capGen >= maxPubEditGen

\* ①b（观测水位，保留自初版）：呈现帧 gen 序不倒退。
InvEpochNeverOverwritesEdit == published.gen >= maxPubGen

\* ② 旧账号晚返回不污染新账号（落地瞬间账号相符）：
\*    refresh 帧落地瞬间其出处账号必等于当时的 selected。
\*    R1 ②拆步后本性质与①分离报告——校验通过后 SwitchSelection 可插入
\*    生效时刻之前，「校验时相符」不再蕴含「落地时相符」。
InvNoCrossAccountPollution ==
  published.kind = "refresh" => published.src = lastWriteSel

\* ③ 刷新报错绝不擦除有效 last-known-good：
\*    快照版本单调不降（append-only + 失败不写快照，机制上无擦除路径）。
InvSnapshotNeverErasedA == snapA >= maxSnapA
InvSnapshotNeverErasedB == snapB >= maxSnapB

TypeOK ==
  /\ gen \in Nat
  /\ inFlightEdits \in Nat
  /\ selected \in Accounts
  /\ snapA \in Nat /\ snapB \in Nat
  /\ published.ver \in Nat
  /\ published.kind \in {"refresh", "edit", "none"}
  /\ commitHeld \in BOOLEAN

====
