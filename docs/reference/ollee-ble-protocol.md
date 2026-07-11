# Ollee Watch BLE Protocol (reverse-engineered)

Originally captured 2026-05-31 from the **official Ollee app** instrumented via the
`ollee-graphene` capture harness (a `CAPTURE=1` build that logs every
`BluetoothGattCharacteristic` write/notify as hex under logcat tag `OLLEE_BLE`); kept current
against `OlleeProtocol.kt` as the implementation has evolved.

## Transport
- Nordic UART service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- Write (app→watch) char `6e400002-…` (RX) · Notify (watch→app) char `6e400003-…` (TX)

## Frame

```
00 │ LEN │ AA 55 │ CRC16_hi CRC16_lo │ CMD TARGET │ payload…
```

- `LEN = (CMD TARGET payload).length + 4`  (e.g. a bare read = 2-byte inner → `LEN = 0x06`)
- `CMD` is always `0x02` in everything observed.
- `CRC` = CRC-16/CCITT-FALSE (poly `0x1021`, init `0xFFFF`, no reflect/xorout) over the inner
  bytes `CMD TARGET payload`.
- Large messages fragment across BLE writes (~20 B each) and reassemble by `LEN`.

## Request/response pairing

A **read** is `02 <target>` with no payload; the watch answers on the notify characteristic
with the **same data at `target + 0x20`** (`2A→4A`, `2E→4E`, `37→57`, …). A **write** is
`02 <target> <payload>` and is acknowledged at `target + 0x20`.

## Command catalogue (observed)

Reply/ack targets follow `RESPONSE_TARGET_OFFSET = 0x20`: a read or write to `02 <target>` is
answered/acked at `target + 0x20`.

| CMD·TARGET | Dir | Decoded payload | Meaning | Confidence |
|------------|-----|-----------------|---------|------------|
| `02 2A` → `4A` | R | `…DEADBEEF…01.05.0000.01.07…` | firmware / version string | capture-confirmed (2026-05-31) |
| `02 25` → `45` | W | alarm record (13 bytes, see below) | **TARGET_ALARM** — write the single alarm-face record; the chime "Try chime" preview shares this format (`playNow` byte) | on-device-verified (2026-06-11) |
| `02 26` → `46` | W | 4-byte header + 10× LE-uint32 (see below) | **TARGET_TIMERS** — write the Timer face's 10 countdown slots; header byte 3 selects configure-only/single/interval start | on-device-verified (2026-06-17) |
| `02 2B` → `4B` | R | `00010017007E0A05C0FF0FFF` | **TARGET_GET_ALARM** — read back the stored alarm record | derived (target verified in code; payload shape from 2026-05-31 capture) |
| `02 2C` → `4C` | R | `00012800` | **TARGET_GET_TIMER** — read back the timer-face config | derived (target verified in code; payload shape from 2026-05-31 capture) |
| **`02 2E` → `4E`** | R | ASCII `"  54 F"` | Ambient read-back cache. **Does NOT drive the Temperature face** (firmware-locked to the onboard sensor — see below) | on-device-verified (2026-06-01) |
| `02 2F` | W | ≤6 ASCII | **nameplate / "Name tag"** (hold ALARM on the Clock face); the field FreeOllee-Faces writes | capture-confirmed (2026-05-31) |
| `02 30` → `50` | R | `0000138800` | (unknown) | capture-confirmed (2026-05-31) |
| `02 32` → `52` | R | `0206 7182 0000 0078 FFFF 0000` | **TARGET_GET_CONFIG** — settings + autosleep config register (read): 4-byte BE settings bitmask (`CONFIG_BIT_AUTOSLEEP` = bit 6) followed by a 4-byte BE autosleep period in seconds | on-device-verified (2026-06-18) |
| `02 33` → `53` | W | settings bitmask + autosleep period (same layout as `0x52`) | **TARGET_SET_CONFIG** — read-modify-write of the config register; FreeOllee-Faces only touches the autosleep bit/period, leaving other bytes intact | on-device-verified (2026-06-18) |
| `02 34` | W | 4-byte header + `"MOTUWETHFRSASU"` | **weekday-name register — the 4-byte header is the World Time UTC offset** (big-endian two's-complement **seconds**), sharing this register with the weekday strings. `00007E90`=+9:00, `FFFFABA0`=-6:00, `00004D58`=+5:30. **Root cause of issue #34:** badge pushes rewrite this register and older code replayed `00007E90` verbatim, resetting World Time to +9. | **on-device-verified (2026-07-10)** |
| `02 35` → `55` | R | 4-byte offset header + `"MOTUWETHFRSASU"` | read weekday register / World Time offset (`TARGET_GET_WEEKDAYS`). Note: reflects only the phone-written offset; an on-watch zone change is stored elsewhere and does NOT appear here. | **on-device-verified (2026-07-10)** |
| `02 36` | W | face-record table (below) | **write enabled-faces config** | capture-confirmed (2026-05-31) |
| `02 37` → `57` | R | face-record table | read enabled-faces config | capture-confirmed (2026-05-31) |
| `02 39` → `59` | R | `002D` | (unknown) | capture-confirmed (2026-05-31) |
| `02 23` | W | 20-byte payload, all little-endian (see below) | **set clock / time + location** (`TARGET_SET_CLOCK`) | **on-device-verified (2026-07-10)** |

## Faces table (`02 36` write / `02 37`→`57` read)

Header `04 00000000 00`, then **6-byte records** in slot (display) order:

```
ID │ 01 │ ENABLED │ 01 │ ?? │ SLOT
```

`ENABLED` (3rd byte) is `00`/`01`. Confirmed by toggling Temperature in the UI: exactly one
byte changed (the `ENABLED` byte of `ID 0x0B`). Mapping (Clock is the always-on base and has
no record):

| Slot | ID | Face | | Slot | ID | Face |
|------|----|----|----|------|----|----|
| 01 | `05` | Alarm | | 08 | `0E` | Flashlight |
| 02 | `07` | Stopwatch | | 09 | `0A` | Step Counter |
| 03 | `09` | Timer | | 0A | **`0B`** | **Temperature** |
| 04 | `11` | Sunrise/Sunset | | 0B | `0C` | Heart Rate |
| 05 | `06` | World Time | | 0C | `0F` | Game A (PING) |
| 06 | `0D` | Counter | | 0D | `10` | Game B (Blackjack) |
| 07 | `08` | Set Clock | | 0E | `12` | Game C (Poker) |

Cross-check: the disabled faces shown in the UI (Alarm, Stopwatch, Timer, Sunrise/Sunset,
World Time) are exactly the records with `ENABLED=0` (IDs `05,07,09,11,06`).

## Timer / Alarm / Config payloads

Authoritative implementation: `OlleeProtocol.kt`. These three registers were added/clarified
after the original 2026-05-31 capture and are documented here from the code plus the on-device
verifications cited.

### Timer (`TARGET_TIMERS = 0x26`, ack `0x46`)

`buildTimerPacket()` emits a **4-byte header** followed by **ten little-endian uint32 countdown
durations** (seconds; `0` = blank slot):

```
header: [HH, MM, SS, MODE]
slots:  10 × uint32-LE seconds
```

- Header byte 0 = **hours** (not a flags/mode byte — hardware-verified 2026-06-15 by capturing
  the official app sending 20h05m00s as `14 05 00 01`), byte 1 = minutes, byte 2 = seconds. This
  seeds the Timer face's standalone **Quick-timer**, independent of the 10 slot durations.
- Header byte 3 is the **start mode**, `TimerStartMode`:
  - `0x00` (`SAVE`) — configure-only, persists the table without starting anything.
  - `0x01` (`START_SINGLE`) — starts a single countdown of the header HH:MM:SS immediately.
  - `0x02` (`START_INTERVAL`) — starts the 10-slot interval sequence immediately.

  These byte3 values were verified on hardware 2026-06-13 and are the reverse of an earlier
  (incorrect) design-doc guess.

### Alarm (`TARGET_ALARM = 0x25`, ack `0x45`; `TARGET_GET_ALARM = 0x2B`, reply `0x4B`)

`buildAlarmPacket()` writes a 13-byte record:

```
[enable, hourlyChime, snoozeEnable, hour, minute, dayMask, chime, snoozeMin, playNow,
 hourMaskLo, hourMaskMid, hourMaskHi, FF]
```

`playNow` (byte 8) drives the **chime preview** ("Try chime") — a transient effect the firmware
does not persist (it is absent from the `0x2B`/`0x4B` read-back), so the same write format is
reused for both "save alarm" and "preview chime" without disturbing the stored alarm. See the
`buildAlarmPacket` KDoc in `OlleeProtocol.kt` for the full byte-by-byte breakdown (day mask
polarity, snooze fields, hourly-chime active-hours mask).

### Config / autosleep (`TARGET_GET_CONFIG = 0x32`, reply `0x52`; `TARGET_SET_CONFIG = 0x33`)

The config register is a settings bitmask followed by the autosleep period:

```
bytes 0-3: settings bitmask, big-endian uint32 (CONFIG_BIT_AUTOSLEEP = bit 6)
bytes 4-7: autosleep_period, big-endian uint32 seconds
```

Confirmed on-device 2026-06-18. `WatchConfig.withAutoSleep()` performs a read-modify-write:
it flips only the autosleep bit and rewrites the period bytes, leaving every other settings
byte (DND, gestures, pedometer, BLE-continuous, …) untouched. Allowed period values, per the
official app's picker: `5, 10, 30, 60, 120` seconds (`CONFIG_PERIOD_VALUES_SEC`); a period is
only required/validated when autosleep is being enabled.

### Set-clock (`TARGET_SET_CLOCK = 0x23`)

The official app writes this on every connect/sync. **All fields little-endian.** Decoded from
HCI-snoop captures of official app 1.0.6, cross-checked against four 1.0.5 captures (2026-07-10):

```
bytes  0-3 : now,          LE uint32  Unix epoch seconds
bytes  4-7 : utc_offset,   LE int32   home UTC offset seconds (two's complement; e.g. FFFFABA0 = -21600 = -6h)
bytes  8-11: latitude,     LE int32   degrees × 1000  (e.g. 40140 = 40.140°N)
bytes 12-15: longitude,    LE int32   degrees × 1000  (e.g. FFFE6548 = -105144 = -105.144°W)
bytes 16-17: 0x0003        LE uint16  constant flag (unknown; replay)
bytes 18-19: 0xFFFF                   trailing, added in 1.0.6 (1.0.5 omitted it); constant (replay)
```

Note the offset uses the same two's-complement-seconds encoding as the World Time header
(`0x34`), but **little-endian here** vs big-endian there. The frame also carries the phone's
GPS position (for the Sun & Moon face), confirmed against `dumpsys location`. CRC-16/CCITT-FALSE
over the inner bytes as usual. Golden vector (LEN `0x1a`):
`001aaa55fb5302239c8e516aa0abffffcc9c00004865feff0300ffff` =
`build(now=1783729820, offset=-21600, lat×1000=40140, lon×1000=-105144)`.

## Temperature face — can it show outdoor weather? (RESOLVED: no)

**The Temperature face is firmware-locked to the onboard sensor and cannot be overridden over
BLE.** Tested on-device 2026-06-01 (watch `00:80:E1:26:DC:86`):

- `0x2E`/`0x4E` is a **read-back ambient field that does NOT drive the face.** Writing it
  changes only what the official app reads back, not the physical display.
- With measurements **ON**, the face showed `84 °F` (live, refreshing every few seconds) while
  `0x2E` held `"  55 F"` — no match. Pressing **ALARM on the Temperature face** toggled
  `84 °F ↔ 29 °C`, i.e. it converted the face's *own* value, not our injected `0x2E` (which
  would be `13 °C`).
- A full register sweep found no writable register holding the face's value; `0x2E` is the only
  ASCII temperature field and the face ignores it.

**Therefore:** outdoor temp can only be surfaced via the **`02 2F` nameplate** — the watch's
**"Name tag"**, viewable by holding **ALARM on the Clock face**. This is the existing
FreeOllee-Faces approach and the ceiling for this feature.

> Detail: an earlier experiment reported "CONFIRMED" because the official app read back our
> `0x2E` write — a false positive that never checked the physical face. The
> `experiment/temp-to-face-field` `0x2E` routing was reverted.
