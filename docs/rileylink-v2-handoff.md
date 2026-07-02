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

## Paranoid-review checklist (requested, not yet done)

1. `MedtronicCommunicationManager` — side-by-side diff against
   `git show aa6bcf1920^:pump/medtronic/.../MedtronicCommunicationManager.kt`. Verify each
   protocol method is behavior-identical (esp. getPumpHistory frame loop, getBasalProfile
   ACK loop, setCommand retry budget).
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
5. Delete the now-dead tactical-fix paths from the legacy module IF Eros doesn't want them
   (they do fix real Eros bugs — probably keep; consider upstreaming to nightscout/AndroidAPS).
6. Consider upstreaming rileylink2 itself once soak-proven.
