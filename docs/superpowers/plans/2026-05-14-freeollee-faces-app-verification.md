# FreeOllee Faces — On-Device Verification Report

**Plan:** `docs/superpowers/plans/2026-05-14-freeollee-faces-app.md` (Task 13)
**APK built at:** `app/build/outputs/apk/debug/app-debug.apk`
**APK SHA-256:** `3795bb683446902f912d7a09e8aa097ad2c0a630f3bced31d1932aaffcafb4b0`
**Built against:** Kotlin 2.2.10, AGP 9.1.1, compile/target SDK 36, min SDK 31
**Unit test status at build time:** 26/26 passing (OlleeProtocol 6, DisplayFormatter 11, SunCalc 5, OpenMeteoClient parser 4)

## Verification environment

Fill in when you run the checks:

- **Date of verification:** _TBD_
- **Phone:** _TBD_ (model)
- **GrapheneOS version:** _TBD_
- **Ollee watch firmware:** _TBD_ (if visible)
- **Pairing path:** Android Settings → Bluetooth → pair "Ollee Watch" before opening the app.

## Install

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

(Or copy the APK to the phone and tap it in the Files app — debug APKs are user-signed and side-loadable.)

## 13.3 — Watch pairing & selection

1. Pair the Ollee watch in Android Bluetooth settings.
2. Open FreeOllee Faces. Tap **Select watch**.
3. Confirm the runtime `BLUETOOTH_CONNECT` prompt fires the first time.
4. A dialog should list the bonded Ollee. Select it.
5. Confirm the label updates to `Watch: <name>`.
6. Force-close + reopen the app: confirm the watch label persists.

| Sub-check | Result | Notes |
|---|---|---|
| Runtime permission prompt fires once | _TBD_ | |
| Bonded list shows the Ollee | _TBD_ | |
| Label updates after pick | _TBD_ | |
| Selection persists across relaunch | _TBD_ | |

## 13.4 — Temperature send

1. Enter valid coords (e.g. your home coords).
2. Tap **Send temperature**.
3. Confirm status: `Fetching temperature…` then `Sent '  72 F'.` (or similar).
4. Photograph the watch face. Cycle to the watch's temperature face (face `F` per the home-screen image). Photograph again.
5. If a character renders wrong, edit `DisplayFormatter.temperature`, add a regression test, commit.

| Sub-check | Result | Notes |
|---|---|---|
| Open-Meteo fetch succeeds | _TBD_ | |
| Watch LCD shows the value | _TBD_ | attach photo |
| Same value on temperature face vs. default face | _TBD_ | |

## 13.5 — Sun time send

1. Same coords. Tap **Send next sun event**.
2. Confirm status: `Sent '8:15ps'.` (or similar — depends on local time).
3. Photograph the watch on the Sunrise/Sunset face (face `S`).
4. If wrong characters render, edit `DisplayFormatter.sunTime`, add regression tests, commit.

| Sub-check | Result | Notes |
|---|---|---|
| SunCalc produces the expected event | _TBD_ | |
| Watch LCD shows the time | _TBD_ | attach photo |
| `r`/`s` and `a`/`p` markers render | _TBD_ | |

## 13.6 — Custom send

1. Type `123456` in Custom, tap **Send custom**. Expected `Sent '123456'.`.
2. Type `hi`. Expected sends `"hi    "` (padded).
3. Type `toolongvalue`. Expected sends `"toolon"` (truncated).

| Sub-check | Result | Notes |
|---|---|---|
| 6-char value passes through | _TBD_ | |
| Short value pads correctly | _TBD_ | |
| Long value truncates | _TBD_ | |

## 13.7 — Location flows on GrapheneOS

Reset Location permission between subcases via Settings → Apps → FreeOllee Faces → Permissions.

1. **Both location permissions denied:** Deny both at the prompt.
   - Expected status: `Location permission denied — enter coordinates manually.`
2. **Approximate only:** Grant Approximate.
   - Expected: lat/lng populate with a coarse fix; status names the provider + accuracy.
3. **Precise:** Grant Precise; go outdoors.
   - Expected: GPS provider fix; ±10–30 m accuracy.
4. **Precise but providers disabled:** With Precise granted, turn Location off in Settings.
   - Expected status: `Location failed: no location providers enabled`.

| Sub-case | Result | Notes |
|---|---|---|
| 1. Denied | _TBD_ | |
| 2. Approximate | _TBD_ | |
| 3. Precise | _TBD_ | |
| 4. Providers off | _TBD_ | |

## 13.8 — Error paths

1. Turn Wi-Fi and mobile data off. Tap **Send temperature**. Expected `Weather fetch failed: …`. No BLE write attempted.
2. Turn the watch off / move out of range. Tap **Send custom**. Expected `Send failed: …` after ~8 s.
3. Enter `lat = 95` (invalid). Confirm Send Temp / Send Sun buttons disable.

| Sub-check | Result | Notes |
|---|---|---|
| Offline -> weather error | _TBD_ | |
| BLE timeout -> send error | _TBD_ | |
| Invalid coords disable send | _TBD_ | |

## v0.2 — Preview & temperature unit

1. **Auto-preview on launch (permission held):** Open app cold. Both preview cards transition from Loading to Ready within ~3 s. Status line reads `Got fix: …`.
2. **Auto-preview on launch (permission denied, saved coords present):** Revoke location, reopen app. Status line reads `Using saved coordinates.` Both previews populate without any prompt.
3. **Auto-preview on launch (no saved coords, no permission):** Clear app data, reopen. Both cards show `Enter coordinates manually to see previews`; manual fields work as the recovery path.
4. **F / C toggle:** Tap °C. Temperature preview re-fetches, payload format becomes `"  22 C"` (or similar), persists across app restart. Sun preview is unaffected.
5. **Refresh button:** Tap Refresh. Both cards transition Loading → Ready again (within ~3 s); previews update with current values.
6. **Coord edit debounce:** Type a new lat/lng one character at a time. Refresh fires only once, ~500 ms after the last keystroke (not on every key).
7. **°C on the watch LCD:** Switch to °C, tap Send on the temperature card. Photograph the watch; the `C` character must be readable and distinguishable from `F` on the temperature face. If not, tweak `DisplayFormatter.temperature` or `TempUnit.symbol` and commit a regression test alongside.
8. **Per-card send disable when no watch:** Forget the watch. Both Send buttons go disabled; Custom Send also disables.
9. **Per-card send disable while sending:** Tap Send on a Ready card. While the BLE write is in flight, both Send buttons (temp + sun) and Custom Send disable until the status flips to `Sent …` or `Send failed: …`.
10. **Sun NoEvent rendering:** Enter `lat = 78.0, lng = 15.0` (high arctic) on a date near summer solstice. Sun preview must show `No sunrise/sunset in next 24 h.` and its Send button must disable.

| Sub-check | Result | Notes |
|---|---|---|
| 1. Auto-preview on launch (permission held) | _TBD_ | |
| 2. Auto-preview on launch (permission denied + saved coords) | _TBD_ | |
| 3. Auto-preview on launch (no saved coords, no permission) | _TBD_ | |
| 4. F / C toggle | _TBD_ | |
| 5. Refresh button | _TBD_ | |
| 6. Coord edit debounce | _TBD_ | |
| 7. °C on the watch LCD | _TBD_ | attach photo |
| 8. Per-card send disable (no watch) | _TBD_ | |
| 9. Per-card send disable (while sending) | _TBD_ | |
| 10. Sun NoEvent rendering | _TBD_ | |

## Defects & follow-ups

Record each issue found:

| Title | Observed | Expected | Suggested fix |
|---|---|---|---|
| _none yet_ | | | |

## Acceptance

When every row above is filled in with PASS (or follow-ups are committed), this task is complete and the Done criteria in the implementation plan are met.
