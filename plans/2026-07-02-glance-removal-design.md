# Design: Remove the Instrument glance; live instruments on the idle Activity home

Date: 2026-07-02 · Target: PR #35 (`feat/activity-upgrades`, v0.32.0) — user decision; no separate branch.

## Goal

The standalone Instrument glance duplicates recording mode (its metrics — orientation, altitude,
pressure — are a strict subset of `RECORDING_METRICS`). Remove it end-to-end. Its one unique value
— looking at instruments without recording — moves to the idle Activity home as a **sensors-only**
live row: compass heading + barometric pressure from phone sensors. No GPS, no watch push, nothing
recorded, no configuration. Altitude stays recording-only (needs GPS).

## Add

- **`InstrumentsProvider`** (commonMain interface, mirroring `LocationProvider`):
  `start()` / `stop()`, exposing a `StateFlow<IdleInstruments>` where
  `IdleInstruments(headingDeg: Float?, pressureHpa: Double?)` — null when the sensor is absent
  or hasn't reported yet.
- **`AndroidInstrumentsProvider`** (androidMain): `SensorManager` — rotation-vector → azimuth
  degrees; `TYPE_PRESSURE` → hPa. Missing sensor ⇒ that field stays null (row hides the readout).
- **Idle Activity home row**: compact "Instruments" section on `IdleContent` showing Compass and
  Pressure via the existing human-format helpers. Sampling runs only while the Activity tab is
  visible AND idle: `DisposableEffect` in `ActivityTab` keyed on `activityState.running` —
  start when idle-visible, stop on dispose or when a recording starts.
- Injection follows the `LocationProvider` pattern: constructed in `MainActivity.createAppViewModel`,
  injected into `AppViewModel`, which exposes the provider's `StateFlow` + start/stop;
  `ActivityTab` collects it and drives the lifecycle via its `DisposableEffect`.

## Remove

- **UI** (`ActivityScreen`): "Instrument glance" button, "Close glance" button, glance branches in
  the watch-status text, `ActivityMode` selection in `RunningContent`.
- **Controller/tabs**: `ActivityController.onShowLive` + its snackbar gate;
  `AppScreenTabs.ActivityTab`'s `livePermissionLauncher` + `showLiveWithPermission`.
- **Launcher/service**: `ActivitySessionLauncher.startLive`, `AndroidActivitySessionLauncher.startLive`,
  `ActivitySessionService.ACTION_START_LIVE` / `startLiveSession()` / `startLive(context)`.
- **Engine** (`ActivitySessionEngine`): `startLive()`; `beginRecording()` collapses to a plain cold
  start (the glance→record upgrade path and its fresh-session guard go away); the
  `recording` field and `ActivityState.recording` collapse into `running` (a session is always a
  recording now) — every `state.recording` consumer switches to `running`. The glance-only
  auto-pause guard (`autoPause = null` in `startLive`) disappears with `startLive`.
- **Model/config**: `ActivityMode` enum; `ActivityMetricsConfig.glance`, `GLANCE_METRICS`, the
  `mode` parameters on config/repo methods (`enabledOrder`, `moveUp/Down`, `setEnabled`,
  `withMode`); `ActivityMetricsConfigScreen` becomes single-section (Recording), mode params gone;
  `ActivityController` metric methods drop `mode`.
- **Docs**: README glance mentions → describe the idle instruments row.
- **Tests**: glance-specific tests deleted (`begin_recording_upgrades_live_session`,
  `begin_recording_does_not_leak_glance_time_or_distance`, `glance_does_not_auto_pause_when_stationary`,
  `onShowLive_*`, glance-mode config/JSON/screen tests); the seam behaviors they guarded either
  vanish with the code or are re-pinned recording-only.

## Migration / compatibility

`ActivityMetricsJson` currently persists `{recording, glance}`. New shape encodes `recording` only;
the decoder must tolerate the legacy `glance` key (keep `ignoreUnknownKeys` or an optional ignored
field) so existing installs keep their recording order/toggles. A regression test decodes a legacy
payload.

## New tests

- `AndroidInstrumentsProvider` is thin (sensor callbacks) — logic-free; the commonMain seam is
  tested with a fake provider: idle row state maps heading/pressure → formatted strings, nulls hide.
- Legacy metrics-config JSON decodes (glance key ignored, recording preserved).
- Engine: `beginRecording` cold-start behavior re-pinned without the upgrade path;
  `ActivityState.recording` removal ripples through existing tests (mechanical).

## Verification

- Triple gate (tests / detekt / assembleDebug) after each task.
- On-device check waived by the user for this change (2026-07-02); the walk-verified GPS/pause
  seams are untouched. Static check before merge: `grep -ri glance app/src` → 0.

## Risks

- Engine simplification touches walk-verified code; the user waived a device re-check —
  the unit suite re-pins the recording seams instead.
- Phones without a barometer: pressure readout hides (null), compass remains.

## Success criteria

No glance references remain; idle home shows live instruments; recording flow byte-identical on
the watch; legacy config prefs load; all gates green.
