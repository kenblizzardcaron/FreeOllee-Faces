# RingConn Gen2 record-sync protocol spike (v2: true daily steps)

**Goal:** decode the stored-record sync protocol so `AndroidRingStepsSource.readSteps()`
can return the ring's TRUE daily step total (sum of today's records), replacing the
live-bout counter semantics that Task 7 disproved.

**User decision (2026-07-03):** "2 always. you know i always pick the real fix" — record
spike over the HC+bout workaround. Branch feat/ringconn-steps holds at 687cae5 (max()
semantics, unpushed) until this lands.

## Hardware-verified facts (Task 7, 2026-07-03)

- The status frames' step field (`10`/`87` at [4:6], `81 01` at [6:8]) is a **live
  activity-bout counter**: 0 at rest, climbs while walking (0→124→198→293→447 during the
  treadmill bout), resets to 0 minutes after the bout ends. NOT a daily total.
- The RingConn app computes daily steps by **summing synced records** (opened at stale 14,
  streamed rapidly to 636; later 699 after stairs).
- **RingConn does NOT write to Health Connect** on this phone (HC stayed ~337 while the
  app showed 636→699).
- Ground truth for correlation: sync at ~07:44 MT streamed records summing to ~622 steps
  (14 → 636). Treadmill bout ~06:50-07:00 (~450 steps). HC (phone-only) 337 at the time.

## Protocol findings so far

- **Timestamp format:** u32 big-endian, seconds since 2020-01-01 00:00:00 *ring-local*
  (equivalently UTC+8 / CST midnight if the ring clock runs on the app's set local time —
  disambiguate at implementation with a fresh capture). Verified: 15-min records decode
  to exactly 07:00/07:15/07:30 MT quarter-marks on capture day; fine records 06:53-07:00
  cover the treadmill bout; last record ≈ sync moment.
- **`4c` frames** carry ~24-byte records at 150 s cadence during activity (the walk
  window) — the step-bearing activity records (field layout TBD below).
- **`47` frames** carry 15-min-cadence records with sensor-sample-looking payloads
  (HR/wellness series, likely not steps).
- Records within a frame appear delimited by `d0 <ts(4)>` boundaries (confirm with
  splitter).

## CRACKED (2026-07-03, same session)

- **Step field: `4c` activity record body byte [14], single u8 = steps in that 2.5-min bucket.**
  Sum over the 20 captured buckets = **626** vs ground-truth daily 636 (gap = the 2-3
  buckets after 07:40 not in this frame). Per-bucket values track the bouts exactly:
  84 @06:53 (treadmill), 110/98 @07:05-07:08, 41 @07:20 (stairs), ~0 at rest. To confirm
  field WIDTH before shipping: a fast runner may exceed 255/bucket — capture a hard run and
  check whether [13:15] or [14:16] is the real u16, or [14] stays u8 with a carry field.
- **Fetch-since command: `02 00 <ts:u32-be> <b6> 01 00`** where ts is the same 2020-epoch
  UTC+8 seconds. Observed the app walk ts forward (…4b a5 → 4b c6 → 4c 02) to page/ack.
- **Stream pump / record request: `07 00 00`** (repeated), with `cc 00 00` / `c7 00 00`
  as related control (enumerate/close?). Records arrive as `47`/`4c` notifies on 0x0804.
- **Record framing: `d0`-delimited** — each record is `d0 <ts:u32-be> <body>`; `4c` bodies
  are 18-19 B (2.5-min activity buckets), `47` bodies 43-44 B (15-min wellness/HR series,
  NOT steps). Frame header `<id> 00 <b2>` where b2 looks like a remaining-count (…0d,07,01,00).
- Body byte [4] is a constant record-type marker (0x12) — NOT steps (was a false 586 hit).

## Remaining before implementation

1. Confirm step field width (u8 vs u16) with a >255/bucket run capture.
2. Nail the `02 00` fetch-since flags (b6/last two bytes) and stream-termination signal so
   we fetch TODAY-only, minimal frames, and know when the stream is done.
3. Decoder: add MTU-reassembly to decode_btsnoop.py (record frames can exceed one ACL).
4. Port to `AndroidRingStepsSource`: after auth, send `02 00 <today-midnight-ts>`, pump
   `07 00 00`, reassemble 4c frames, sum byte[14] over today's buckets, return the total.
   Replaces the live-bout counter; merge reverts to `max(ring_daily, HC)`.
5. On-device: our sum == RingConn app's daily, hands-off.

## Capture inventory (scratchpad)

- `ringapp-sync.zip` / `btsnoop_hci.log` — THE key capture: full official-app sync 14→636.
- `task7-capture.zip`, `task7-verify.zip` — our app's read cycles (auth + 81 01 frames).
- `decode_btsnoop.py` — ATT extractor (no reassembly; long frames may need PB-flag
  reassembly added for record frames > MTU).
