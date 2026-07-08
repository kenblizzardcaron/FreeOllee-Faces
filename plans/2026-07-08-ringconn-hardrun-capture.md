# RingConn hard-run capture — settle the step-field width (u8 vs wider)

**Why:** `4c` activity records carry steps as body[14], apparently a single u8 per 2.5-min
bucket. 255/bucket = 102 steps/min of *registered* cadence — any real run exceeds that, so
the field must either saturate at 255, carry into a neighbor byte, or be a u16 after all.
Max observed so far is 110/bucket (treadmill walking, ring hand likely on the rail — the
ring registers only ~⅓–½ of true cadence when the arm doesn't swing). Blocks nothing in
the current build, but an overflow bug would silently undercount runs after merge.

## Protocol (Ken, ~10 minutes)

1. **Jog 5.5–6 mph for 6–8 continuous minutes** — guarantees 2–3 full buckets at high
   cadence (a single partial bucket can straddle the boundary and dilute below 255).
   5 mph is fine if the natural jog cadence is quick.
2. **Ring hand OFF the rail, swinging naturally the whole time** — this matters more than
   the speed; rail-holding is why walking buckets read so low.
3. Tell Claude when done (or note the wall-clock start/end times if solo).

## Analysis (Claude, after the run)

1. Verify full HCI snoop is still on (Developer options toggle resets on reboot; the
   `bluetooth_hci_log` settings key alone is NOT enough — flip the UI toggle if a
   bugreport comes back with only `btsnooz_hci.log`).
2. Wait ≥5 min after the run (bucket flush lag), then trigger a read:
   `adb37 shell am start -n com.blizzardcaron.freeolleefaces.debug/com.blizzardcaron.freeolleefaces.MainActivity`
   (the debug build acks `47`/`11` only, so this steals nothing from the RingConn app).
3. Pull `adb37 bugreport`, extract `FS/data/misc/bluetooth/logs/btsnoop_hci.log`, decode
   with `~/ringconn-captures/ring_records.py <log> records`.
4. Inspect the run-window buckets:
   - **Any bucket > 255** → field is wider than u8; find it (check body[13] as a BE high
     byte and body[15] as an LE high byte against the run's known cadence).
   - **Buckets pinned at exactly 255** → u8 saturation; check whether a neighbor byte
     lights up as a carry/overflow count.
   - **Buckets 150–254** → cadence didn't overflow; ALSO check body[13]/[15] stayed at
     their usual values, then rerun faster or accept u8 with a documented saturation
     ceiling (~102 steps/min per bucket).
5. Record the verdict in `plans/2026-07-03-ringconn-records-spike.md` ("Still owed" item)
   and, if the field is wider, fix `RingRecords` STEPS parsing + add a capture-vector test.

## Context anchors

- Branch `feat/ringconn-steps` @ 634a288 (unpushed), VERSION 0.33.0. Merge waits on this.
- Ack policy (do NOT change while testing): ack `47`/`11`, never `cc` — see
  `AndroidRingStepsSource` class doc and the spike doc's 2026-07-08 findings.
- Captures + analyzers live in `~/ringconn-captures/`.
