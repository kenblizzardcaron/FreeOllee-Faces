# FreeOllee Faces — Android App Design

**Date:** 2026-05-14
**Status:** Approved by user; ready for implementation planning.

## Problem

The official Ollee Watch companion app's Sunrise/Sunset face needs the phone's location, which is not available reliably on GrapheneOS (the official app uses Google Play Services' Fused Location Provider, which is absent or stubbed on GrapheneOS). The user wants to feed the watch the same kind of glanceable data (temperature in °F, next sunrise/sunset time) from either a manually-entered lat/lng or — when the OS will provide one — a fix obtained via the platform `LocationManager` directly (which works on GrapheneOS without Google Play Services).

Community / custom watch faces are not yet supported by Ollee, but the watch will display whatever 6-character string the phone sends it. Arthur86000's existing [FreeOllee](https://github.com/Arthur86000/FreeOllee) project has reverse-engineered the BLE packet format used to push such a string. We will reuse that format inside our own app, so the user has a single Android app that takes lat/lng and pushes either a temperature reading or the next sun event to their watch — plus a free-form custom string for experimentation.

## Goals

- Manual one-tap push from a self-contained Android app, no dependency on the official Ollee app or on the FreeOllee APK.
- Works on GrapheneOS without location permissions (user enters coords manually), AND also offers an optional "Use my location" button that prepopulates lat/lng using the platform `LocationManager` when the user has granted either precise (`ACCESS_FINE_LOCATION`) or approximate (`ACCESS_COARSE_LOCATION`) permission.
- Three send actions: **Temperature** (from Open-Meteo), **Sun Time** (next sunrise or sunset, locally computed), **Custom** (free text up to 6 chars).
- Remember the last lat/lng and last selected watch between launches.
- All formatting and protocol code unit-tested; on-watch rendering verified manually during build.

## Non-goals (explicit cuts)

- No background service. No scheduled / hourly refresh. No location-change triggers.
- No reliance on Google Play Services' Fused Location Provider. We use the platform `LocationManager` directly so the location feature works on GrapheneOS.
- Location permission is **optional** — the app fully functions with neither location permission granted; the lat/lng fields are simply manual-entry-only in that mode.
- No watch pairing UI beyond a bonded-devices list — the user pairs in Android Bluetooth settings first, same flow as FreeOllee.
- No multi-watch support — single device address persisted at a time.
- No timezone-by-coordinates lookup; we use the phone's current `ZoneId.systemDefault()`. Documented limitation; revisit if it bites.
- No map picker for coordinates.
- No support for the watch's other faces (Hour, Countdown, etc.) beyond what falls out of Custom.

## Target

- Android, Kotlin, Jetpack Compose, min SDK 31 (matches the BLUETOOTH_CONNECT / BLUETOOTH_SCAN permission model). Target SDK current at build time.
- APK installable on GrapheneOS; pairing performed in OS Bluetooth settings.

## Architecture

Single-activity app. All packages under `com.blizzardcaron.freeollee_faces`.

| Component | File | Responsibility |
|---|---|---|
| UI | `MainActivity.kt` | Compose screen: lat/lng fields, **Use my location** button, watch picker button, three send buttons, custom-text field, status line, error states. |
| Protocol | `ble/OlleeProtocol.kt` | Pure functions: `crc16(bytes): Int`, `buildPacket(value: String): ByteArray`. Ported from FreeOllee. |
| BLE link | `ble/OlleeBleClient.kt` | `suspend fun send(deviceAddress: String, value: String): Result<Unit>`. Connect-write-disconnect per push; service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`, characteristic `6e400002-b5a3-f393-e0a9-e50e24dcca9e`. |
| Weather | `weather/OpenMeteoClient.kt` | `suspend fun currentTempF(lat: Double, lng: Double): Result<Double>` using `https://api.open-meteo.com/v1/forecast?…&current=temperature_2m&temperature_unit=fahrenheit`. Plain `HttpURLConnection`, no extra dependencies. |
| Sun calc | `sun/SunCalc.kt` | NOAA Solar Position algorithm; `fun nextEvent(now: Instant, lat: Double, lng: Double, zone: ZoneId): NextEvent` returning `(kind = SUNRISE\|SUNSET, time: ZonedDateTime)`. Polar day/night → `null`. |
| Formatting | `format/DisplayFormatter.kt` | Pure functions producing the literal 6-character strings sent to the watch. |
| Location | `location/LocationSource.kt` | `suspend fun fetch(): Result<Coords>` using platform `LocationManager` (no Play Services). Tries `getCurrentLocation` on whichever providers are enabled and permitted (GPS, NETWORK, PASSIVE), with a configurable timeout; falls back to `getLastKnownLocation` if `getCurrentLocation` produces nothing. Works with FINE *or* COARSE permission — Android downgrades fix accuracy automatically when only COARSE is granted. |
| Preferences | `prefs/Prefs.kt` | SharedPreferences wrapper for `lastLat`, `lastLng`, `watchAddress`. |

**Permissions** (manifest):
- `BLUETOOTH_CONNECT` (required to read bonded device names/addresses and to perform GATT writes on Android 12+).
- `BLUETOOTH_SCAN` (declared to match FreeOllee's manifest; not strictly required for our flow since we only use bonded devices, but kept so users who paired through FreeOllee's UX have the same expected permission set).
- `INTERNET`
- `ACCESS_COARSE_LOCATION` (optional — declared in manifest; the user grants it via runtime prompt only if they tap *Use my location*).
- `ACCESS_FINE_LOCATION` (optional — same runtime gating). The runtime prompt is the standard two-option Android dialog ("Precise" / "Approximate") and we honor whichever the user grants.

No foreground service. No boot receiver. The app remains fully usable if both location permissions are denied — manual coordinate entry covers the entire feature set.

## Default 6-character output formats

These are starting points; the user explicitly accepted that final formats will be validated by photographing the watch during build and tweaking `DisplayFormatter` in one place.

**Temperature** — integer round, right-justified `F` suffix, exactly 6 chars:
- `72 → "  72 F"`
- `-12 → " -12 F"`
- `102 → " 102 F"`

**Sun time** — 12-hour, trailing `r` (sunrise) or `s` (sunset). am/pm marker (`a` / `p`) only when there is room (single-digit hours). Two-digit hours drop am/pm because there are no remaining segments:
- 6:29 AM sunrise → `6:29ar`
- 8:15 PM sunset → `8:15ps`
- 10:05 AM sunrise → `10:05r`
- 12:30 PM sunset → `12:30s`

**Custom** — `padEnd(6, ' ').take(6)`:
- `"hi"` → `"hi    "`
- `"toolong"` → `"toolon"`

## Data flow

1. **Launch.** `Prefs` loads `lastLat`, `lastLng`, `watchAddress`. UI hydrates fields and shows `Watch: <bonded name>` or `Watch: none selected`.
2. **Select watch.** Tap *Select watch* → if `BLUETOOTH_CONNECT` not granted, request it. On grant, show an `AlertDialog`-equivalent (Compose `Dialog`) listing bonded devices. Selection persists `watchAddress`.
3. **Use my location** *(optional)*. Tap *Use my location* →
   1. If neither `ACCESS_FINE_LOCATION` nor `ACCESS_COARSE_LOCATION` is granted, request both via the standard runtime prompt (Android shows the Precise/Approximate chooser); user may grant either or deny entirely.
   2. If at least one is granted, `LocationSource.fetch()` calls `getCurrentLocation` on each enabled provider in priority `GPS → NETWORK → PASSIVE`, racing them with a 10-second overall timeout. First non-null `Location` wins. If all return null, falls back to `getLastKnownLocation` on the same providers.
   3. On success: write `lat = location.latitude`, `lng = location.longitude` into the fields (also persists via `Prefs`); status reads e.g. `Got fix: 44.310610, -72.041310 (GPS, ±12 m)`.
   4. On denial, no enabled provider, or no fix within timeout: status surfaces the cause (see Error handling); fields are left as they were.
4. **Send Temperature.**
   1. Validate `lat ∈ [-90, 90]`, `lng ∈ [-180, 180]`.
   2. Persist current lat/lng.
   3. `OpenMeteoClient.currentTempF(lat, lng)` on IO dispatcher.
   4. `DisplayFormatter.temperature(tempF)` → 6-char string.
   5. `OlleeBleClient.send(watchAddress, value)`.
   6. Status line: `Sent "  72 F" to <watchName>`.
5. **Send Sun Time.**
   1. Validate + persist lat/lng.
   2. `SunCalc.nextEvent(Instant.now(), lat, lng, ZoneId.systemDefault())`.
   3. `DisplayFormatter.sunTime(event)` → 6-char string.
   4. Send via `OlleeBleClient`, same status line as above.
6. **Send Custom.**
   1. Read custom text field.
   2. `DisplayFormatter.custom(text)` → 6-char string.
   3. Send via `OlleeBleClient`.

All three send paths share one suspending function that handles the BLE connect → discover services → write → disconnect cycle and returns a `Result`. Network/IO runs in a `viewModelScope.launch(Dispatchers.IO)` (or screen-level coroutine if we skip a ViewModel for MVP).

## Error handling

Surface every failure on the status line. Add a `Toast` for transient errors (network, BLE).

| Condition | Behavior |
|---|---|
| Invalid lat/lng | Inline field error; affected send button disabled. |
| No watch selected | Send buttons disabled; status reads `Select a watch to send.` |
| `BLUETOOTH_CONNECT` denied | Status: `Permission denied — grant BLUETOOTH_CONNECT in settings.` |
| Both location permissions denied | Status: `Location permission denied — enter coordinates manually.` *Use my location* remains visible so the user can re-prompt; manual entry is unaffected. |
| Location permission granted but no provider enabled (all of GPS/Network off) | Status: `No location providers enabled — turn on GPS or network location, or enter coordinates manually.` |
| Location fix timeout (10 s) with no last-known fallback | Status: `Couldn't get a location fix — enter coordinates manually.` |
| Open-Meteo timeout / non-200 | Status + toast: `Weather fetch failed: <message>`. No BLE write. |
| BLE connect timeout (8 s) | Status + toast: `Couldn't reach watch — wake it or re-enable BT (long-press bottom-right ×2).` |
| BLE write failed (`onCharacteristicWrite` non-success) | Status: `Write failed: <status>`. |
| Sun event not in next 24 h (polar) | Status: `No sunrise/sunset in next 24h at this location.` |

## Testing

**Unit tests** (`app/src/test/java/com/blizzardcaron/freeollee_faces/`):

- `OlleeProtocolTest`
  - `crc16` against a fixture computed from FreeOllee's exact payload (`byteArrayOf(0x02, 0x2f) + "Hello ".toByteArray(Charsets.US_ASCII)`).
  - `buildPacket("Hello ")` → exact byte sequence (golden).
- `DisplayFormatterTest`
  - Temperature: 72, -12, 102, 0 → expected strings.
  - Sun time: 6:29 AM rise, 8:15 PM set, 10:05 AM rise, 12:30 PM set → expected strings.
  - Custom: `""`, `"hi"`, `"toolong"`, `"123456"` → expected strings.
- `SunCalcTest`
  - 3 fixtures from independent NOAA calculator: Greenwich on the spring equinox, a mid-latitude US city in summer, a high-latitude case with no sunset (returns `null`).

**Manual on-watch verification** (acceptance gate before merge):

1. Pair the Ollee in Android Bluetooth settings, select it in the app.
2. Send each default formatter output; photograph the LCD; tweak `DisplayFormatter` if any character renders wrong.
3. Cycle the watch face (Temperature face vs Sunrise/Sunset face) and re-send the same value to confirm whether the same 6-char string renders acceptably on both faces or whether the format needs to vary per active face (open question we explicitly defer to this step).

**Manual location verification** (on GrapheneOS, the target device):

1. Deny both location permissions → app still works manually, *Use my location* surfaces the documented denial message.
2. Grant only Approximate → tap *Use my location* and confirm a coarse fix populates the fields and is honored by Open-Meteo and SunCalc.
3. Grant Precise → confirm GPS fix populates the fields and the accuracy in the status line matches expectations (e.g. tens of meters outdoors).
4. Grant Precise but disable all location providers → confirm we surface "no providers enabled".

**No instrumented tests** for MVP — BLE and location behavior is verified manually against real hardware.

## Open items deferred to build-time

- Exact character-level format strings (resolved by on-watch verification).
- Whether to keep the `connect → write → disconnect` model or switch to a sticky GATT connection if disconnects feel slow on subsequent presses.
- Timezone-by-coordinates lookup for the sun-time path. Currently we use `ZoneId.systemDefault()` and document the limitation.

## References

- FreeOllee source (BLE protocol, packet format, CRC-16/CCITT-FALSE): https://github.com/Arthur86000/FreeOllee
- Open-Meteo current weather API: https://open-meteo.com/en/docs
- NOAA Solar Position Algorithm: https://gml.noaa.gov/grad/solcalc/calcdetails.html
