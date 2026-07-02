# RileyLink/Minimed Connectivity Stack v2 — Design

Status: DRAFT for review · Author: Claude + Emily · Date: 2026-07-02
Scope: AndroidAPS side only. The OrangeLink firmware is treated as a fixed peer.

---

## 1. Purpose and scope

Replace the AAPS RileyLink connectivity stack (the `pump/rileylink` module: `RileyLinkBLE`,
`RFSpy`, `RFSpyReader`, `RileyLinkService` + broadcast plumbing, `ServiceTaskExecutor` and its
tasks, `RileyLinkServiceData`) with a new stack designed for correctness and reliability under
link failure. The Medtronic *protocol* layer (message framing, command semantics, history
parsing in `pump/medtronic/comm/**`) is preserved semantically: identical bytes on the radio,
identical retry/timing budgets, adapted only at its call boundary.

**Goals**

- G1. Self-healing: from any reachable fault state, the stack returns to `Ready` without user
  action once the physical situation allows it. No dead-end states.
- G2. Bounded reaction time: link-state changes (BLE drop, firmware reboot) cancel affected
  work within ~1 s, not after multi-minute timeout chains.
- G3. Single source of truth for connection state; no stale flags.
- G4. Explicit failure taxonomy: no `null` returns, no sentinel counters; every operation
  returns a typed result including `Uncertain` where delivery is unknowable.
- G5. Deterministic, testable core: state machine and protocol logic runs against a fake
  OrangeLink in JVM tests; every firmware quirk in §2 has a conformance test.

**Non-goals**

- Firmware changes (explicitly excluded by Emily).
- Preserving the old module's internal APIs. Omnipod Eros keeps the legacy module (§9.5).
- Changing Medtronic pump protocol behavior (wire bytes, wake semantics, history decoding).

---

## 2. Ground truth: the peer's contract

Facts extracted from the OrangeLink firmware source (`/home/em/Dev/aps/Orangelink-Firmware`,
`subg_to_ble/**`) and from AAPS logs of live incidents (2026-07-02). The design treats each as
a hard constraint. References: `app_aps.c`, `app_ble.c`, `app_subg.c`, `ble_ips.c`, `rf69.c`.

### 2.1 BLE surface

| Item | Value | Consequence for v2 |
|---|---|---|
| Radio service | `0235733b-…-3845` | unchanged |
| Data characteristic `c842e849-…` | READ/WRITE, ≤150 B, **no notify** | responses must be *read back*; never trust push |
| Response-count char `6e6c7910-…` | READ/NOTIFY, 1 B, increments per queued response, wraps `0xFF→0x00`, **resets to 0 on reboot** | count is both the "response ready" doorbell and the **reboot detector** |
| Version char `30d99dc9-…` | constant `"ble_rfspy 2.0"` | identification only; not a session-liveness signal |
| Conn params requested | interval 50–100 ms, latency 0, **supervision timeout 5 s** | any ~5 s window in which link-layer traffic fails kills the link → assume frequent status-8 drops; keep radio commands short (§6.5) |
| MTU | default 23 unless negotiated | request larger MTU but never rely on it; commands ≤150 B regardless |

### 2.2 Command protocol (subg_rfspy 2.2 dialect)

- Frame: `[len][opcode][params…]`, `len = total−1`; malformed frames are **silently dropped**
  (`app_aps.c:664`) — a lost response is indistinguishable from a dropped command. Therefore
  every command needs a host-side deadline and a defined retry/uncertainty policy.
- Command queue depth is **2, effectively 1** (`app_aps.c:42`); overflow drops the command with
  only a firmware-side log. Therefore the host must never pipeline: **exactly one in-flight
  command per session**, enforced by construction (§6.3).
- Response codes: `0xDD` OK, `0xAA` radio-listen timeout, `0xBB` interrupted (BLE dropped
  mid-radio-op), `0x11` bad param, `0x22` unknown opcode.
- `UPDATE_REG` executes immediately (not queued); everything else via the 10 ms command loop.
- `CMD_RESET (0x07)` is **not implemented** — the host cannot soft-reset the device; the only
  resets are firmware WDT/fault reboots and physical power cycles.
- `GET_STATE` always answers `"OK"` — useless as health check beyond round-trip liveness.

### 2.3 Radio behavior

- `SEND_AND_LISTEN`: TX repeated `repeat+1` times at `repeatIntvl` ms, then RX until first
  terminator byte or `timeout` ms; optional firmware-side retry (`retryCnt`).
- Minimed mode strips a **trailing `0x00`** from outgoing packets (`app_aps.c:381`); encoded
  payloads must be padded so they never end in `0x00` (current stack already pads with `0x55`;
  v2 asserts this invariant at the framing layer).
- 4b6b decode failure returns **zero bytes**, not an error — an empty radio payload can mean
  "decode failed", not "pump silent". Classified as `GarbledResponse`, not `RadioTimeout` (§6.6).
- RSSI is reported in CC1110 format `(dBm+73)*2`; conversion kept from current code.

### 2.4 Reboot semantics (the critical path)

Firmware reboots on WDT starvation (32 s), hard fault, or brown-out. Observed live: reboot
mid-tune with GATT status-8 disconnect and boot buzzer; re-advertising begins immediately.

On reboot **all volatile radio state is lost** and reset to defaults: frequency → 916 MHz
default table, software encoding → NONE, preamble → 0, response count → 0, command queue →
empty. There is **no way to query** whether state was lost (`GET_STATE` is constant).

**Design rule R1 (unconditional re-init):** after *every* BLE (re)connect, run the full radio
session bring-up (§6.4) — version probe, encoding, preamble, frequency. It costs <1 s and
removes an entire class of "did it reboot?" guessing. Never attempt to reuse radio state
across a reconnect.

**Design rule R2 (reboot detection in-session):** a response-count regression (counter goes
backwards / resets) or `0xBB` completion is treated as evidence of reboot/interruption →
current command completes as `Interrupted`, session is torn down and re-established (R1).

### 2.5 Response-count read protocol

Correct per-command sequence (`ble_ips.c:449–556`, `app_ble.c:665–679`):

1. Subscribe to response-count notifications (once per connection, before first command).
2. Write command to data characteristic.
3. Await response-count notification (with deadline).
4. **Read** the data characteristic → this is the response payload.

Quirks the session layer must absorb: reading the data characteristic with no pending response
returns **stale** bytes (last value); notifications can batch under load; count wraps at 8 bits.
Mitigation: single in-flight command + drain-before-send (§6.3) makes "which response is this"
unambiguous by construction; wraparound handled as modular increment.

---

## 3. Failure model

Every fault class the stack must handle, with the incident evidence that motivates it and the
required behavior. "Detect / React / Recover" for each is the correctness spine of the design;
§8 maps each to state-machine transitions, §12 maps each to tests.

| # | Fault | Evidence | Required handling |
|---|---|---|---|
| F1 | Pump out of radio range / asleep | wake+tune fail, `0xAA` on all freqs (15:59 session) | classify as `PumpUnreachable`; keep BLE session; retry on a demand-driven schedule forever (G1) |
| F2 | OrangeLink BLE out of range / powered off | scan finds nothing, or supervision timeout | reconnect loop with backoff + rescan; no terminal error state |
| F3 | OrangeLink firmware reboot mid-session | status-8 drop + boot buzz at 16:34:46; response count resets | detect (R2), cancel in-flight ops ≤1 s, reconnect, full re-init (R1) |
| F4 | GATT connected but services stale/unconfigured | `resultCode=2` write failures for 12 min (16:35–16:46) | service discovery is part of the connect sequence itself, not a queued task; writes are impossible-by-construction before session bring-up completes |
| F5 | Radio op in flight when link dies | zombie tune ground 27 doomed attempts | structured cancellation: link-state change cancels the session scope; no polling of stale flags |
| F6 | Garbled radio responses (RF saturation, 4b6b failure) | `Unknown_Device` at RSSI −6…−58 while real commands succeed | distinguish `GarbledResponse` (pump present) from `RadioTimeout` (pump absent); garbled counts as *reachability evidence* for wake/tune |
| F7 | Android BLE stack pathologies (status 133, stuck GATT) | historical; `disconnect()+close()` on 133 in current code | bounded connect attempts → close + fresh `connectGatt`; escalation to adapter-level remedies is user-opt-in only |
| F8 | Phone BT adapter toggled / permissions revoked | existing `BluetoothReconnected` path | adapter-state listener feeds the same supervisor; no separate code path |
| F9 | Command delivered, response lost (link died in the gap) | possible per §2.2 silent drops | result is `Uncertain`, never `Failed`, for non-idempotent pump commands; surfaced to the Medtronic driver which already reconciles via pump history (bolus safety) |
| F10 | Process death / service restart | Android lifecycle | all durable state (last good frequency, encoding choice, MAC) in prefs; everything else reconstructed by R1 |

Explicit **anti-goals** derived from the incidents: no FIFO task executor shared between
long radio work and connection maintenance (killed F4); no boolean `isConnected` flags cached
outside the owner (stale-flag bug, hypothesis A2 confirmed in logs); no broadcast-intent chains
for state transitions (unsupervised, unordered, lossy).

---

## 4. Architecture overview

```
┌────────────────────────────────────────────────────────────────────┐
│  MedtronicPumpPlugin (AAPS Pump interface)      MedtronicFragment  │
│        │  isConnected()/connect()/…                  ▲ StateFlow   │
│        ▼                                             │             │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │ L3  PumpLinkSupervisor        one state machine, one owner   │ │
│  │     states: Idle/BleConnecting/SessionInit/Tuning/Ready/…    │ │
│  │     recovery ladder, demand tracking, rate limits            │ │
│  └──────────────┬───────────────────────────────────────────────┘ │
│                 │ suspend fns + Flow<LinkEvent>                    │
│  ┌──────────────▼───────────────────────────────────────────────┐ │
│  │ L2  MinimedRadio              wake / tune / sendAndListen    │ │
│  │     4b6b (Local) encode/decode, RSSI, garbled-vs-timeout     │ │
│  └──────────────┬───────────────────────────────────────────────┘ │
│                 │ RfspyCommand → RfspyResult (typed)               │
│  ┌──────────────▼───────────────────────────────────────────────┐ │
│  │ L1  RfspySession              one session per BLE connection │ │
│  │     framing, single in-flight cmd, count protocol, bring-up  │ │
│  │     reboot detection, session epoch                          │ │
│  └──────────────┬───────────────────────────────────────────────┘ │
│                 │ suspend write/read/subscribe + Flow<BleEvent>    │
│  ┌──────────────▼───────────────────────────────────────────────┐ │
│  │ L0  BleLink                   owns BluetoothGatt exclusively │ │
│  │     scan/connect/discover/MTU/subscribe as one atomic        │ │
│  │     bring-up; serialized GATT ops; reconnect events          │ │
│  └──────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────┘
```

**Ownership and threading model**

- One `PumpLinkScope`: `SupervisorJob` + a **single-threaded dispatcher** for all state-machine
  and session logic. Android GATT callbacks and RxBus arrivals are *events posted into* this
  dispatcher; no logic runs on binder threads. Blocking Android calls (none expected — GATT is
  callback-based) would go to `Dispatchers.IO`.
- Layer N owns layer N−1's lifetime: the supervisor creates a `BleLink` connection, gets an
  `RfspySession` from its bring-up, hands a `MinimedRadio` view of it to command execution.
  When L0 reports the link died, the supervisor **cancels the session scope**: every suspended
  radio call throws `CancellationException` within one dispatch — this is the mechanism that
  makes F5 structurally impossible.
- The service (`RileyLinkMedtronicService` successor) exists only to own the scope for
  process-lifecycle purposes and to satisfy AAPS's foreground-service expectations.

**Everything is either (a) an immutable event, (b) state owned by exactly one coroutine, or
(c) a suspend call into a lower layer.** There are no shared boolean flags, no semaphores
crossing layers, and no `SystemClock.sleep`.

---

## 5. L0 — BleLink

Responsibility: exclusive ownership of `BluetoothGatt` for the OrangeLink; deliver an
*operational* BLE link (connected, services discovered, MTU set, notifications armed) or a
typed failure. Pump-agnostic; knows GATT, not rfspy.

```kotlin
interface BleLink {
    /** Cold: each collect attempt owns one physical connection lifecycle. */
    suspend fun connect(address: String, timeout: Duration): BleConnection
    fun scan(filterAddress: String?, timeout: Duration): Flow<ScanHit>
}

interface BleConnection {                        // valid until [events] emits Closed
    val events: SharedFlow<BleEvent>             // Closed(cause), NotificationReceived(uuid, bytes)
    suspend fun write(char: CharId, bytes: ByteArray, timeout: Duration)
    suspend fun read(char: CharId, timeout: Duration): ByteArray
    suspend fun subscribe(char: CharId, timeout: Duration)
    suspend fun close()                          // idempotent
}
```

Design decisions:

- **Atomic bring-up.** `connect()` returns only after: GATT connected → `discoverServices()`
  complete → RileyLink service UUIDs verified → MTU requested → response-count notification
  subscribed. A connection that hasn't finished bring-up never escapes L0. This deletes fault
  class F4: there is no state in which upper layers can write to an undiscovered GATT.
- **Scan-then-connect** for OrangeLink (as today, `OrangeUseScanning`); direct
  `connectGatt(autoConnect=false)` otherwise. v2 uses **directed connects with its own retry
  loop** rather than `autoConnect=true`: autoConnect hides reconnection from the supervisor,
  which is exactly the invisibility that produced the current design's blind spots. The
  supervisor decides when to reconnect and with what urgency.
- **One outstanding GATT operation**, enforced by a per-connection `Mutex` inside L0 (Android
  requirement). Operations carry deadlines; a deadline expiry closes the connection (a GATT
  that stops answering is dead in practice — see F7).
- **Status-133 / stuck-GATT policy:** on connect failure with status 133 or bring-up deadline
  expiry: `close()`, wait `min(2^n × 500 ms, 30 s)` with jitter, fresh `connectGatt`. After 3
  consecutive failures, insert a scan cycle (device may have changed address/state after DFU).
- Every event and transition logged with cause and monotonic timestamps (§11).

## 6. L1 — RfspySession

Responsibility: turn an operational `BleConnection` into a live *subg_rfspy session*:
framing, the response-count read protocol, single in-flight command, session bring-up per R1,
reboot detection per R2.

### 6.1 Interface

```kotlin
class RfspySession internal constructor(...) {
    val epoch: Int                                   // increments per bring-up; tags all results
    suspend fun execute(cmd: RfspyCommand, deadline: Duration): RfspyResult
    suspend fun close()
}

sealed interface RfspyResult {
    data class Ok(val payload: ByteArray) : RfspyResult              // 0xDD …
    data class RadioTimeout(val raw: ByteArray) : RfspyResult        // 0xAA
    data class Interrupted(val raw: ByteArray) : RfspyResult         // 0xBB → session teardown
    data class FirmwareError(val code: Int) : RfspyResult            // 0x11 / 0x22
    data class Uncertain(val cause: UncertainCause) : RfspyResult    // delivered?, response lost
    // LinkLost is NOT a result: it is CancellationException from scope cancellation
}
```

### 6.2 Bring-up (runs on every connect; rule R1)

1. Read response count (baseline), read data characteristic once (discard — flushes stale value).
2. `GET_VER` → must answer `subg_rfspy 2.x`; anything else → `IncompatibleFirmware` (terminal
   until user action; the only intentionally-terminal state in the design).
3. `SET_SW_ENCODING(NONE)` — Medtronic uses host-side 4b6b ("Local"); explicit, never assumed.
4. `SET_PREAMBLE(0x0000)` (Medtronic).
5. Frequency registers (`UPDATE_REG 0x09/0x0A/0x0B`) from last-good frequency pref, else region
   default.
6. Optionally `GET_STATISTICS` for the observability journal.

Total < 1 s. Only after bring-up does the supervisor consider the link `RadioReady`.

### 6.3 Command execution protocol

Single in-flight enforced by a `Mutex` owned by the session (upper layers cannot bypass: the
only way to talk to the device is `execute`). Per command:

```
lock →
  expectedCount = lastCount + 1 (mod 256)
  write(data, frame)                       // deadline: 2 s
  await count notification                 // deadline: cmdDeadline (from caller)
  read(data)                               // deadline: 2 s
  classify byte[0] → RfspyResult
→ unlock
```

- Count observed ≠ expected (regression or skip): evidence of reboot or desync → complete the
  in-flight command as `Interrupted`, close session (supervisor re-runs bring-up). No attempt
  to resynchronize a live session — reconnect + bring-up is cheap and *known correct* (R1).
- Notification deadline expiry: if the command was non-mutating (`GET_VER`, `GET_STATE`,
  `UPDATE_REG` readback...) → retry once within the session; if potentially-mutating at pump
  level (any `SEND_AND_LISTEN` carrying a pump command) → return `Uncertain` (F9). L1 does not
  know pump semantics, so the mutating/non-mutating bit is carried on `RfspyCommand`.
- `0xBB` → `Interrupted` → session close (firmware told us the radio op was cut by a BLE event
  we may not have seen yet).

### 6.4 Session epoch

Every result is tagged with `epoch`. The supervisor and L2 discard any late completion whose
epoch ≠ current epoch (defense-in-depth; structured cancellation should make this unreachable,
and the journal logs if it ever fires — see §11).

### 6.5 Command duration budget

Firmware requests a 5 s supervision timeout and services radio ops in busy loops; observed
reboots correlate with long radio operations. v2 therefore bounds any single rfspy command to
**≤ 10 s wall time** and prefers host-side iteration over firmware-side long listens:

- Wake: instead of one `SEND_AND_LISTEN(repeat=200, listen=25 s)` (current stack), issue up to
  5 × `SEND_AND_LISTEN(repeat=200, listen=4.5 s)` — same RF energy pattern and total listen
  budget, but each iteration is a cancellation point, a health check, and a bounded stress
  window for the firmware. (Timings preserved from current stack totals; exact split tunable
  in §15 Q3.)
- Tune trials stay short (1.25 s listen) as today.

### 6.6 Response classification (fault F6)

`RadioTimeout` (0xAA) = pump genuinely silent. A `0xDD` radio packet whose decoded payload is
empty or fails validation = `GarbledResponse` at L2 — the pump *is there* (something keyed the
carrier at matching RSSI). Wake and tune treat garbled as reachability success; only
command-level protocol needs valid payloads. The 2026-07-02 logs show this distinction is the
difference between "unreachable, give up" and "reachable, retry the read".

## 7. L2 — MinimedRadio

Responsibility: Medtronic-specific radio procedures on top of `RfspySession`. This is where
the preserved protocol layer plugs in.

- `wake(serial)` — §6.5 pattern; success = any radio packet (valid or garbled) heard.
- `tune(region, lastGood)` — cancellable scan: try `lastGood` first with 3 quick trials
  (fast path: skip full scan if RSSI ≥ threshold), else full region scan as today (RSSI-ranked,
  −99 penalty for misses), persist result. Fail-fast: 3 consecutive session-level failures
  (`Interrupted`/`Uncertain`) abort the tune — those are link problems, not frequency problems.
- `sendAndListen(pumpMessage, timeouts…)` — 4b6b encode (+ pad so encoded frame never ends in
  `0x00`, §2.3), execute, decode, validate; retry budgets **unchanged** from
  `MedtronicCommunicationManager` (3 tries, +2 s per retry) so pump-visible behavior is
  identical to the proven stack.
- Exposes exactly the call surface `MedtronicCommunicationManager` needs; that class is edited
  only to (a) accept suspend semantics, (b) delete its embedded tune/wake-scheduling side
  channels (`serviceTaskExecutor.startTask(WakeAndTuneTask)` from inside `sendAndListen` — the
  layering violation that let the radio layer spawn orchestration work), (c) replace
  `RileyLinkCommunicationException` control flow with typed results.

## 8. L3 — PumpLinkSupervisor

The single owner of connection lifecycle. One coroutine, one state variable, all inputs are
events. **No state is terminal except `IncompatibleFirmware` and `NotConfigured`.**

### 8.1 States

```
NotConfigured            no MAC / invalid serial (exits when prefs change)
Idle                     no demand; BLE disconnected by policy
BleConnecting(attempt)   L0 connect in progress
SessionInit              rfspy bring-up (R1)
Tuning                   wake+tune in progress (cancellable child job)
Ready(freq, rssi)        pump commands may execute
PumpUnreachable(since)   BLE+session fine, pump silent (F1)
LinkDown(since, cause)   BLE lost; reconnect loop active (F2/F3/F7)
IncompatibleFirmware     version probe failed (only user can fix)
```

### 8.2 Transition table (excerpt; full table is the implementation checklist)

| State | Event | Next | Action |
|---|---|---|---|
| Idle | `DemandArrived` | BleConnecting | L0 connect |
| BleConnecting | `BringUpOk` | SessionInit | run §6.2 |
| BleConnecting | `BringUpFailed(n)` | BleConnecting(n+1) / LinkDown | backoff per §5; journal |
| SessionInit | `SessionOk` | Tuning or Ready | tune iff no valid recent frequency |
| SessionInit | `IncompatibleFw` | IncompatibleFirmware | alert |
| Tuning | `TuneOk(freq)` | Ready | persist freq |
| Tuning | `TuneFailed(pumpSilent)` | PumpUnreachable | schedule retry (§8.3) |
| Ready | `SessionLost` (0xBB, count desync, BLE Closed) | LinkDown | cancel session scope ≤1 s |
| Ready | `PumpCmdsFailing(k)` | PumpUnreachable | k consecutive `RadioTimeout` on real commands |
| PumpUnreachable | `RetryTimer ∨ DemandArrived` | Tuning | rate-limited (§8.3) |
| LinkDown | `ReconnectOk` | SessionInit | **always** full bring-up (R1) |
| any | `BtAdapterOff` | LinkDown(adapterOff) | wait for adapter-on event (F8) |
| any | `PrefsChanged(mac/region)` | Idle → BleConnecting | clean restart of lifecycle |

Liveness argument (G1): every non-terminal state has at least one exit arc driven by a timer,
a link event, or demand — verified exhaustively by state-machine tests (§12.2). The two
terminal states require information only the user has (hardware/config).

### 8.3 Recovery ladder and rate limits

Escalation on repeated failure, each rung rate-limited and journaled:

1. Command retry within session (L2 budgets, unchanged from legacy).
2. Session re-init without BLE reconnect (rare; only for count desync without disconnect).
3. BLE reconnect + bring-up (backoff 0.5 s → 30 s, jittered; reset on success).
4. Scan cycle (device address may be gone/changed) — every 3rd failed reconnect.
5. Surface to user: AAPS notification + `EventRileyLinkDeviceStatusChange` after
   `PumpUnreachable`/`LinkDown` persists > 10 min (aligns with existing
   `ALLOWED_PUMP_UNREACHABLE` alarm plumbing).

Pump-side retries (wake/tune in `PumpUnreachable`): every 5 min when demand exists (KeepAlive
cadence supplies this naturally), plus immediately on new demand, capped at one wake+tune per
3 min — same rate limit as the tactical fix currently proven on `emily.connectivity`.

**Deliberately absent:** the legacy BT-adapter-toggle watchdog. Toggling the phone's adapter
punishes every BLE device the user owns for one stuck GATT; §5's close-and-reconnect policy
addresses the same fault class (F7) surgically. If field data shows Android stacks that only
an adapter bounce can fix, it returns as rung 4.5 behind a preference, off by default.

### 8.4 Demand model

`connect(reason)` from AAPS increments a demand refcount with a 90 s lease (renewed by queue
activity); `disconnect(reason)` releases. Policy for Minimed (proprietary radio, no pairing
cost): **stay connected to the OrangeLink whenever configured** — BLE connection is cheap,
the 5 s supervision timeout makes "connect on demand" slower than "already connected", and a
held link is what lets the supervisor *see* firmware reboots as they happen. Demand only
gates radio activity (wake/tune retries), not BLE presence. `Idle` is reached solely when the
plugin is disabled or unconfigured.

## 9. AAPS integration

### 9.1 Pump interface mapping (QueueWorker contract)

| AAPS call | v2 implementation |
|---|---|
| `isConnected()` | `state is Ready` |
| `isConnecting()` | `state is BleConnecting ∨ SessionInit ∨ Tuning` (genuinely in progress only — never true while parked in a failure state; QueueWorker then drives `connect()`) |
| `isHandshakeInProgress()` | `false` (SessionInit+Tuning reported via isConnecting; simpler for QueueWorker's 120 s budget) |
| `connect(reason)` | post `DemandArrived` (idempotent, never blocks) |
| `disconnect(reason)` | release demand lease |
| `stopConnecting()` | cancel current attempt → state-appropriate parked state |
| `waitForDisconnectionInSeconds()` | 0 (we never drop BLE between commands; §8.4) |

The 120 s QueueWorker ceiling: BleConnect+SessionInit ≈ 5 s and wake+tune ≤ ~95 s worst case,
so a cold `PumpUnreachable`→`Ready` recovery fits one queue window when the pump is present;
when it isn't, `isConnecting()` goes false promptly and the queue times out cleanly instead of
spinning (the legacy stack's forever-"connecting" lie).

### 9.2 Command execution API for the Medtronic driver

```kotlin
suspend fun <T> withPump(
    priority: PumpCmdPriority,          // BOLUS > TBR > STATUS/HISTORY
    description: String,
    block: suspend (MinimedRadio) -> T
): PumpCmdOutcome<T>                    // Success | PumpUnreachable | LinkDown | Uncertain
```

Single-flight per priority class with priority preemption *between* (never within) pump
commands: a queued bolus preempts a pending history fetch but never interrupts an in-flight
frame exchange (pump protocol is stateful mid-command). `Uncertain` (F9) propagates untouched
to `MedtronicPumpPlugin`, whose existing history-reconciliation path is the arbiter — v2 must
never convert `Uncertain` into `Failed` (bolus double-delivery risk) or `Success`.

### 9.3 UI and events

`StateFlow<PumpLinkState>` is the source of truth. A thin adapter maps state changes onto the
existing RxBus events (`EventRileyLinkDeviceStatusChange`, `EventPumpStatusChanged`) and onto
legacy `RileyLinkServiceState` values for `MedtronicFragment`/`RileyLinkStatusActivity`, so no
UI rewrite is required in phase one of the landing. The adapter is one-directional: UI never
mutates stack state except through the plugin interface (the "Wake and Tune" custom action
becomes `post(DemandArrived(tuneRequested=true))`).

### 9.4 Preferences

Reused as-is: `MacAddress`, `Encoding`, `LastGoodDeviceFrequency`,
`LastGoodDeviceCommunicationTime`, pump region/serial, `OrangeUseScanning`,
`ShowReportedBatteryLevel`. New: none required. Removed from meaning: BT-watchdog pref
(§8.3), `pref_orange_use_scanning` default flips to true when an OrangeLink is detected
(scan-first is its reliable path, per firmware analysis).

### 9.5 Omnipod Eros

Eros shares today's `pump/rileylink` module. Decision: **v2 is a new module
(`pump/rileylink2`) used by Medtronic only**; Eros keeps the legacy module untouched. L0/L1
are deliberately pump-agnostic so Eros can port later (it needs Manchester encoding, 433 MHz
register tables, `preambleExtend`, and packet-length register support — all representable in
the §6 command model). Both modules coexist in the APK; they never share a device, so there is
no runtime conflict for a given user.

## 10. Correctness rules (implementation invariants)

- I1. `BluetoothGatt` is referenced by exactly one class (L0). Grep-enforceable.
- I2. All state transitions occur on the single PumpLink dispatcher; states are `val`s in one
  `MutableStateFlow`, mutated in one function (`transition(event)`), which is the only place
  allowed to log a transition.
- I3. No layer exposes `null` for a fallible operation; sealed results only. `!!` forbidden in
  the module.
- I4. No `SystemClock.sleep`, no raw `Thread`, no semaphores. Delays are `delay()` under the
  scope so cancellation reaches them; time comes from an injected `Clock` (testability, and
  `Date.now`-style nondeterminism kept out of the state machine).
- I5. Every deadline is explicit at the call site; no operation may block unboundedly.
- I6. Cancellation is never swallowed: `catch (e: CancellationException) { throw e }` pattern
  enforced by lint where broad catches exist.
- I7. A session result carries its epoch; consumers assert epoch currency (§6.4) and journal
  violations rather than crashing.
- I8. Radio frames destined for Minimed are validated at the framing layer: length ≤ 107,
  final byte ≠ 0x00 (§2.3), before entering L1.
- I9. Mutating pump commands must declare themselves (`RfspyCommand.mutating`); the `Uncertain`
  path is mandatory for them (F9). Non-declared commands are treated as mutating (fail safe).

## 11. Observability

- **Transition journal**: ring buffer (last 500) of `(mono time, state, event, cause, epoch)`
  — the productized version of the RECONN logging that diagnosed the 2026-07-02 incidents;
  dumped into the log on every error-class transition and viewable in the status UI.
- **Counters** (per session and lifetime): reconnects by cause (status code), firmware reboots
  detected (count resets), bring-up duration, tune duration/outcome/frequency, command
  outcomes by type, `Uncertain` occurrences (each one individually alarmed in log — they
  should be rare), supervision-window violations (commands > 10 s).
- Log tags: keep `PUMPBTCOMM`/`PUMPCOMM` so existing log tooling and community familiarity
  survive; transitions logged at INFO with a stable grep-able prefix `LINKV2:`.

## 12. Testing strategy

### 12.1 FakeOrangeLink (JVM)

Implements `BleLink`/`BleConnection` against an in-memory model of the firmware derived from
§2 — including the quirks: count wraparound, stale data reads, silent malformed-frame drops,
queue-depth-1 overflow, trailing-0x00 strip, reboot (count reset + state loss + disconnect),
supervision-timeout drops, notification batching. Each §2 quirk row = at least one test.

### 12.2 State-machine conformance

- Exhaustive: for every (state, event) pair, assert the transition table (§8.2) — including
  the "no dead ends" liveness property: from every non-terminal state, injected event
  sequences representing F1–F10 eventually reach `Ready` when the fake allows it.
- Property-based fault fuzzing: random interleavings of {BLE drop, reboot, garble, timeout,
  demand arrival, prefs change} against the fake; invariant assertions: never two in-flight
  rfspy commands; never a write before bring-up; every `Uncertain` is surfaced not retried
  silently for mutating commands; recovery time from fault injection to `Ready` bounded.

### 12.3 Incident replays

The 2026-07-02 log traces become scripted scenarios: (a) start with pump out of range →
bring in range → assert `Ready` within one retry period without restart; (b) firmware reboot
mid-tune → assert cancellation ≤1 s, re-init, `Ready` ≤30 s; (c) 12-minute-zombie-tune
scenario → assert total outage <45 s in v2.

### 12.4 On-device soak

The adb log-pull workflow used today, formalized: soak script asserts journal properties
(no transition loops, no `Uncertain` bursts, reconnect latencies) from device logs over
multi-day wear with deliberate range-loss episodes. Exit criteria for cutover: 7 days of soak
with zero manual interventions and zero unexplained `LinkDown>10min` episodes.

## 13. Landing plan

Not incremental in design, pragmatic in cutover:

1. New module `pump/rileylink2` (L0–L3, adapter) + JVM test suite. No changes to shipping code.
2. `MedtronicCommunicationManager` call-boundary adaptation behind the existing class name
   (suspend wrappers; protocol bytes and budgets untouched — reviewed by diffing radio frames
   in FakeOrangeLink tests against frames recorded from the legacy stack).
3. `MedtronicPumpPlugin` switches to the v2 adapter; legacy `pump/rileylink` remains in-tree
   for Eros only. The tactical fixes from `emily.connectivity` stay on the legacy module (they
   are Eros-relevant too).
4. Soak per §12.4 on Emily's hardware; cutover is a config-less code switch (the plugin binds
   v2), rollback is a revert.
5. Post-cutover deletions: Medtronic-side task classes (`WakeAndTuneTask`,
   `InitializePumpManagerTask`, `DiscoverGattServicesTask` usage), broadcast-intent lifecycle
   plumbing, `RileyLinkServiceData` mutable-state sharing for Medtronic paths.

## 14. Firmware track (protocol v2)

The OrangeLink firmware **is** being modified in parallel (decision 2026-07-02); the work is
specified in `/home/em/Dev/aps/Orangelink-Firmware/docs/HANDOFF-protocol-v2.md` and its
authoritative output will be that repo's `docs/PROTOCOL-V2.md`. Impact on this design:

- Nothing architectural changes. The firmware work removes fault *frequency* (cooperative
  radio loops kill the reboot-under-load class F3-as-routine) and simplifies L1 (pushed,
  sequence-numbered responses; explicit BUSY instead of silent drop; CANCEL resolves Q3 in
  favor of long listens).
- L1 gains a `ProtocolVariant` selected at bring-up from the `GET_VER` string
  (`subg_rfspy 2.2` → stock; `2.2-ol1`+ → v2 features per the spec). **The stock variant is
  implemented first and remains fully supported** — v2 must work against an unmodified or
  replacement OrangeLink forever.
- `FakeOrangeLink` grows a second personality implementing PROTOCOL-V2.md; every v2 feature
  lands with conformance tests against both personalities (v2 feature present vs absent).
- Boot-counter/GET_STATE-v2 upgrade rule R1's "assume nothing" to "verify" without replacing
  it: unconditional re-init stays even on v2 firmware.

## 15. Open questions

- Q1. Keep-BLE-always-connected (§8.4) trades OrangeLink battery for reliability/visibility.
  Firmware requests 50–100 ms intervals (relatively power-hungry). Acceptable? (Suspect yes —
  device is rechargeable and worn adjacent; measure in soak.)
- Q2. `isHandshakeInProgress` mapping (§9.1): report Tuning as handshake instead, so the UI
  shows a distinct phase? Cosmetic; decide during adapter work.
- Q3. Wake split (§6.5): 5×4.5 s vs legacy 1×25 s listen — validate on-pump that split listens
  don't reduce wake success (pump wake window semantics). If they do, fall back to long listen
  but add a parallel BLE keepalive read every 2 s during it.
- Q4. Should v2 opportunistically read `GET_STATISTICS` each session for the journal (extra
  ~50 ms per bring-up)? Default yes unless soak shows firmware sensitivity.
- Q5. Port Eros to v2 later, or leave permanently on legacy? (Out of scope; L0/L1 kept
  pump-agnostic to preserve the option.)
