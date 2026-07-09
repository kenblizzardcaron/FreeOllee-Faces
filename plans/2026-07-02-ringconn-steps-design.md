# RingConn live step source — design spec

**Date:** 2026-07-02
**Status:** approved for planning
**Feature branch:** to be decided at plan time (new branch off `main`; NOT part of PR #35)

## Goal

Let the Steps complication read the RingConn Gen2 ring's live onboard step count directly
over BLE, as a fresher source than Health Connect, behind an opt-in toggle. When enabled and
the ring is reachable, the complication shows `max(ringCount, healthConnectCount)`; otherwise
it behaves exactly as today.

## Motivation

Health Connect only reflects the ring's steps when RingConn syncs to it, so between syncs the
complication shows a stale count. The ring itself keeps a live onboard pedometer that is
readable over BLE within seconds. Reading it directly closes the freshness gap without waiting
on RingConn's cloud/Health-Connect sync cycle.

This is deliberately niche (likely single-user), so it is strictly opt-in and never a hard
dependency: any failure falls back to the existing Health Connect path silently.

## Key facts (from prior art)

Source: OpenRingConn / "OpenCircuit" (MIT-licensed, github.com/perezjuanj/OpenRingConn),
which reverse-engineered the RingConn Gen2/Gen3 BLE protocol. Relevant findings, all to be
re-verified on-device in the capture spike (Task 0):

- **Multiple simultaneous connections.** The ring accepts a connection from a second central
  without disturbing its existing pairing. FreeOllee opens its *own* GATT connection; it does
  not and cannot "piggyback" on the RingConn app's connection. "While RingConn is open" is
  therefore not a real constraint.
- **Primary data service** `8327ad99-2d87-4a22-a8ce-6dd7971c0437`:
  - Write/command characteristic `8327ad98-2d87-4a22-a8ce-6dd7971c0437`
  - Notify characteristic `8327ad97-2d87-4a22-a8ce-6dd7971c0437` (enable via CCCD
    `00002902-…` written `01 00`)
- **Status descriptor** — a fixed 19-byte frame the ring emits spontaneously every ~30–60 s
  while connected, and on demand in response to command `d0 00 00`. Response id byte `[0]` is
  `0x10` (spontaneous / `d0` reply) or `0x87` (reply to `07 00 00`); the body layout is
  identical. **Step count is bytes `[4:6]`, 16-bit big-endian.** The frame carries an XOR
  trailer (XOR of all preceding bytes).
- **Ring count ≠ app total.** The `[4:6]` value is the ring's own onboard daily count, which
  can differ from the RingConn app's cloud-aggregated total (Health Connect may sum the ring
  plus a phone pedometer or other sources). This is *why* the merge rule is `max`, not
  "replace".
- **Bond required.** An unbonded central gets only the `0x01` handshake; data flows only on a
  bonded LE link. The user's phone is already bonded via the official RingConn app.
- **Per-connection auth (conditional).** The ring may require a challenge-response to
  "activate" streaming: host `01 00 00` → ring `81 00 <challenge> <xor>`; host answers
  `01 01 <r0> <r1> <r2> 00` where
  `response = SM3(byteArrayOf(V, challenge))[29..31]`, `V = mac[3] xor mac[4] xor mac[5]`,
  and `mac` is the ring's BLE MAC (readable from the DIS System ID characteristic
  `0x2a23`, or on Android directly from `BluetoothDevice.getAddress()` for a bonded device).
  SM3 is the GB/T 32905 hash. The prior art also notes the spontaneous descriptor path may
  not need this at all on a bonded link — **Task 0 decides whether we implement auth**.

## On-device reconnaissance (2026-07-02, verified)

`adb shell dumpsys bluetooth_manager` on the Pixel 7 confirmed, before any code:

- The ring is bonded as **`RingConn Gen2-962A`** — an LE device under package
  `com.gdjztech.ringconn`. Gen2 confirmed; the `962A` name suffix matches the MAC's last two
  bytes (the prior art's naming scheme holds).
- The `Ollee Watch` is separately bonded under our own app package, so filtering bonded
  devices by the `RingConn` name prefix returns exactly one match and never collides with the
  watch. The zero-scan / no-location-permission discovery path is validated against the real
  device list.
- On Android the full ring MAC is available directly from the bonded `BluetoothDevice`
  (`getAddress()`), so the auth branch (if needed) does not require the DIS System-ID read the
  iOS prior art used.

## btsnoop capture verification (2026-07-02, on-device)

A Bluetooth HCI snoop capture of the official RingConn app syncing (extracted via
`adb bugreport`, decoded with `scratchpad/decode_btsnoop.py`) confirmed the protocol on the
user's actual Gen2 ring (connection `0x0040`):

- **ATT handles:** write `0x0802`, notify `0x0804`, notify CCCD `0x0805` (enabled with
  `01 00`) — matches the prior art.
- **Handshake order:** CCCD enable → `01 00 00` → ring `81 00 11 90`
  (`sub=00`, challenge `0x11`, XOR trailer `0x90` = `0x81^0x00^0x11`, valid) →
  host `01 01 e1 b9 23 00` → sync-open `02 …` → `07 00 00` fetch → `0x87` descriptor.
- **Steps at descriptor `[4:6]` BE — confirmed live.** e.g. `10 4f 03 00 00 5a …` → 90; the
  `[4:6]` value climbed 15 → 314 monotonically across the ~6-minute buffer. Both `0x87`
  (fetch reply) and `0x10` (spontaneous, ~30–60 s cadence) carry the identical 19-byte
  descriptor.
- **Cross-checks:** battery voltage `[14:16]` = `10 26` = 4134 mV; case byte `[17]` = `0xff`
  (ring on finger, not docked) — both consistent with the prior art's decode.
- **SM3 auth algorithm verified against real data.** For challenge `0x11`, a unique
  `V = 0x4a` reproduces the captured response `e1 b9 23` via
  `SM3(byteArrayOf(V, challenge))[29..31]`. This confirms the algorithm on-device and implies
  the ring MAC ends `…F6:96:2A` (`V = mac[3]^mac[4]^mac[5] = 0xf6^0x96^0x2a = 0x4a`). Use this
  as a `RingAuth` test vector alongside the GB/T 32905 SM3 known-answer vectors.

**Real test frames** (for `RingConnDescriptorTest`, all XOR-valid):
- `87 4f 03 00 00 0f 01 3b 01 45 00 00 00 00 10 26 00 ff 73` → steps 15
- `10 4f 03 00 00 5a 01 37 01 40 00 00 00 00 10 26 00 ff b8` → steps 90
- `10 4f 02 00 01 3a 01 2a 01 38 00 00 00 00 10 28 00 ff 24` → steps 314

**Still open (Task 0 only):** the capture cannot prove whether *our* connection can skip auth,
because the official app always authenticates. A minimal GATT probe (enable notify, do NOT
auth, wait for a `0x10`) is the sole remaining question — and if it comes back silent, the
auth path is fully de-risked (algorithm + on-device vector in hand).

## Approach

Capture-spike-first. Task 0 connects to the ring from the bonded phone over adb (user walks on
a treadmill to generate steps) and answers one question: *does a bonded Android connection
receive step descriptors without the auth handshake?*

- If **yes** → ship the passive path only (no crypto).
- If **no** → add the `RingAuth` + SM3 module and nothing more.

Everything downstream of that decision is identical. Auth is an optional, self-contained module.

## Architecture

### Pure core (commonMain — no Android, fully unit-testable)

**`RingConnDescriptor`** — pure parser.

```
object RingConnDescriptor {
    /** Parse the ring's onboard step count from a status descriptor frame.
     *  Returns null on any malformed frame (wrong id, too short, bad XOR). */
    fun parseSteps(frame: ByteArray): Long?
}
```

Validation: `[0]` is `0x10` or `0x87`; length ≥ 6; XOR trailer (last byte == XOR of all
preceding bytes). Steps = `((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)`
as `Long`. Tested against captured hex frames (valid, wrong-id, truncated, bad-trailer, zero).

**`RingStepsSource`** (interface).

```
interface RingStepsSource {
    /** Read the ring's live onboard step count.
     *  success(n)    — the ring's onboard count
     *  success(null) — connected but no step frame arrived this cycle
     *  failure       — read genuinely failed; caller falls back to Health Connect */
    suspend fun readSteps(): Result<Long?>
}

/** Default no-op used when the feature is off or on non-Android targets. */
object NoopRingStepsSource : RingStepsSource {
    override suspend fun readSteps(): Result<Long?> = Result.success(null)
}
```

**`RingAuth`** (built only if Task 0 shows auth is required).

```
object RingAuth {
    /** V = mac[3]^mac[4]^mac[5]; response = SM3(byteArrayOf(V, challenge))[29..31].
     *  Returns the 6-byte command `01 01 r0 r1 r2 00`. */
    fun authCommand(challenge: Int, mac: ByteArray): ByteArray
}
```

Ships with a pure-Kotlin SM3 implementation carrying GB/T 32905 known-answer test vectors
(the standard `"abc"` digest and the 64-byte-message digest). No external crypto dependency.

### Android glue (androidMain)

**`AndroidRingStepsSource(context, addressProvider)`** implements `RingStepsSource`.

`readSteps()` connect-on-demand flow:
1. Resolve the ring MAC from `addressProvider()` (Prefs `ringConnAddress`); if null → `failure`.
2. `device.connectGatt(context, false, cb, TRANSPORT_LE)` (autoConnect false).
3. `discoverServices()`; locate service `8327ad99…`, notify char `8327ad97…`, CCCD.
4. Enable notify: `setCharacteristicNotification(notify, true)`, write CCCD `01 00`.
5. (Auth branch only) on the first `0x81 00 …` frame, compute `RingAuth.authCommand` and
   write it to command char `8327ad98…`.
6. Optionally write `d0 00 00` to the command char to prompt a descriptor (belt-and-suspenders
   alongside the spontaneous emission).
7. Await the first frame that `RingConnDescriptor.parseSteps` accepts, with a short timeout
   (default 4000 ms). Return `success(steps)`.
8. On timeout with a live connection → `success(null)`. On connect/discover/link failure →
   `failure`.
9. Always `gatt.close()` in a finally; the connection is short-lived, never held.

Reuses `BleRetryPolicy` for the connect attempt (same 3-attempt / 2s,4s backoff shape as the
watch path) and mirrors the GATT callback structure of `ble/WatchLink.kt`. It is a *separate*
short-lived GATT from the watch's process-wide `WatchLink` singleton — different device, no
shared state.

**Ring discovery** — `RingConnDiscovery(context)`:
- Enumerate **bonded** devices: `bluetoothAdapter.bondedDevices`.
- Filter to `device.name?.startsWith("RingConn") == true`.
- Exactly one match → return its address (auto-select).
- Zero matches → signal "pair the ring in the RingConn app first".
- Multiple matches → return the list for a picker.

Bonded-enumeration + connect-by-address means **no BLE scan and no location permission** —
only `BLUETOOTH_CONNECT`, already declared in the manifest and already exercised by the watch
feature.

### Wiring

**Prefs** (new keys, following the existing `notificationsEnabled` boolean and `watchAddress`
string patterns):

```
var ringConnStepsEnabled: Boolean   // KEY = "ringconn_steps_enabled", default false
var ringConnAddress: String?        // KEY = "ringconn_address", default null
```

**`AppViewModel`** — new constructor param `ringSteps: RingStepsSource = NoopRingStepsSource`,
passed into `ComplicationController`. Default keeps commonTest and non-Android construction
working unchanged (mirrors how `instrumentsProvider` defaults to `NoopInstrumentsProvider`).

**`MainActivity.createAppViewModel`** — construct
`ringSteps = AndroidRingStepsSource(context) { Prefs(appSettings(context)).ringConnAddress }`
(or route the existing prefs instance in), alongside `steps = AndroidStepsProvider(context)`.

**`ComplicationController`** — new ctor param `ringSteps: RingStepsSource`. In `refreshSteps`,
only when `prefs.ringConnStepsEnabled` is true, after the existing Health Connect read
resolves:
- On Health Connect **success(count)**: attempt `ringSteps.readSteps()`. If it returns
  `success(ringCount)` with a non-null value, display and record `max(count, ringCount)`
  instead of `count`. If it returns `success(null)` or `failure`, use `count` exactly as today.
- On Health Connect **failure**: attempt `ringSteps.readSteps()`. If it yields a value, treat
  it as a fresh reading (record + display, not stale-marked). Otherwise the existing
  cached/stale fallback path runs unchanged.
- The `max` is computed before `prefs.recordStepsFetch(...)` so the cache and the "Updated …"
  timestamp reflect the displayed value.

The ring read must never block or fail the Health Connect path: it is awaited only after the
Health Connect result is in hand, and any exception is folded into `Result.failure` inside
`AndroidRingStepsSource`.

**Settings screen** — in the Steps section: a "Read steps from RingConn (BLE)" toggle bound to
`ringConnStepsEnabled`, off by default. When first enabled, run discovery: auto-select a single
bonded ring, show a picker for multiple, or show "Pair your ring in the RingConn app first,
then try again" for none. Show the selected ring's name once chosen.

## Data flow

```
Steps complication refresh (existing interval / active-face gating)
  → ComplicationController.refreshSteps(push)
      → StepsProvider.todaySteps()            [Health Connect, unchanged]
      → if ringConnStepsEnabled:
            RingStepsSource.readSteps()        [Android: short-lived GATT to the ring]
              → connectGatt → discover → enable notify
                → (auth iff required) → await descriptor (≤4s)
                → RingConnDescriptor.parseSteps([4:6] BE) → close
      → display/record max(healthConnect, ring)   (ring absent ⇒ health connect only)
      → existing cache / stale-mark / push-to-watch path unchanged
```

## Error handling

- **Toggle off** → `NoopRingStepsSource`; zero behavior change, zero BLE traffic.
- **No ring selected / not bonded** → discovery surfaces the "pair first" hint; `readSteps`
  returns `failure`; Health Connect path runs as today.
- **Ring out of range / on charger / timeout** → `failure` or `success(null)`; silent
  fallback to Health Connect (user chose silent — no error surfaced on the card).
- **Malformed frame** → `parseSteps` returns null; treated as no reading this cycle.
- **BLE permission missing** → `readSteps` returns `failure`; Health Connect path runs.
- Every GATT resource is closed in a finally; no held connection, no leak, no contention with
  the watch link.

## Testing

**Pure unit tests (commonTest):**
- `RingConnDescriptorTest` — parse a real captured `0x10` frame → expected steps; `0x87`
  frame → same; wrong id → null; truncated → null; bad XOR trailer → null; zero steps → 0.
- `RingAuthTest` (auth branch only) — SM3 known-answer vectors (`"abc"` and the 64-byte
  message from GB/T 32905); `authCommand` against the captured challenge→response pairs from
  Task 0 (the spike yields real pairs to pin against).
- `ComplicationControllerTest` — new cases with a fake `RingStepsSource`:
  - ring > health connect → displays ring value, records it
  - health connect > ring → displays health connect value
  - ring `success(null)` → displays health connect value unchanged
  - ring `failure` → displays health connect value unchanged
  - health connect failure + ring value → displays ring value, not stale
  - toggle off → ring source never consulted

**On-device (Task 0 capture spike + final verification):**
- Task 0: adb-connected phone, user walks on treadmill; confirm a bonded Android connection
  receives descriptors, `[4:6]` tracks the rising step count, and whether auth is needed.
  Capture challenge→response pairs if auth is required.
- Final: with the toggle on, walk and confirm the complication reflects fresh ring steps
  within one refresh interval, and that toggling off restores pure Health Connect behavior.

The GATT calls themselves are not unit-testable (same constraint as the watch stack); they are
covered by the on-device verification, while all decode/merge/auth logic is pure and tested.

## Scope / non-goals

- **Steps only.** No heart rate, temperature, SpO2, sleep, or battery from the ring — the
  descriptor carries them but they are out of scope.
- **No persistent connection.** Connect-on-demand at the existing refresh interval only.
- **No background service.** The read piggybacks on the existing steps-refresh path (including
  `AutoUpdateWorker`), inheriting its scheduling and active-face gating; no new scheduler.
  Background reads are best-effort: if a backgrounded connect fails or times out, the silent
  Health Connect fallback covers it — the ring is never a hard dependency in any context.
- **Gen2 only** (the user's ring). Gen3 offsets unverified; not targeted.
- **No Health Connect writes.** We read the ring for display freshness; we do not write the
  ring's count back into Health Connect.
- **No RingConn cloud / account integration.** Purely local BLE.

## Compute Offload (claude-local)

Gated, self-contained chunks suitable to delegate to `claude-local` (each with its
deterministic gate). Run `claude-local-brief-check BRIEF.md` before dispatch; always
`git diff`-verify; claude-local output is a draft until the gate is green.

- **`RingConnDescriptor` (new file) + `RingConnDescriptorTest` (new file)** — pure parser and
  its table of captured-hex cases. Gate: `:app:testDebugUnitTest` green + `:app:detekt`.
  Whole-new-file shape, ideal for delegation.
- **Pure-Kotlin SM3 + `RingAuth` (new files) + KAT tests (new file)** — *auth branch only*;
  deterministic, gated entirely by the GB/T 32905 known-answer vectors. Whole-new-file.
- **`RingStepsSource` / `NoopRingStepsSource` (new file)** — tiny interface + no-op. Gate:
  compile (`:app:assembleDebug`).

The Android GATT glue (`AndroidRingStepsSource`, discovery), the `ComplicationController` merge
edit, Prefs keys, VM/factory wiring, and the Settings toggle are controller-authored
(modify-existing, cross-file, judgment — not claude-local's reliable shape). Text-out (commit
bodies, PR body) authored directly.
