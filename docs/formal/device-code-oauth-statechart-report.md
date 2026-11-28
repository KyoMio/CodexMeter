# DeviceCodeOAuth

Model checked with `tlc -deadlock` using bounded `MaxEpoch = 2`.
`-deadlock` is required for the bounded model because `MaxEpoch = 2` caps `StartLogin` re-entry, and `VerifySuccess` leaves `consumed = TRUE, verifyCount = 0` (the intended terminal post-success WebView state).

On `DeviceCodeOAuth-pass.cfg`, TLC explored **12,026** generated states (**2,970** distinct states, **0** left on queue) to search depth **24** and exited **0**.
The companion diagram `device-code-oauth.mmd` draws **13 states** (`s0`–`s12`, plus 5 legend entries) and **34 arrows** (32 inter-state transitions plus `[*] --> s0` and `s12 --> [*]`).

## Rules that hold (`DeviceCodeOAuth-pass.cfg`)
`TypeOK`, `InvNoDuplicateSubmit`, `InvSessionPreserved`

## Rules that break (known findings)
- **`InvCfBlockedTerminates`** (`DeviceCodeOAuth-finding.cfg`, exit **12** -> tracked as **CM-8**):
  Cloudflare `cf-mitigated` 403 challenges without body markers (`cf-chl` / `__cf_chl` / `just a moment`) are routed to `Result.Pending` in `CodexDeviceCodeClient.kt:114-121` and return to `Awaiting` instead of terminating in `Failed`. Shortest counterexample (6 states / 5 transitions; 7 states when `StartLogin` re-enters once under multi-worker search):
  1. `Init`: `ctrl = "Idle"` (`s0`)
  2. `StartLogin`: `ctrl = "Requesting"` (`s1`)
  3. `CompleteRequestDeviceCode`: `ctrl = "Awaiting"` (`s2`)
  4. `StartPoll`: `ctrl = "PollFlight"` (`s6`)
  5. `DeliverResponse("cfMitigated")`: `ctrl = "PollFlight"`, `inFlightResp = "cfMitigated"` (`s6`)
  6. `ProcessResponse`: returns to `ctrl = "Awaiting"` (`s2`) with `cfMitigatedLoop = TRUE` -> violates `InvCfBlockedTerminates`.
