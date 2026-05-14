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

## Defects & follow-ups

Record each issue found:

| Title | Observed | Expected | Suggested fix |
|---|---|---|---|
| _none yet_ | | | |

## Acceptance

When every row above is filled in with PASS (or follow-ups are committed), this task is complete and the Done criteria in the implementation plan are met.
