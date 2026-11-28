# RefreshCoordinator

Model checked with `tlc -deadlock` using bounded constants (`MaxGen = 6`, `MaxSessions = 2`, `nextVer <= 4`).
On `RefreshCoordinator-pass.cfg`, TLC explored **22,605,047** generated states (**7,501,510** distinct states, **0** left on queue) to search depth **32** and exited **0**.

The companion diagram `refresh-coordinator.mmd` is a hand-authored phase-machine visualization comprising **3 sub-machines** (`session`, `editing`, `republish`), not an auto-exported full state graph.

## Rules that hold (`RefreshCoordinator-pass.cfg`)
`TypeOK`, `InvRefreshNeverRegressesEdit`, `InvSnapshotNeverErasedA`, `InvSnapshotNeverErasedB`

> Note: `InvEpochNeverOverwritesEdit` (`published.gen >= maxPubGen`) in `RefreshCoordinator.tla` is an algebraic diagnostic identity (tautological under `gen` monotonicity) and is excluded from `.cfg` runs.

## Rules that break (known findings)
- **`InvNoCrossAccountPollution`** (`RefreshCoordinator-finding.cfg`, exit **12**, **1,849** distinct states / **3,400** generated / depth **8** under `-workers 1` -> tracked as **CM-10**):
  Because `commitMutex` only serializes publishers while `beginEdit()` / DataStore writes do not acquire `commitMutex`, a concurrent account switch can interleave between `ValidateEpoch` (authorization) and `WritePublish` (effect). Shortest counterexample (8 states / 7 steps):
  1. `Init`: `selected = "A"`, `gen = 0`, `snapA = 0`
  2. `StartRefresh("A")`: captures `epochGen = 0`, `phase = "io"`
  3. `FinishIO`: failure branch keeps `snapA = 0` and resets `s.ver = 0`, `phase = "durable"`
  4. `EnterValidate`: acquires `commitHeld = TRUE`, `phase = "validating"`
  5. `ValidateEpoch`: checks `fresh = TRUE` (`gen=0`, `inFlightEdits=0`, `selected="A"`), enters `phase = "writing"`
  6. `BeginEdit`: `gen = 1`, `inFlightEdits = 1`, `editing = TRUE` (interleaves without `commitMutex`)
  7. `SwitchSelection("B")`: `selected = "B"`
  8. `WritePublish`: commits stale frame `published = [gen |-> 1, src |-> "A", ver |-> 0, kind |-> "refresh", capGen |-> 0]` while `lastWriteSel = "B"` -> violates `InvNoCrossAccountPollution`.
