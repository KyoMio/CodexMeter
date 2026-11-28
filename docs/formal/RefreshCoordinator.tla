---- MODULE RefreshCoordinator ----
(***************************************************************************)
(* CodexMeter RefreshCoordinator + 账号切换 + 本地编辑 并发状态机（CM-7）      *)
(* 对照实现逐条行号见 docs/formal/MAPPING.md。抽象边界见文件末尾注释。         *)
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
  published,       \* 呈现层最后一帧 [src, ver, kind, gen]
  commitHeld,      \* commitMutex 持有标志
  maxSnapA,        \* 影子历史：snapA 峰值（不变量③）
  maxSnapB,        \* 影子历史：snapB 峰值
  maxPubGen,       \* 影子历史：published 帧 gen 水位（不变量①）
  lastWriteSel,    \* 影子：最近一次 refresh 帧落地瞬间的 selected（不变量②）
  pubViolations    \* 影子计数：stale/错账号写入被拦截次数（佐证丢弃路径真实存在）

vars == <<gen, inFlightEdits, selected, editing, sessions, nextVer, snapA, snapB,
          published, commitHeld, maxSnapA, maxSnapB, maxPubGen, lastWriteSel,
          pubViolations>>

Init ==
  /\ gen = 0
  /\ inFlightEdits = 0
  /\ selected = "A"
  /\ editing = FALSE
  /\ sessions = {}
  /\ nextVer = 1
  /\ snapA = 0 /\ snapB = 0
  /\ published = [src |-> "A", ver |-> 0, kind |-> "none", gen |-> 0]
  /\ commitHeld = FALSE
  /\ maxSnapA = 0 /\ maxSnapB = 0
  /\ maxPubGen = 0
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
                 commitHeld, maxSnapA, maxSnapB, maxPubGen, lastWriteSel, pubViolations>>

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
                      commitHeld, maxPubGen, lastWriteSel, pubViolations>>

\* 耐久写完 → 进入发布段，先抢 commitMutex（publishCurrentState，234-274 行）
EnterValidate(s) ==
  /\ s \in sessions
  /\ s.phase = "durable"
  /\ ~commitHeld
  /\ commitHeld' = TRUE
  /\ sessions' = (sessions \ {s}) \cup {[s EXCEPT !.phase = "validating"]}
  /\ UNCHANGED <<gen, inFlightEdits, selected, editing, nextVer, snapA, snapB,
                 published, maxSnapA, maxSnapB, maxPubGen, lastWriteSel, pubViolations>>

\* commitIfFresh（VersionedStateGuard.kt:116-130）：
\* 「重读 selected + isFresh 校验 + write」三步在 commitMutex 内不可分割——
\* 建模为单原子动作（这是实现提供的原子性保证，不是简化）：
\* 校验失败则 write 完全不执行（丢弃计数+1）。EnterValidate 的抢锁步保留，
\* 因为锁竞争（commitSerialized 重发排队）是真实存在的交错。
ValidateCommit(s) ==
  /\ s \in sessions
  /\ s.phase = "validating"
  /\ commitHeld
  /\ LET fresh == (gen = s.epochGen) /\ (inFlightEdits = 0) /\ (s.account = selected)
     IN
       IF fresh
       THEN /\ published' = [src |-> s.account, ver |-> s.ver, kind |-> "refresh", gen |-> gen]
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
                 maxSnapA, maxSnapB>>

\* perAccountEffects（RefreshCoordinator.kt:253）guard 之外无条件发出——
\* 不进 published（通知/widget 副作用），模型里折叠不占状态位。

\* ---- 本地编辑（切换选中账号：AccountMutationRepository.kt:45-62）----
\* beginEdit（VersionedStateGuard.kt:71-80）CAS 原子步：gen+1 且 inFlightEdits+1。
BeginEdit ==
  /\ ~editing
  /\ gen < MaxGen
  /\ gen' = gen + 1
  /\ inFlightEdits' = inFlightEdits + 1
  /\ editing' = TRUE
  /\ UNCHANGED <<selected, sessions, nextVer, snapA, snapB, published, commitHeld,
                 maxSnapA, maxSnapB, maxPubGen, lastWriteSel, pubViolations>>

\* 编辑窗口内的 DataStore 写（AccountMutationRepository.kt:52）
SwitchSelection(acct) ==
  /\ editing
  /\ acct # selected
  /\ selected' = acct
  /\ UNCHANGED <<gen, inFlightEdits, editing, sessions, nextVer, snapA, snapB,
                 published, commitHeld, maxSnapA, maxSnapB, maxPubGen, lastWriteSel,
                 pubViolations>>

\* endEdit（87-99 行）：gen 再 +1——窗口内捕获的 epoch 永久 stale（+2 语义）
EndEdit ==
  /\ editing
  /\ gen' = gen + 1
  /\ inFlightEdits' = inFlightEdits - 1
  /\ editing' = FALSE
  /\ UNCHANGED <<selected, sessions, nextVer, snapA, snapB, published, commitHeld,
                 maxSnapA, maxSnapB, maxPubGen, lastWriteSel, pubViolations>>

\* 编辑后的补偿重发（CurrentAccountQuotaStateRepublisher.kt:29-72 /
\* Republishing* 系列）：commitSerialized 不做 epoch 校验，内容按重读耐久层。
EditRepublish ==
  /\ ~editing
  /\ ~commitHeld   \* commitSerialized 与 commitIfFresh 共用 commitMutex（147-152 行）
  /\ published' = [src |-> selected, ver |-> SnapOf(selected), kind |-> "edit", gen |-> gen]
  /\ maxPubGen' = Max({maxPubGen, gen})
  /\ UNCHANGED <<gen, inFlightEdits, selected, editing, sessions, nextVer, snapA, snapB,
                 commitHeld, maxSnapA, maxSnapB, lastWriteSel, pubViolations>>

Next ==
  \/ \E acct \in Accounts : StartRefresh(acct)
  \/ \E s \in sessions : FinishIO(s) \/ EnterValidate(s) \/ ValidateCommit(s)
  \/ BeginEdit
  \/ \E acct \in Accounts : SwitchSelection(acct)
  \/ EndEdit
  \/ EditRepublish

Spec == Init /\ [][Next]_vars

\* ================= 三条派单不变量 =================

\* ① 过期 SnapshotEpoch 永不覆盖新编辑：
\*    published 帧的 gen 永不严格低于历史水位 maxPubGen。
\*    refresh 帧落地时 gen=写入瞬间世界 gen（校验语义），世界 gen 单调增——
\*    任何「过期 epoch 帧覆盖新编辑帧」都会把 published.gen 打回水位之下被抓。
InvEpochNeverOverwritesEdit == published.gen >= maxPubGen

\* ② 旧账号晚返回不污染新账号：
\*    refresh 帧落地瞬间其出处账号必等于当时的 selected
\*    （commitIfFresh 的 epoch.accountId == 重读 selected，VersionedStateGuard.kt:109）。
\*    落地后被切走是合法的（编辑的领域）——判定落在影子变量：
\*    帧的 src 必等于它落地瞬间记录的 selected。
\*    语义说明：「旧账号晚返回」的污染形态是 stale 响应写进呈现层且 src 不是
\*    落地瞬间的 selected。落地之后被切走、编辑重发未到的驻留窗口是合法语义
\*    （W4 活性假设：编辑方必补发）。因此本不变量判定的是「落地瞬间匹配」这一
\*    机制的完备性——变异测试（拆掉账号比对）必须让它被抓，见 PROOF 的 able-to-fail。
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
