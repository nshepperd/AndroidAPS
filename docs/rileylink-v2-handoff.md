# RileyLink v2 — Session Handoff (2026-07-02)

For the next working session. Companion docs: `docs/rileylink-v2-design.md` (architecture,
the authority on intent), `/home/em/Dev/aps/Orangelink-Firmware/docs/HANDOFF-protocol-v2.md`
(firmware work spec, separate agent). Personal build; Emily wears this system.

## Where things stand

Branch `emily.connectivity`, all committed, tree clean. Chronology of today:

1. Diagnosed why the legacy stack never auto-reconnects (three root causes: terminal
   `PumpConnectorError` + lying `isConnecting()`; GATT discovery starved behind zombie tunes
   on the single-thread executor; no fail-fast on dead BLE). Tactical fixes committed
   (`1e4ffab3be`) — these live in the legacy module and still matter for Omnipod Eros.
2. Designed and built the replacement stack `pump/rileylink2` (43 JVM tests): L0
   `BleLink`/`AndroidBleLink`, L1 `RfspySession` (wire format pinned by a golden frame
   captured from the live legacy stack), L2 `MinimedRadio` (4b6b/CRC-8 ports, bounded wake,
   fail-fast tune), L3 `PumpLinkSupervisor` (actor state machine, no-dead-end states,
   demand-gated recovery).
3. **Full conversion of the Medtronic driver** (`aa6bcf1920`): legacy RileyLink machinery no
   longer executes for Medtronic. 140 medtronic tests green, full APK builds.
4. **Flashed and bench-confirmed**: v2 talks to the real OrangeLink + Minimed 722 on the
   bench and is already behaving better than the legacy driver. Bench = pump disconnected
   from body; all commands (including bolus-into-air) are safe to exercise.

Rollback at any time: `git revert aa6bcf1920` (driver back on legacy stack) or reflash a
pre-conversion APK. Legacy module is untouched and Eros-compatible.

## Conversion architecture (what changed where)

- `MedtronicUIComm.executeCommand()` — THE blocking↔suspend bridge. Command queue and all
  plugin dosing call sites unchanged; inside, each command is one `supervisor.withPump`
  block (SetBolus = HIGH priority, 90 s ready-timeout vs QueueWorker's 120 s ceiling).
- `MedtronicCommunicationManager` — suspend, takes `MinimedRadio`; protocol logic verbatim
  from legacy (retry budgets, 16-frame history ACK flow, basal frames, PumpDeviceState
  transitions). `MedtronicCommException` replaces RileyLinkCommunicationException in the
  same try/catch shapes. `verifyPumpPresence(radio)` replaces `isDeviceReachable()`.
- `MedtronicPumpLinkService` — owns scope + `AndroidBleLink` + supervisor; ports
  `verifyConfiguration` (supervisor restart on MAC/serial/frequency-region change; also
  forced via `triggerPumpConfigurationChangedEvent` after the MAC-scan dialog); bridges
  `PumpLinkState` → legacy `RileyLinkServiceData.setServiceState` so MedtronicFragment /
  RileyLinkStatusActivity work unchanged.
- `MedtronicPumpPlugin` — `isConnected` = supervisor `Ready`; `isConnecting` = genuinely
  in-progress only; `connect()/disconnect()` = demand lease (BLE stays connected per design
  §8.4; demand gates wake/tune retries); WakeAndTune action → `requestTune()`; ResetRLConfig
  action → `restartSupervisor()`. Still implements `RileyLinkPumpDevice` with
  `rileyLinkService = null` — RileyLinkBLEConfigActivity and RileyLinkStatusGeneralFragment
  hard-cast the active pump but only use `pumpInfo`/`lastConnectionTimeMillis`/safe-calls.

## Bench test plan (staged; continue from wherever reality is)

Confirmed so far: bring-up → tune → Ready and basic operation (first flash worked).

1. **Read-only soak**: PumpModel, status refreshes, history pages over several hours.
   Watch: `LINKV2:` transitions (journal), wake success rate, tune outcomes.
2. **Fault drills** (the whole point of v2 — each must recover unaided):
   - Pump out of radio range 20+ min → back in range → Ready within one KeepAlive cycle +
     tune (~5-8 min worst case). This is the original incident (F1).
   - OrangeLink power-cycle mid-session and mid-command (F3) → LinkDown → reconnect →
     full re-init → Ready in seconds, not minutes.
   - OrangeLink out of BLE range → back (F2). Phone BT toggle (F8).
3. **Mutating commands**: TBR set/cancel, SetBolus into the air, basal profile write.
   Verify pump-side state (pump screen) matches AAPS's belief; verify history read
   reconciles the air-bolus.
4. **Q3 wake-split validation**: v2 wakes with 5×(200-repeat burst + 4.5 s listen) instead
   of legacy 1×25 s listen. If wake success looks worse than legacy on the bench, revisit
   `MinimedRadio.WAKE_*` constants first (design doc §15 Q3 has the fallback plan).
5. **Multi-day wear-eligibility bar** (adapted design §12.4): no manual interventions, no
   unexplained `LinkDown/PumpUnreachable > 10 min`, no `Uncertain` bursts, bolus/TBR
   verified correct on pump screen every time. Only then wear it.

Log workflow: `adb pull /storage/emulated/0/Android/data/info.nightscout.androidaps/files/AndroidAPS.log`;
grep `LINKV2:` (v2 transitions), `PUMPCOMM`/`PUMPBTCOMM` (protocol), `RECONN:` (legacy-module
diagnostics, still present in the Eros-shared code). Build: `./gradlew :app:assembleFullDebug`
(debug keystore = symlink `new-debug.keystore` → `../new-debug.keystore`).

## Bench results so far (2026-07-02 evening, build 3a1b16b287)

- Bring-up → Ready in 3.7 s; QueueWorker connect 2 s; multi-page history fetch OK
  (3 transient frame-ACK retries, self-recovered; RSSI swinging −74…−106 on the bench).
- F3-style BLE drop (GATT status 8, 19:18): unaided recovery LinkDown → Ready in 3.6 s;
  legacy UI state bridge tracked correctly.
- Pump-silent episode (19:24–19:26): 2 wake windows timed out, 3rd succeeded; "Pump
  unreachable" raised + auto-cleared; SMB queued during outage delivered after recovery;
  demanded tune 2.4 s, stayed 916.7 MHz. No dead ends, no manual intervention.
- **I9 data point**: 19:26:53 SMB SetBolus hit the retry path (prelude ACKed, param frame
  sent, pump silent → full resend → ACKed). Pump history shows **one** 0.5 U bolus at
  19:27 — no double delivery. Benign half of the exposure (pump likely never decoded the
  first param frame); the acts-but-ACK-lost half remains open until I9 tightening.
- **F8 drill (phone BT toggle, 19:44)**: recovered unaided but slowly — write failure was
  the first sign of BT-off (no adapter-state listener), then 7 blind 30 s connect attempts
  ("bring-up timed out", zero GATT callbacks) while the Android BT stack restarted
  (~2 min, logcat) and after; attempt 7 connected at 19:48:53, Ready <1 s later. Two
  hardening items: (a) register `BluetoothAdapter.ACTION_STATE_CHANGED` — close link on
  TURNING_OFF with a distinct `BtOff` cause, park reconnect until STATE_ON; (b) pass
  `TRANSPORT_LE` explicitly to `connectGatt` (TRANSPORT_AUTO + cold device cache after a
  stack restart is a known silent-fail source, may explain post-restart attempts 4–6).
- Post-F8 tune failed (pump silent) → PumpUnreachable at 19:49:47 — pump-side RF was
  already degraded before the toggle (freq scans −91…−108 vs −74 at session start);
  likely bench geometry, not BT. Recovered unaided 19:55: retry tune hit a setFrequency
  deadline → LinkDown → reconnect → Ready, ~13 s total.
- **F1 drill (pump away ~20:25–21:11, chart build 67f62ace32)**: PASSED the recovers-unaided
  bar — first solid exchange at 21:11:06 (−89 dBm) seconds after pump returned; TBR
  1.25 U/h enacted at 21:11:08. Twist: pump was edge-of-range, not absent — the 20:42 tune
  heard it at ~−100 and moved 916.70 → 916.55 MHz; marginal probes (−97…−101) succeeded
  from 21:01. Effectively also a flapping-RF test; no dead ends throughout.
- New watch items from the drills:
  1. Escalation latency: supervisor held Ready ~13 min (20:25–20:38) while every wake
     failed; notifications fired but reportPumpUnreachable/tune escalation lagged. Check
     the plugin's escalation threshold vs intent. **Root cause identified 2026-07-03: see
     paranoid-review finding R1-a — legacy's 10-min timeout→tune escalation in
     sendAndListen has no v2 equivalent.**
  2. setFrequency deadline (3 s) tears down the link mid-tune when firmware is busy —
     seen twice (19:55 @916.5, 20:38 @916.55). Self-recovers in seconds but is churn;
     consider a longer register-write deadline or draining radio ops first.
  3. lastGoodFrequency now 916.55, chosen on a −100 signal during the drill; quick-path
     keeps it while the pump answers. Re-seat with WakeAndTune at close range.
- Zero `Uncertain`/`LinkTrouble`/GATT 133 across the whole v2 window.
- Q3 wake-split: 2 of ~6 wakes needed >1 window, but RF variance muddies any comparison
  with legacy — not yet actionable.

## Drill matrix complete (2026-07-03 afternoon, post-fix build 5c37566760)

- **F2 (OL out of BLE range, via foil wrap)**: PASSED. ~6 min wrapped, 9 connect attempts
  (30 s timeout each, backoff at 30 s ceiling), connected the moment the foil came off →
  SessionInit → Ready in 2.7 s, pump exchanges resumed at once. No response-count desync
  after re-init with OL's preserved counter (B1 fix verified in anger).
- **Basal profile write**: PASSED — SetBasalProfileSTD via runCommandWithFrames, 2.3 s,
  read-back matched all 24 h.
- All fault drills (F1/F2/F3/F8) and all mutating command classes now bench-verified.
  Still open before wear: overnight re-soak on the fixed build, WakeAndTune re-seat off
  916.55, F8 hardening, escalation-latency + setFrequency-deadline tweaks, paranoid review.

## Overnight soak 2026-07-02→03: three bugs found, all fixed 2026-07-03

Soak (bench, chart build): supervisor never dead-ended (30 transitions, all recovered),
but three real bugs surfaced. Loop was silently wedged 03:10–10:22 (restart cleared it).

- **B1 — response-count wrap is mod 255, not mod 256.** Firmware `ble_ips.c` zeroes the
  counter on REACHING 0xff, so 255 never appears on the wire; RfspySession expected mod
  256 → false desync + link teardown every ~255 exchanges (7×/night, each self-recovered
  ~4 s). FIXED in RfspySession + FakeOrangeLink now models the real wrap + regression test.
  The old wrap test passed because fake and session shared the same wrong model — lesson:
  fake fidelity beats fake/impl agreement.
- **B2 — withPump readyTimeout abandoned running commands.** 90 s bound covered execution,
  not just wait-for-Ready; a slow history read (marginal RF) got abandoned mid-flight,
  result discarded. FIXED: atomic PENDING→STARTED/ABANDONED lifecycle; a started block
  always runs to its outcome (maxCommandDuration still bounds it) + test.
- **B3 — abandoned command wedged the AAPS command queue permanently.** Chain: B2 left
  task.result null → `historyResult!!` NPE in readPumpHistoryLogic (unlogged) → threw
  through CommandReadStatus.execute → QueueWorker never resetPerforming() → every later
  worker run skipped both pickup and disconnect branches and spun silently (no logs, 7 h).
  KeepAlive READSTATUS "ignored as duplicated" against the stale performing command.
  FIXED twice over: null-safe history-result handling (medtronic), and QueueWorker now
  catches a throwing command, logs the stack, cancels it, and resets performing (core
  implementation module — upstream candidate).

## Paranoid-review checklist (item 1 DONE 2026-07-03; 2-4 open)

1. ~~`MedtronicCommunicationManager` side-by-side~~ **DONE 2026-07-03** — compared against
   legacy class + the base-class methods the conversion inlined
   (`RileyLinkCommunicationManager.wakeUp/sendAndListen`), eyeball + normalized diff.
   **Protocol logic is verbatim**: history frame loop (incl. quirks: cumulative 6-failure
   counter, stale-response spin on ACK failure, short-frame append), basal ACK loop
   (4 attempts vs 3 elsewhere — legacy inconsistency preserved), setCommand budget
   (4 attempts, retries on timeout AND non-ACK = known I9 class), wakeUp caching (1-min
   window, PumpModel probe — legacy PowerOn wake was already dead code, cache written even
   on failed wake), PumpDeviceState transitions, all `!!` sites legacy-identical. pumpType
   source change in getBasalProfile verified equivalent (verifyConfiguration sets both);
   `previousConnection` covered by plugin `initPumpStatusData` and is write-only anyway.
   Three behavioral deltas found, none fixed (Emily to decide):
   - **R1-a (explains drill watch item 1):** legacy base `sendAndListen` auto-fired
     WakeAndTuneTask on any command radio-timeout once >10 min since last good comm
     (`ALLOWED_PUMP_UNREACHABLE`); legacy `isDeviceReachable` had a 15-min variant. v2
     command timeouts never escalate directly — only `verifyPumpReachable()` (status-refresh
     path) and initializePump's ≥5-error tune do. This is why the supervisor held Ready
     ~13 min in the F1 drill. Candidate fix: supervisor counts consecutive PumpSilent
     command outcomes while Ready → PumpUnreachable + tune.
   - **R1-b (minor):** `notConnectedCount` semantics drifted: legacy = RFSpy-level
     (BLE-no-response + radio timeout, reset on any RL ack/radio packet); v2 = pump-level
     (PumpSilent+Garbled+LinkTrouble, reset only on fully valid reply). Feeds the
     initializePump ≥5→tune heuristic → slightly more tune-prone in flaky RF (compounds
     the existing over-tune watch item).
   - **R1-c (benign improvement):** successful `probeForPumpModel` now pre-caches the
     1-min wake window (legacy re-burst before the next command).
2. The I9 note at the top of that file: set-commands retry on radio timeout exactly like
   legacy (same double-delivery exposure class). Tightening = post-soak follow-up, using
   `RfspyResult.Uncertain` which already propagates to `MinimedRadio.LinkTrouble`.
3. Demand-lease pairing in the plugin (`queueDemandHeld`) vs QueueWorker's actual
   connect/disconnect call pattern — an unbalanced release only wastes retries (count is
   floor-0), but verify.
4. `AndroidBleLink` — the only code with minimal real-world mileage; Android GATT quirks
   (status 133 handling, MTU, notification subscription) will show up here first.

## Known gaps / watch items (accepted for now, on the list)

- **RL battery level not polled** in v2 (legacy read it after transmits). StatusActivity's
  battery display will go stale/empty. Add a periodic BATTERY_LEVEL read via L0 if wanted.
- **Encoding preference is inert** (v2 always host-side 4b6b, set unconditionally per R1).
- **Design doc I8 is wrong as written**: legacy TX *relies* on the firmware trailing-0x00
  strip (terminator). Real invariant: byte-identical frames, strip included. Fix in doc.
- `rileyLinkUtil.rileyLinkHistory` (status activity History tab) no longer gets service
  state entries for Medtronic — display-only, mostly empty now.
- `MedtronicPumpPlugin.initializePump` "5+ invalid responses → tune" now calls
  `requestTune()` (cooldown-bypassing); watch it doesn't over-tune during flaky RF.
- Firmware track runs in parallel: WI-1 (cooperative loops) is the big reliability win;
  rileylink2 already recognizes `subg_rfspy 2.2-ol1` → `ProtocolVariant.V2_OL1` (currently
  behaves as STOCK until PROTOCOL-V2.md lands and L1 grows the v2 personality + tests).

## Post-soak backlog (rough priority)

1. Fix I8 wording in design doc; record bench results + any timing tweaks in §15 answers.
2. I9 tightening: surface `Uncertain` on mutating commands to the plugin instead of silent
   retry; wire to history reconciliation explicitly.
3. RL battery polling; journal exposure in the status UI (design §11's remaining half).
4. Firmware v2 protocol support in L1 (when the firmware agent delivers PROTOCOL-V2.md) +
   FakeOrangeLink second personality + CANCEL-based long-listen wake (Q3 alternative).
   → IN PROGRESS: PROTOCOL-V2.md delivered; plan + phase status in
   `docs/rileylink-v2-protocol-v2-plan.md` (branch `emily.protocolv2`).
5. Delete the now-dead tactical-fix paths from the legacy module IF Eros doesn't want them
   (they do fix real Eros bugs — probably keep; consider upstreaming to nightscout/AndroidAPS).
6. Consider upstreaming rileylink2 itself once soak-proven.
