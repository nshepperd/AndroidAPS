# Host-side plan: OrangeLink protocol v2 in `pump/rileylink2`

Status: IN PROGRESS (2026-07-03). Phase A landed (MTU, RESPONSE_V2/DIS chars, fake
interrupt semantics + queueDepth, DIS boot-counter read). Phase B landed (V2Codec,
v2 exchange in RfspySession, Rejected result, MTU fallback, BUSY tripwire, GET_STATISTICS,
fake -ol1 personality + conformance tests) with two deviations from this plan:

- **Stale-seq notifications are IGNORED, not close-on-violation** (D-list said close).
  A completion arriving after its command's deadline expired is expected on a live
  session (the next command's enqueue interrupts the old op → stale `[oldSeq][0xBB]`),
  and Phase C CANCEL drains need the same tolerance. A completion arriving BEFORE its
  ack still closes (guaranteed order violated). Genuine reboot evidence on v2 is a link
  drop, not seq weirdness.
- **The trailing-0x00 strip landed in Phase B** (V2Codec.envelope, opcodes 4/5), not
  Phase D — v2-enveloped SEND_AND_LISTEN ships with Phase B, so RF identity had to too.
  Phase D reduces to the wake strategy.

C–F pending. Wire contract:
`/home/em/Dev/aps/Orangelink-Firmware/docs/PROTOCOL-V2.md` (as-implemented, firmware
`subg_rfspy 2.2-ol1`). Companions: `docs/rileylink-v2-design.md` (§14 sketched this work),
`docs/rileylink-v2-handoff.md` (backlog #4).

## 0. Scope and invariants

- **Stock stays fully supported forever** (design §14): the protocol variant is selected
  per session at bring-up from `GET_VER`; nothing stock-path changes behavior.
- **RF bytes stay byte-identical to legacy** in both variants (corrected I8: identical
  *after* the firmware's stock-path trailing-`0x00` strip). This is the one place v2 can
  silently diverge — see §3.4.
- **Safety posture**: mutating-command uncertainty rules (I9) only ever get *more*
  conservative in this change. Tightenings enabled by v2 (immediate NACKs are
  definitive not-executed) land as watch-listed follow-ups, not in the first cut.
- Conformance philosophy (B1 lesson): every firmware behavior the host relies on gets a
  `FakeOrangeLink` behavior modeled **from firmware source** (`app_aps.c`, `ble_ips.c`)
  plus a test. When the spec and convenience disagree, read the C.

## 1. What v2 changes for the host (deltas that drive the design)

| Stock path | v2 path |
|---|---|
| write → count notification → data read-back | write envelope → `[seq][0xE0]` ACCEPTED → `[seq][status][payload]` completion, all via RESPONSE_V2 notifications; no read-back, no count bump |
| silent drop on overflow/malformed/unknown | explicit `0xE1` BUSY / `0xE2` MTU / `[seq][0x11]` malformed / `0x22` unknown inner |
| single shared data buffer (stale reads, overwrite) | per-command notifications; buffer quirks gone |
| interruption (`0xBB`) = evidence of desync → close session | `0xBB` is an *expected* CANCEL outcome; session survives a deliberate cancel |
| one deadline covering queue+radio+doorbell | two-phase: short ACCEPT deadline (link health) + radio-sized completion deadline |
| reboot detection via count desync (R2) | no count on v2 → detection via link drop, CCCD loss, seq protocol violations, DIS boot counter |
| trailing `0x00` stripped by firmware before TX | envelope length authoritative, **no strip** |

CANCEL (`0x0F`) is the reliability prize: abort an in-flight listen in <500 ms without
tearing down the session — enables cancellable long-listen wake (design §15 Q3
alternative) and cooperative shutdown mid-radio-op.

## 2. Phasing (each phase lands with fake behavior + tests, independently committable)

### Phase A — L0 groundwork (no protocol change; also serves Focus-1 item 3)

1. **MTU negotiation in bring-up** (`AndroidBleLink.bringUp`): `requestMtu(247)` after
   service discovery (the §5 contract comment already promises this; the code never did
   it). New `BleConnection.mtu: Int` (post-bring-up value; 23 if negotiation fails —
   failure is non-fatal, it just forecloses v2). Handle `onMtuChanged`. Note: Android 14+
   coerces the first request to 517 — fine, we only need ≥ 131.
2. **`CharId.RADIO_RESPONSE_V2`** + UUID `02355632-99c5-4197-b856-69219c2a3845` in
   `GattUuids`. `onCharacteristicChanged` routing is automatic via `GattUuids.of`.
   L1 subscribes on every session open (rule R1 — never trust a restored CCCD).
3. **Fake fidelity debt found while reading the spec** (stock personality, applies to
   both): the fake does not model *command-interrupts-in-flight-radio-op* (`0xBB`), which
   PROTOCOL-V2 documents as original stock behavior, bench-verified 2026-07-03. Model it.
4. **Queue depth is a fake parameter** (resolved Q-a: -ol1 increased the queue to 8 for
   BOTH paths, vendor firmware stays depth 1+processing). The fake takes `queueDepth`
   (1 = vendor, 8 = -ol1); the STOCK-protocol test suite runs at both depths so the
   stock path is proven against both firmwares (see §Phase E matrix item 12).
5. **DIS boot-counter/reset-reason read at bring-up** (resolved Q-c): one non-fatal
   GATT read of the 0x180A Software Revision String (`boot:<n> rr:0x<resetreas>`),
   parsed, logged, journaled. Upgrades R1/R2 reboot inference to measurement — and on
   v2 it matters more, since the response-count desync signal doesn't exist there.
6. (Sanctioned, adjacent, but a separate commit: TRANSPORT_LE on `connectGatt`, adapter
   state receiver → `CloseCause.AdapterOff`. From the F8 bullet, not this plan.)

### Phase B — v2 codec + session transport

1. **`V2Codec`** (new file, pure functions, golden-frame tests):
   - `envelope(seq, innerBody): ByteArray` → `[0x10][seq][opcode][params…]` handed to the
     existing `RfspySession.frame()` for the outer length byte;
   - parse notification → `Ack(seq, code)` (`0xE0/0xE1/0xE2/0x11`) vs
     `Completion(seq, status, payload)`. NB `0x11` is both an immediate-NACK code and a
     completion status; disambiguate by protocol position (first notification for a seq is
     always the ack), not by value.
2. **Exchange strategy inside `RfspySession`**: extract the write→await→classify body of
   `executeLocked` into an internal `Exchange` interface with `StockExchange` (current
   code, untouched semantics) and `V2Exchange`. `classify()` payload semantics are shared
   (v2 completion payloads are byte-identical). Selected once at `open()` after the
   version probe; the probe itself always runs stock (chicken-and-egg).
3. **`V2Exchange` semantics**:
   - `seq`: per-session counter, `(n+1) and 0xFF`, one in flight → correlation trivial.
     Notifications with an unexpected seq: consumed silently if they are the drained
     `0xBB` of a command we cancelled (Phase C); otherwise **protocol violation → close
     session** (R2 analog — never rationalize a live session that is talking garbage).
   - Two-phase await: `acceptDeadline` (new `SessionConfig` field, ~3 s — pure BLE round
     trip) then the caller's `responseDeadline` for completion.
   - Result mapping: `0xE0` → keep waiting; completion `0xDD/0xAA/0x11/0x22` → existing
     `Ok/RadioTimeout/FirmwareError`; completion `0xBB` unsolicited → `Interrupted` +
     close (same rule as stock); `0xE1/0xE2/[seq][0x11]` → **new
     `RfspyResult.Rejected(code, epoch)`** — the command was *definitively not executed*
     (spec: discarded, no completion follows). L2 maps `Rejected` to `LinkTrouble` for
     now (each one is a host bug or config bug: BUSY can't happen with the single-flight
     mutex, MTU was checked at bring-up). Safe-retry-on-Rejected for mutating commands is
     the I9 tightening enabled here — **follow-up, not first cut**. Repeated BUSY
     (resolved Q-b): log loudly, and after 3 consecutive close the session and let the
     supervisor reconnect (R2 posture — a session behaving impossibly is not reasoned
     with).
   - No ACCEPTED within `acceptDeadline`, link still open: spec-wise this means
     not-executed (acks come from BLE event context; a live link delivers notifications
     reliably). First cut stays conservative: `Uncertain(NO_RESPONSE_NOTIFICATION)` for
     mutating, in-session retry for non-mutating, exactly like stock. Watch item: soak
     evidence may later upgrade this to `Rejected`.
   - v2 completions don't bump the stock count: `lastCount` is simply not consulted on
     the v2 path (but the count subscription stays — reboot still resets it and battery
     or diagnostic stock commands remain legal).
4. **Bring-up sequencing** (rule R1): stock version probe → variant `V2_OL1` detected →
   `conn.subscribe(RADIO_RESPONSE_V2)` → check `conn.mtu ≥ 131`. If MTU insufficient:
   **fall back to STOCK for the whole session** with a loud `LINKV2:` log + journal
   entry (spec's per-command `0xE2` is the firmware's guard; ours is at bring-up).
   Remaining bring-up steps (encoding, preamble, FREQ2/1/0) run **through the v2 path**
   so a broken v2 stack fails the session at bring-up, not mid-bolus.
5. **`GET_STATISTICS` per bring-up** (resolved Q-d): issued through the v2 envelope on
   V2_OL1 sessions — doubles as a per-session v2 smoke test with a non-trivial payload.
   Non-fatal (observability, never a bring-up gate); journaled as raw hex initially,
   parsing deferred until something needs it.

### Phase C — CANCEL + cooperative cancellation

Constraints locked by the Phase B review:
- Gate CANCEL on `session.v2Active`, NOT `protocolVariant` — an MTU-fallback session never
  subscribed RESPONSE_V2, so an enveloped CANCEL would be silently dropped as an unknown
  stock opcode and the recovery path itself would hang.
- `classifyV2Completion` currently closes on ANY own-seq `0xBB` (single-flight violated);
  Phase C must consume the `0xBB` of its own CANCEL before it reaches that classifier.
- The fake does not model CANCEL yet (bare or enveloped; enveloped currently completes
  0x22 like any unknown inner opcode) — model BOTH paths from `app_aps.c` incl. the
  per-path 0xDD ordering (firmware: v2 acks 0xDD from BLE event context first;
  stock emits 0xDD last through the single data buffer).
- Candidate refactor while touching the exchange: session-lifetime RESPONSE_V2 collector
  instead of the per-command collector+channel (efficiency note from review).

1. `RfspyCommand.Cancel` (opcode `0x0F`, `mutating = false`, empty params).
2. `RfspySession.cancelInFlight()` (v2 sessions only): sends enveloped CANCEL with its
   own seq **outside** `commandMutex` (that's the point — the mutex holder is the one
   stuck). Expect `[seq][0xDD]` immediately; the aborted command's `[seq'][0xBB]` then
   flows to its own waiter, which reports: non-mutating → retryable failure; mutating →
   `Uncertain` (TX may have already keyed — unchanged I9 rule). Idempotent per spec.
3. Wire into structured cancellation: if `execute()`'s coroutine is cancelled after
   ACCEPTED but before completion, a `NonCancellable` best-effort CANCEL frees the
   firmware radio (<500 ms) and **the session stays open** — today the equivalent
   situation wedges the firmware op and the next command's `0xBB` kills the session.
   This is the host half of firmware WI-1 (keep radio ops cancellable/short).
4. Ordering quirk kept out of scope: stock-path bare CANCEL (0xDD-last, shared-buffer
   overwrite) — we never send CANCEL on stock sessions; the fake still models both
   orderings per spec so the conformance suite documents the difference.

### Phase D — L2: RF byte identity + long-listen wake option

1. **Trailing `0x00`** (the one silent-RF-divergence trap): keep
   `Minimed4b6b.encodeForTx` byte-identical (golden tests untouched); `V2Exchange`
   strips one trailing `0x00` from the packet field of inner opcodes 4/5 before
   enveloping — mirroring the firmware's stock-path strip at the same conceptual layer,
   documented against `app_aps.c:381`. Locked by the cross-personality test in §3.4.
2. **Cancellable long-listen wake** (Q3 alternative): `MinimedRadio.wake` grows a
   strategy — `SPLIT_BURST` (current 5×(200-repeat + 4.5 s), default) vs `LONG_LISTEN`
   (legacy-shaped 1×25 s listen, only offered when the session is `V2_OL1`, safe now
   because CANCEL + Phase C make it abortable). Selection via `PumpLinkConfig`
   (default SPLIT_BURST). Purpose: bench A/B for Q3 — if split listens hurt wake
   success on the real pump, the fallback no longer costs cancellability.
3. `MinimedRadio` needs the variant: expose `RfspySession.protocolVariant` (already
   public) and gate in `wake`. No other L2/L3 changes — `withPump`, the supervisor
   ladder, and all deadlines are untouched (v2 completion arrives *faster* than
   read-back; existing `BLE_LATENCY_MARGIN_MS` stays as slack, shrink later if ever).

### Phase E — FakeOrangeLink v2 personality + conformance matrix

Second personality (`versionString = "subg_rfspy 2.2-ol1"`), modeled from firmware
source, not from our host code (B1 rule). Behaviors:

- RESPONSE_V2 characteristic with real CCCD state: `subscribe()` tracked per-char;
  **unsubscribed `0x10` is silently dropped** (treated as unknown opcode).
- MTU model: `requestMtu` result configurable (`negotiatedMtu` hook, default 247);
  firmware-side per-command check → `[seq][0xE2]` when < 131.
- Immediate acks emitted synchronously from `write()` (BLE event context);
  completions from the worker (thread context); ACCEPTED→completion order guaranteed.
- 8-deep queue → `0xE1` BUSY; CANCEL never occupies a slot, accepted when full.
- Any enqueue interrupts an in-flight radio op → its own `[seq][0xBB]` (and on the stock
  personality, `0xBB` via the stock buffer — Phase A item 3).
- CANCEL both paths with per-path ordering (v2: `0xDD` first, then drained `0xBB`s;
  stock: `0xBB`s first, `0xDD` last overwriting the single buffer).
- v2 path performs **no trailing-`0x00` strip**; `seq` echoed verbatim (including
  duplicates — firmware enforces nothing); enveloped `0x10` → `[seq][0x22]`;
  envelope without inner opcode → `[seq][0x11]`; envelope without seq → silence;
  params > 123 → `[seq][0x11]`.
- v2 completions do not bump `responseCount` and do not touch `lastDataValue`.

Test matrix (all against both personalities where meaningful):

1. Bring-up: stock unchanged (regression); -ol1 → subscribed, MTU ok, bring-up steps go
   via v2 (assert no stock count bump during them); -ol1 + small MTU → STOCK fallback +
   journal evidence.
2. Golden frames: envelope bytes for each inner opcode; ack/completion parsing incl.
   the `0x11` position ambiguity.
3. Happy path: v2 SendAndListen → Reply, payload classification identical to stock.
4. Rejections: BUSY / MTU / malformed → `Rejected`; mutating command + `Rejected` does
   NOT report `Uncertain`.
5. ACCEPTED then silence → completion deadline → CANCEL → `0xBB` consumed → session
   still open and serves the next command.
6. CANCEL idempotence; CANCEL with nothing in flight → `0xDD`.
7. seq wrap (0xFF→0x00, 256 commands); unexpected-seq notification → session closed
   (R2 analog); drained `0xBB` post-CANCEL → ignored, not fatal.
8. Reboot mid-v2-command → link drop → existing close machinery (no regressions).
9. **RF identity**: identical `MinimedRadio.sendAndListen` call on stock vs -ol1
   personality → `txLog` packets byte-identical (locks the trailing-`0x00` decision).
10. Wake strategies: LONG_LISTEN cancellation mid-listen frees the fake radio via
    CANCEL and the session survives; SPLIT_BURST unchanged on both variants.
11. Supervisor end-to-end on -ol1 personality: full `PumpLinkSupervisorTest` sweep
    re-run with the v2 fake (parametrize the existing suite if cheap).
12. STOCK-protocol suite at both queue depths (resolved Q-a): vendor personality
    (`queueDepth = 1`) and -ol1 personality forced to STOCK (`queueDepth = 8`, e.g. via
    the MTU-fallback path) — overflow behavior differs (silent drop at depth 1 vs
    absorbed at depth 8) and the stock path must be correct against both firmwares.
13. Repeated-BUSY tripwire: 3 consecutive `0xE1` → session closed with a journaled
    reason, supervisor reconnects (resolved Q-b).

### Phase F — bench + rollout (Emily's hardware)

1. Flash `2.2-ol1`; verify bring-up log shows variant + MTU (expect 247/517) + v2
   bring-up steps + the DIS boot-counter/reset-reason line (Phase A item 5) +
   GET_STATISTICS hex (Phase B item 5).
2. Re-run the F1/F2/F3/F8 drill subset on the v2 path (the drill matrix doc defines
   them); add a new drill: **cancel-mid-listen** (Wake and Tune, then force
   disconnect/stop mid-listen; expect CANCEL, no session teardown, no `0xBB` wedge).
3. Q3 A/B: wake success rate SPLIT_BURST vs LONG_LISTEN over a scripted series
   (pump allowed to sleep between trials), using the RSSI chart + `LINKV2:` grep.
4. Overnight soak on v2 (same criteria as the 2026-07-02/03 soaks: zero
   Uncertain/LinkTrouble/GATT-133, queue never wedged) before it becomes the default
   worn configuration.
5. Regression soak point: one night back on stock firmware (or vendor RileyLink) to
   prove the STOCK path didn't rot.

## 3. Decisions taken (challenge in review if wrong)

- D1. Exchange-strategy inside `RfspySession`, not a session subclass — bring-up,
  mutex, epoch, close machinery are variant-independent.
- D2. All-v2 after opt-in (spec's intended use); stock count subscription retained.
- D3. MTU < 131 → whole-session STOCK fallback, loud, journaled (not per-command 0xE2
  handling).
- D4. `Rejected` is a new result type, mapped conservatively to `LinkTrouble`;
  I9-tightening (safe retry of mutating on `Rejected`) is a separate, later change.
- D5. No-ACCEPTED stays `Uncertain` for mutating in the first cut despite the spec
  implying not-executed — upgrade only with soak evidence.
- D6. Trailing-`0x00` strip lives in `V2Exchange` (opcode 4/5), locked by the
  cross-personality RF-identity test.
- D7. Wake default stays SPLIT_BURST until the Q3 A/B says otherwise.

## 4. Resolved questions (Emily, 2026-07-03)

- Q-a. **Queue depth**: -ol1 increased the queue for BOTH paths (stock included).
  → Fake `queueDepth` parameter; STOCK-protocol tests run at depth 1 (vendor) AND
  depth 8 (-ol1) so v1 protocol is proven against both firmwares. (Phase A item 4,
  matrix item 12.)
- Q-b. **Repeated BUSY**: log + close/reconnect confirmed. 3 consecutive `0xE1` →
  close session, supervisor reconnects. (Phase B item 3, matrix item 13.)
- Q-c. **DIS boot counter**: yes — piggyback into Phase A bring-up, non-fatal read.
  (Phase A item 5.)
- Q-d. **GET_STATISTICS**: yes — Phase B bring-up via the v2 envelope, non-fatal,
  journaled as hex. (Phase B item 5.)

## 5. Rough size

Phases A–C are the substance (~3 new files: `V2Codec`, exchange split, CANCEL; plus
`AndroidBleLink` MTU). D is small. E is the largest test surface (fake personality +
~15 tests). Everything except Phase F verifies on JVM; F needs the bench.
