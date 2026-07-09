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

## CRACKED (2026-07-03, same session) — see 07-08 corrections below

- **Step field: `4c` activity record body byte [14], single u8 = steps in that 2.5-min bucket.**
  Per-bucket values track the bouts exactly: 84 @06:53 (treadmill), 110/98 @07:05-07:08,
  41 @07:20 (stairs), ~0 at rest. Post-wake window (06:53→07:40) sums to 626 ≈ the observed
  app climb (~622).
- Body byte [4] is NOT steps (was a false 586 hit).

## CORRECTED & COMPLETED (2026-07-08, reassembly re-analysis)

Re-decoded the full capture with ACL reassembly (`decode_btsnoop.py` updated;
`ring_records.py` is the new record analyzer, both in ~/ringconn-captures/). The capture
spans **~11.4 h and two phone sessions** (07-02 evening + 07-03 morning), which the first
pass misread as one sync. Corrections:

- **Record framing is fixed-width, NOT `d0`-delimited.** Frame = `<id> 00 <b2>` +
  back-to-back records + 1 trailing byte (checksum?). `4c` record = ts:u32be + body[19]
  (2.5-min bucket, steps u8 at body[14]); `47` record = ts:u32be + body[43] (15-min
  wellness). Lengths confirm: 142 = 3 + 6×23 + 1; 145 = 3 + 3×47 + 1. The "d0 delimiter"
  was the last body byte of the previous record.
- **`02 00 <ts> …` is a time-sync/ack, NOT fetch-since.** Its ts tracks the wall clock at
  send time in both sessions; reply is a bare `82 00 00`. There is NO fetch-since command
  in the capture — the ring pushes un-acked records automatically.
- **Transport model: continuous drip + cursor-by-ack.** While connected, the ring streams
  records near-real-time as `47`/`4c` notifies on 0x0804. The phone acks each frame by
  type: `cc 00 00` acks a `4c` frame, `c7 00 00` acks `47`, `91 00 00` acks `11`
  (ack id = frame id | 0x80). On reconnect the ring replays every record since the last
  ack — the morning session opened with a b2-countdown backlog burst (61→55→…→0) covering
  exactly the overnight gap. **`b2` = records remaining after this frame; `b2 == 0` =
  stream drained** (per record type). `07 00 00` is a status poll (reply `87`, the live
  bout counter), not a record pump. `d0 00 00` gets no reply (ack for an unseen type-50
  frame, or keepalive).
- **Σ body[14] over a calendar day ≠ the app's daily.** All of 07-03 through 07:40 sums
  to 1071; the app showed ~636. Overnight buckets (user asleep) carry ~370 "steps" —
  sleep-movement artifacts. The app excludes sleep-flagged buckets; overnight records have
  `01 01 01 01 01` at body[6:11] (sensor/wear state), daytime records have real values.
  No tested flag rule reproduces 636 exactly (not-all-01 → 694; post-wake-only → 626) —
  the eyeballed ground truth (~14 → ~636 → ~699) is too fuzzy to pin the app's exact
  algorithm from this capture.
- **No daily-summary frame type exists** (0x0804 carries only 10/11/47/4c/81/82/87), and
  no monotonic daily-cumulative field hides in the `4c` body (exhaustive u16/u24 offset
  scan). Daily totals MUST be computed by summing buckets.
- Field width: body[13] and body[15] are independent fields (both vary on zero-step
  records), so [14] is a standalone u8. NOTE: 255 steps/bucket = 102 steps/min — a brisk
  walk or run exceeds that, so there must be saturation, an overflow/carry field, or
  adaptive bucket cadence. Still needs the hard-run capture to settle.

## Implementation model (replaces old item 4)

`AndroidRingStepsSource.readSteps()`: connect → auth → enable notifies → receive the
replay backlog → ack frames (`cc`/`c7`/`91`) → drained when each type hits `b2 == 0` →
sum today's `4c` buckets (exclusion rule per the product decision below) → **persist a
running daily sum phone-side** (the ring only replays since last ack, so each read adds
new buckets to the stored total; reset at local midnight).
Alternative idempotent-read variant (needs hardware test): ack only frames wholly from
before today, leave today's un-acked so every read replays the whole day — no phone-side
persistence, but unknown whether the ring keeps streaming without per-frame acks.

## Decisions & hardware verification (2026-07-08)

1. **Product decision (Ken):** sleep-excluded best-effort — count a bucket unless
   body[6:11] are all 0x01. Implemented.
2. **On-device 2026-07-08 (Pixel 7, debug build):** the full read pipeline works — the
   ring replayed the day's backlog to our client, `ring_daily_sum` hit 4268 by 10:09 MT
   (plausible true daily), watermark tracked the newest bucket, max(ring, HC) displayed it.
3. **CRITICAL: the replay cursor is SHARED, not per-client.** Our per-frame acks consumed
   the records permanently — the official app reopened to 0 steps and only accumulated the
   post-10:09 drip (132 by 13:48). Today's activity/wellness morning is unrecoverable in
   RingConn. Frames arrive pipelined (4-in-1ms) — the ring does NOT gate streaming on acks.
4. **Pure no-ack FAILED on hardware:** a fully silent client works exactly once, then
   stalls — the ring re-sends the pending un-acked `47` wellness frame every session and
   never opens the `4c` stream behind it. Meanwhile records delivered un-acked are never
   re-delivered to OTHER clients (the 13:52-14:05 window is permanently absent from the
   official app). Streams gate on in-order acks.
5. **FINAL architecture (payload-verified end-to-end same day): ack everything EXCEPT
   `4c`.** Send `c7`/`91` (unblocks the session), never `cc`. Result, confirmed in the
   full btsnoop: our reads receive the activity window since the SHARED cursor (which only
   the official app's `cc` advances), re-delivered idempotently every read (watermark
   dedupes: banked +138/+181/+53 across three reads of an overlapping window); the official
   app afterwards received the identical records (15:38→15:50 + next bucket) and acked
   them. Zero activity-record theft. Cost: we consume `47` wellness records, which the
   official app's sleep/vitals scores demonstrably survive (83/90 intact after a whole
   morning of `c7` acks). If the official app is ever dropped, the un-acked `4c` window
   grows to ring storage (~7 days) — reads stay correct, just longer; an opt-in ack mode
   can be added then.

## Experiment cost (owed an apology)

Today's (2026-07-08) official-app history is permanently gutted by the ack experiments:
the 00:00→10:08 backlog (~4268 steps incl. the morning treadmill) and the 13:52→14:05
window (~250 steps) were consumed and are unrecoverable. Wellness `47` records from the
same windows likewise. From tomorrow both apps run clean.

## Still owed

- Hard-run >255-steps-per-bucket capture to settle u8 saturation vs overflow field —
  full protocol in `plans/2026-07-08-ringconn-hardrun-capture.md`.
- No-ack longevity: confirm large (multi-hour) backlogs stream fully to a silent client.
- Sleep-exclusion parity check against the app on a clean day (today's RingConn data was
  corrupted by the ack test, so parity can only be judged from tomorrow onward).

## Capture inventory (scratchpad)

- `ringapp-sync.zip` / `btsnoop_hci.log` — THE key capture: full official-app sync 14→636.
- `task7-capture.zip`, `task7-verify.zip` — our app's read cycles (auth + 81 01 frames).
- `decode_btsnoop.py` — ATT extractor (no reassembly; long frames may need PB-flag
  reassembly added for record frames > MTU).
