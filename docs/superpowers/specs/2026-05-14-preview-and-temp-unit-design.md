# Preview-Before-Send + Temperature Unit — Design

**Date:** 2026-05-14
**Status:** Approved by user; ready for implementation planning.
**Predecessor spec:** `docs/superpowers/specs/2026-05-14-freeollee-faces-app-design.md`

## Problem

In the shipped v0.1 app each send button (`Send temperature`, `Send next sun event`) does fetch-then-write in one tap. The user wants to see what's about to land on the watch *before* it does, and to do the temperature reading in either °F or °C — defaulting to °F, persisted across launches. Both changes shift the main screen from "three send buttons" toward "two always-on previews + one custom send" with a unit selector at the top.

## Goals

- On launch, automatically fetch the device's location (only if permission already granted) and produce two previews — current temperature and next sunrise/sunset — without any tap from the user.
- Each preview displays both the literal 6-character payload that would go to the watch *and* a human-readable line so the user can sanity-check format quirks at a glance.
- The user picks which preview to push via a per-card **Send** button; the custom-text send path is unchanged and remains below the previews.
- A `°F | °C` segmented control at the top of the screen flips the temperature unit, persists the choice, and triggers a re-fetch of the temperature preview in the new unit.
- Default temperature unit is **Fahrenheit**.

## Non-goals (explicit cuts)

- **No automatic timed refresh.** Previews refresh on launch, on a manual *Refresh* button, on unit toggle, and on valid coord edits (debounced 500 ms). Cron-like background refresh is deferred — captured in [Open follow-ups](#open-follow-ups).
- No "send both" combined action; the user picks one preview at a time.
- No precipitation, conditions, or any Open-Meteo fields beyond `temperature_2m`.
- No first-launch location permission prompt. We never auto-request — only the explicit **Use my location** button can prompt. (The whole reason this app exists is to *not* be opinionated about location.)
- No new on-watch face support. Both previews still produce a 6-character string in the same encoding the v0.1 app uses; only the unit character changes between `F` and `C`.

## User flow

1. App launches → `Prefs` loads `lastLat`, `lastLng`, `watchAddress`, `tempUnit`. Both preview cards render in a `Loading` state.
2. `LaunchedEffect(Unit)`:
   1. If at least one of `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` is granted, fire `LocationSource.fetch()` in the background (10 s timeout). On success, overwrite `lat`/`lng` and persist.
   2. If `state.lat`/`state.lng` parse to valid doubles (from the fix, from Prefs, or — eventually — from manual input), call `refreshPreviews(lat, lng, unit, …)`.
3. `refreshPreviews` runs two coroutines in parallel:
   - `OpenMeteoClient.currentTemp(lat, lng, unit)` → temperature preview.
   - `SunCalc.nextEvent(now, lat, lng, systemDefaultZone)` → sun preview.
4. As each finishes, its card transitions from `Loading` to `Ready` (with payload + human line) or `Error` (with a recovery message and a Retry link).
5. The user taps a card's **Send** button. The 6-character payload is written via `OlleeBleClient.send`. Status line at the bottom of the screen reports outcome.

Edits that trigger another `refreshPreviews`:

- Tapping **Refresh** in the title row.
- Toggling the `°F | °C` segmented control (re-fires temperature only; sun is unaffected).
- Editing the lat/lng fields to a new valid pair (debounced 500 ms).

## State shape

`MainScreenState` (new and changed fields):

| Field | Type | Purpose |
|---|---|---|
| `tempUnit` | `TempUnit` enum (`FAHRENHEIT`, `CELSIUS`) | Hydrated from Prefs at launch; persisted on toggle. |
| `tempPreview` | `PreviewState` | One of `Loading`, `Ready(payload: String, human: String, raw: Double)`, `Error(message: String)`. |
| `sunPreview` | `PreviewState` | Same shape, plus a `NoEvent` variant for polar day/night. |
| `sending` | `Boolean` | Only one BLE write at a time; both cards' send buttons disable while true. |

Removed: `latLngValid` (derived; replaced by `Error` previews when coords don't parse).

## Component changes

| File | Change |
|---|---|
| `format/TempUnit.kt` *(new)* | `enum class TempUnit(val symbol: Char, val openMeteoParam: String) { FAHRENHEIT('F', "fahrenheit"), CELSIUS('C', "celsius") }` |
| `prefs/Prefs.kt` | Add `var tempUnit: TempUnit`. Backed by a String pref. Default `FAHRENHEIT`. |
| `weather/OpenMeteoClient.kt` | Refactor `currentTempF(lat, lng)` → `currentTemp(lat, lng, unit: TempUnit)`. URL builder is extracted into a pure `buildUrl(lat, lng, unit): URL` helper so the unit param is unit-testable without HTTP. The pure JSON parser is unchanged. |
| `format/DisplayFormatter.kt` | `temperature(value: Double, unit: TempUnit): String` — `%4d ${unit.symbol}`. The existing single-arg `temperature(value: Double)` overload remains for backward compatibility, defaulting to `FAHRENHEIT`. |
| `ui/MainScreen.kt` | New layout: title row with **Refresh** + `°F / °C` `SegmentedButton`s; two `Card`s (temperature, sun) each with preview text + per-card Send + Retry; existing custom section below; status line at the bottom. |
| `MainActivity.kt` | New `LaunchedEffect(Unit)` launches the auto-fetch + parallel-refresh flow. `refreshPreviews` is a single helper invoked by launch, Refresh, unit toggle, and debounced coord edits. Each invocation creates a fresh `Job` so toggling unit during an in-flight fetch cancels the stale request. |

## Default 6-char output formats (unchanged from v0.1, plus °C)

Temperature, integer round, right-justified `F` *or* `C` suffix:

- 72 °F → `"  72 F"`
- −12 °F → `" -12 F"`
- 102 °F → `" 102 F"`
- 22 °C → `"  22 C"`
- −12 °C → `" -12 C"`

Sun time (unchanged from v0.1): `6:29ar`, `8:15ps`, `10:05r`, `12:30s`.

## Error handling

| Condition | Behavior |
|---|---|
| No location permission AND no saved coords AND no manual input | Both previews → `Error("Enter coordinates manually to see previews")`. Manual lat/lng fields are the recovery path. **Use my location** button still works for explicit grant. |
| Permission denied at runtime | Silent. We never auto-prompt at launch. Status line reads `Using saved coordinates`. |
| Temp fetch fails (timeout / non-200) | Temp card → `Error("Weather fetch failed: <message>")` with a Retry link. Sun card unaffected. |
| Sun-calc returns null (polar) | Sun card → `NoEvent` rendered as `"No sunrise/sunset in next 24 h."` Sun card's Send button disabled. |
| BLE send fails | Bottom status line: `Send failed: <message>`. Same as v0.1. |
| Unit toggled while temp fetch is in flight | The stale coroutine is cancelled (each `refreshPreviews` invocation creates a fresh `Job`); only the latest result reaches state. |
| Invalid manual lat/lng | Both previews → `Error("Invalid coordinates")`. Send buttons disabled until coords parse cleanly. |

## Testing

**Unit tests** (additive — existing 26 keep passing unchanged):

- `DisplayFormatterTest` — 3 new cases:
  - `temperature(22.0, CELSIUS)` → `"  22 C"`
  - `temperature(-12.0, CELSIUS)` → `" -12 C"`
  - `temperature(22.0)` (default overload) → `"  22 F"` (regression guard)
- `OpenMeteoClientUrlTest` *(new)* — tests for an extracted pure `buildUrl(lat, lng, unit)` helper:
  - `buildUrl(44.31, -72.04, FAHRENHEIT)` contains `temperature_unit=fahrenheit`
  - `buildUrl(44.31, -72.04, CELSIUS)` contains `temperature_unit=celsius`
  - Lat/lng land in the right query params

**Manual on-device verification** (added to the existing verification report):

- Toggle `°F → °C`: temp preview re-fetches and re-formats; persists across app restart.
- Permission denied + saved coords: previews populate without any prompt; status line reads `Using saved coordinates`.
- `°C` payload renders cleanly on the watch's temperature face (verify the `C` character is distinguishable from `F` on the LCD; tweak `DisplayFormatter` if not).
- Refresh button re-runs both fetches.
- Invalid lat/lng: both cards show `Error` and send buttons disable.

## Open follow-ups

- **Auto refresh on a timer.** The user has explicitly noted they may want a periodic background refresh (e.g. every 15 min the app is in the foreground). Deferred for now; doing it well needs a `DisposableEffect` + lifecycle-aware coroutine, possibly an interval setting. Revisit after the v0.2 ship and on-device verification.
- **Per-card timestamps.** Showing "as of HH:MM" under each preview becomes useful once the auto-refresh lands, but is overkill now when the only refresh sources are explicit.
- **°C / °F symbol on the LCD itself.** v0.1 chose `F` over `°F` because of segment-rendering uncertainty. The same logic applies to `C`. If on-device verification finds the `°` glyph would render acceptably, the formatter is the single place to add it later.

## References

- v0.1 design spec: `docs/superpowers/specs/2026-05-14-freeollee-faces-app-design.md`
- v0.1 implementation plan: `docs/superpowers/plans/2026-05-14-freeollee-faces-app.md`
- Open-Meteo `temperature_unit` parameter: `https://open-meteo.com/en/docs`
