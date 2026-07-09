# Glance Removal + Idle Instruments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the standalone Instrument glance end-to-end and replace its unique value with a sensors-only live instruments row (compass + barometer, no GPS) on the idle Activity home.

**Architecture:** A new `InstrumentsProvider` seam (commonMain interface + androidMain SensorManager impl, mirroring `LocationProvider`) feeds the idle row via `AppViewModel`. The glance is then peeled out in compile-green layers: UI entry points → launcher/service plumbing → engine (`recording` collapses into `running`) → per-mode config (`ActivityMode` deleted, JSON stays legacy-tolerant).

**Tech Stack:** Kotlin Multiplatform (androidTarget-only), Compose Multiplatform, kotlinx.serialization JSON, kotlinx.coroutines StateFlow, detekt (no baseline).

**Spec:** `plans/2026-07-02-glance-removal-design.md`. Executes on `feat/activity-upgrades` (PR #35), VERSION stays 0.32.0. On-device check waived by the user (2026-07-02).

## Global Constraints

- Triple gate after every task: `./gradlew :app:testDebugUnitTest :app:detekt :app:assembleDebug` — all three must pass before commit (assembleDebug compiles androidMain, which the unit-test task alone does not).
- detekt has NO baseline: no new findings, no magic numbers; targeted `@Suppress` only with a justifying comment.
- TDD: failing test (or compile-fail RED) before implementation wherever a test can express the change.
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- No pushes 9 AM–5 PM MT Mon–Fri (local commits fine anytime).
- Saved metrics-config prefs from existing installs MUST keep decoding (legacy `glance` JSON key ignored, recording list preserved).

## Compute Offload (claude-local)

Applies across all tasks (DRY). Route by task-shape + gate; run `claude-local-brief-check BRIEF.md` before dispatching; verify with `git diff` + the triple gate — never trust self-report. Fall back to a paid subagent (haiku/sonnet) or the controller when claude-local is off/unreachable.

- **Task 1 Step 3** (`InstrumentsProvider.kt`, whole NEW file, full contents in this plan) — claude-local eligible. Gate: Task 1 tests + detekt.
- **Task 2 Step 1** (`AndroidInstrumentsProvider.kt`, whole NEW file, full contents in this plan) — claude-local eligible. Gate: assembleDebug + detekt.
- **Everything else** is multi-line edits to existing files — NOT claude-local (unreliable block-replaces); use subagents or the controller.

---

### Task 1: InstrumentsProvider seam + AppViewModel exposure

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/InstrumentsProvider.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt` (constructor ~line 73 area, near `activityLauncher`)
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt`

**Interfaces:**
- Produces: `IdleInstruments(headingDeg: Float?, pressureHpa: Double?)`; `InstrumentsProvider { val instruments: StateFlow<IdleInstruments>; fun start(); fun stop() }`; `NoopInstrumentsProvider`; on `AppViewModel`: `val instruments: StateFlow<IdleInstruments>`, `fun startInstruments()`, `fun stopInstruments()`.

- [ ] **Step 1: Write the failing test** — append to `AppViewModelTest.kt` (uses the existing `vmWith` helper? No — `vmWith` doesn't take a provider; construct via a new optional param, see Step 3, and extend `vmWith` with `instruments: InstrumentsProvider = NoopInstrumentsProvider` passed through):

```kotlin
    @Test
    fun instruments_delegate_to_the_injected_provider() = runTest(testScheduler) {
        val fake = object : com.blizzardcaron.freeolleefaces.activity.InstrumentsProvider {
            val calls = mutableListOf<String>()
            private val flow = kotlinx.coroutines.flow.MutableStateFlow(
                com.blizzardcaron.freeolleefaces.activity.IdleInstruments(headingDeg = 90f),
            )
            override val instruments = flow
            override fun start() { calls += "start" }
            override fun stop() { calls += "stop" }
        }
        val vm = vmWith(FakeWatchConnection(), Prefs(MapSettings()), instruments = fake)

        vm.startInstruments()
        vm.stopInstruments()

        assertEquals(listOf("start", "stop"), fake.calls)
        assertEquals(90f, vm.instruments.value.headingDeg)
    }
```

- [ ] **Step 2: Run to verify RED** — `./gradlew :app:compileDebugUnitTestKotlinAndroid` → FAIL: `Unresolved reference 'InstrumentsProvider'`.

- [ ] **Step 3: Implement** — new file `InstrumentsProvider.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.activity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Live phone-sensor readouts for the idle Activity home. Null = sensor absent or no reading yet. */
data class IdleInstruments(
    val headingDeg: Float? = null,
    val pressureHpa: Double? = null,
)

/**
 * Sensors-only instruments for the idle Activity home (compass azimuth + barometric pressure).
 * No GPS, no watch push, nothing recorded. Mirrors [com.blizzardcaron.freeolleefaces.location.LocationProvider]:
 * platform impls own the plumbing; [start]/[stop] bracket sensor registration.
 */
interface InstrumentsProvider {
    val instruments: StateFlow<IdleInstruments>
    fun start()
    fun stop()
}

/** Inert provider: empty readouts, control is a no-op. Default for tests and headless construction. */
object NoopInstrumentsProvider : InstrumentsProvider {
    override val instruments: StateFlow<IdleInstruments> = MutableStateFlow(IdleInstruments())
    override fun start() = Unit
    override fun stop() = Unit
}
```

In `AppViewModel.kt`: add constructor param after `activityLauncher` (line ~73):

```kotlin
    private val instrumentsProvider: InstrumentsProvider = NoopInstrumentsProvider,
```

and expose (near `activityHistory()`):

```kotlin
    /** Sensors-only idle instruments (compass + barometer) for the Activity home. */
    val instruments: StateFlow<IdleInstruments> get() = instrumentsProvider.instruments
    fun startInstruments() = instrumentsProvider.start()
    fun stopInstruments() = instrumentsProvider.stop()
```

Add imports `com.blizzardcaron.freeolleefaces.activity.IdleInstruments`, `com.blizzardcaron.freeolleefaces.activity.InstrumentsProvider`, `com.blizzardcaron.freeolleefaces.activity.NoopInstrumentsProvider` (check which are already covered by existing imports; `StateFlow` is already imported). In `AppViewModelTest.vmWith`, add `instruments: InstrumentsProvider = NoopInstrumentsProvider` parameter, passed as `instrumentsProvider = instruments`.

- [ ] **Step 4: Run the triple gate** — `./gradlew :app:testDebugUnitTest :app:detekt :app:assembleDebug` → BUILD SUCCESSFUL, new test passes.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/InstrumentsProvider.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt
git commit -m "feat(activity): InstrumentsProvider seam for sensors-only idle instruments"
```

---

### Task 2: AndroidInstrumentsProvider (SensorManager)

**Files:**
- Create: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/activity/AndroidInstrumentsProvider.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt` (`createAppViewModel`, ~line 190: add `instrumentsProvider = AndroidInstrumentsProvider(context),` after `activityStore = ...`; import `com.blizzardcaron.freeolleefaces.activity.AndroidInstrumentsProvider`)

**Interfaces:**
- Consumes: `InstrumentsProvider`, `IdleInstruments` (Task 1).
- Produces: `AndroidInstrumentsProvider(context: Context) : InstrumentsProvider`.

No unit test: thin sensor-callback glue with no branching logic worth faking (project precedent: `AndroidLocationProvider` is untested). Gate = detekt + assembleDebug.

- [ ] **Step 1: Implement** — new file:

```kotlin
package com.blizzardcaron.freeolleefaces.activity

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phone-sensor instruments: rotation vector -> compass azimuth, barometer -> hPa. A missing
 * sensor simply leaves its field null (the idle row hides that readout). [stop] clears the
 * readings so a stale heading never flashes on the next start.
 */
class AndroidInstrumentsProvider(context: Context) : InstrumentsProvider, SensorEventListener {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val rotation: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val pressure: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private val _instruments = MutableStateFlow(IdleInstruments())
    override val instruments: StateFlow<IdleInstruments> = _instruments.asStateFlow()

    override fun start() {
        rotation?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        pressure?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun stop() {
        sensorManager?.unregisterListener(this)
        _instruments.value = IdleInstruments()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(ROTATION_MATRIX_SIZE)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(ORIENTATION_SIZE)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val azimuth =
                    (Math.toDegrees(orientation[0].toDouble()).toFloat() + DEGREES_FULL) % DEGREES_FULL
                _instruments.value = _instruments.value.copy(headingDeg = azimuth)
            }
            Sensor.TYPE_PRESSURE ->
                _instruments.value = _instruments.value.copy(pressureHpa = event.values[0].toDouble())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val ROTATION_MATRIX_SIZE = 9
        const val ORIENTATION_SIZE = 3
        const val DEGREES_FULL = 360f
    }
}
```

- [ ] **Step 2: Run the triple gate** — expect BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/activity/AndroidInstrumentsProvider.kt app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat(activity): Android sensor-backed instruments provider"
```

---

### Task 3: Idle instruments row; remove the glance entry points

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityScreen.kt` (IdleContent ~line 60; ActivityScreen signature)
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt` (delete `onShowLive` field, line ~106)
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/vm/ActivityController.kt` (delete `onShowLive()`, lines ~44-51)
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/AppScreenTabs.kt` (ActivityTab: delete `livePermissionLauncher` + `showLiveWithPermission`; add instruments collection + DisposableEffect)
- Modify: `app/src/screenFixtures/kotlin/com/blizzardcaron/freeolleefaces/screens/ScreenFakes.kt` + `ScreenCases.kt` (drop `onShowLive =`, pass `instruments =`)
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/vm/ActivityControllerTest.kt` (delete the two `onShowLive_*` tests)

**Interfaces:**
- Consumes: `IdleInstruments`, `viewModel.instruments` / `startInstruments()` / `stopInstruments()` (Task 1); `ActivityMetric.ORIENTATION.human(state, unit)` / `ActivityMetric.PRESSURE.human(state, unit)` (existing — both return null when the reading is null).
- Produces: `ActivityScreen(..., instruments: IdleInstruments, ...)` new parameter; `ActivityCallbacks` WITHOUT `onShowLive`.

- [ ] **Step 1: RED via compile-fail** — delete `onShowLive` from `ActivityCallbacks` in `Callbacks.kt` and delete the `onShowLive_without_permission_does_not_launch_and_warns` + `onShowLive_with_permission_starts_live_glance` tests from `ActivityControllerTest.kt`. Run `./gradlew :app:compileDebugUnitTestKotlinAndroid` → FAIL (unresolved `onShowLive` at remaining construction sites) — this enumerates every site the next steps must fix.

- [ ] **Step 2: Implement UI** — in `ActivityScreen.kt`:
  - Add `instruments: IdleInstruments` parameter to `ActivityScreen` (after `intervalPresetsMs`; the existing `@Suppress("LongParameterList")` covers it) and thread it to `IdleContent`.
  - In `IdleContent`, REPLACE the Instrument-glance button block:

```kotlin
    OutlinedButton(onClick = callbacks.onShowLive, modifier = Modifier.fillMaxWidth()) {
        Text("Instrument glance")
    }
```

  with a call to `InstrumentsRow(instruments, unit)` placed after the recent-activities section (end of IdleContent), and add:

```kotlin
@Composable
private fun InstrumentsRow(instruments: IdleInstruments, unit: ActivityUnit) {
    // Reuse the metric formatters; both return null when the sensor has no reading yet,
    // which hides that line (and the whole row before the first sensor event).
    val state = ActivityState(headingDeg = instruments.headingDeg, pressureHpa = instruments.pressureHpa)
    val compass = ActivityMetric.ORIENTATION.human(state, unit)
    val pressure = ActivityMetric.PRESSURE.human(state, unit)
    if (compass == null && pressure == null) return
    Text("Instruments", fontWeight = FontWeight.Bold)
    compass?.let { Text("Compass: $it", style = MaterialTheme.typography.bodyMedium) }
    pressure?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
```

  Imports for `IdleInstruments` (`com.blizzardcaron.freeolleefaces.activity.IdleInstruments`) — `ActivityMetric`, `ActivityState`, `FontWeight`, `MaterialTheme` are already imported.

- [ ] **Step 3: Implement controller + tab** —
  - `ActivityController.kt`: delete the whole `onShowLive()` function (and its kdoc).
  - `AppScreenTabs.kt` `ActivityTab`: delete `livePermissionLauncher` and `showLiveWithPermission`; delete `onShowLive = showLiveWithPermission,` from the `ActivityCallbacks(...)`; add after the `pushIntervalMs` collection:

```kotlin
    val instruments by viewModel.instruments.collectAsState()
    // Sensors run only while this tab is visible AND idle; a recording start disposes the effect.
    DisposableEffect(activityState.running) {
        if (!activityState.running) viewModel.startInstruments()
        onDispose { viewModel.stopInstruments() }
    }
```

  (import `androidx.compose.runtime.DisposableEffect`), and pass `instruments = instruments,` to `ActivityScreen`.
  - `ScreenFakes.kt` / `ScreenCases.kt`: remove `onShowLive = {},`-style args; pass `instruments = IdleInstruments(headingDeg = 45f, pressureHpa = 1013.0),` where `ActivityScreen` is constructed (import `IdleInstruments`). Run Step 1's compile to find every remaining site — fix each the same way.

- [ ] **Step 4: Run the triple gate** — BUILD SUCCESSFUL, no test failures.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "feat(activity): live instruments row on the idle home; drop the glance entry points"
```

---

### Task 4: Remove the glance plumbing (launcher, service, fakes)

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionLauncher.kt` (delete `startLive()` from interface + `NoopActivitySessionLauncher`)
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/activity/AndroidActivitySessionLauncher.kt` (delete the `startLive` override, line ~10)
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionService.kt` (delete `ACTION_START_LIVE` const ~line 269, its `onStartCommand` branch ~line 81, `startLiveSession()` ~lines 106-112, and the `startLive(context)` companion helper ~line 286)
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/vm/ActivityControllerTest.kt` + `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/vm/ActivityMetricsControllerTest.kt` (each has a `FakeLauncher` with an `override fun startLive()` — delete those overrides)

**Interfaces:**
- Consumes: nothing new. Produces: `ActivitySessionLauncher` WITHOUT `startLive` — Task 5 relies on `engine.startLive` having no remaining service caller.

- [ ] **Step 1: RED via compile-fail** — delete `fun startLive()` from the `ActivitySessionLauncher` interface only; `./gradlew :app:compileDebugUnitTestKotlinAndroid` → FAIL listing every override/caller ("overrides nothing" in Noop/Android/fakes).

- [ ] **Step 2: Implement** — delete the overrides in `NoopActivitySessionLauncher`, `AndroidActivitySessionLauncher`, both test `FakeLauncher`s, and the service pieces listed above (the `// Non-recording live glance ...` comment block goes with `startLiveSession`).

- [ ] **Step 3: Run the triple gate** — BUILD SUCCESSFUL (assembleDebug is the real check here — the service is androidMain).

- [ ] **Step 4: Commit**

```bash
git add -A app/src
git commit -m "refactor(activity): remove the glance launcher/service plumbing"
```

---

### Task 5: Engine collapse — a session is always a recording

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityState.kt` (delete `recording` field)
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngine.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionService.kt` (`startSession`, ~line 94)
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityScreen.kt` (watch-status text, `RunningControls`, "Close glance" button, mode selection)
- Modify: `app/src/screenFixtures/kotlin/com/blizzardcaron/freeolleefaces/screens/ScreenFakes.kt` (line ~144: `recording = true,`)
- Tests: DELETE `app/src/commonTest/.../activity/ActivitySessionEngineLiveModeTest.kt`; modify `ActivitySessionEngineRecordTest.kt`, `ActivitySessionEngineTest.kt`, `ActivitySessionEngineGpsLockTest.kt`, `ActivitySessionEngineCycleTest.kt`, `ActivityMetricRenderTest.kt`/`ActivityMetricHumanTest.kt` (only if they set `recording =` — grep first)

**Interfaces:**
- Produces: `ActivityState` WITHOUT `recording` (use `running`); `ActivitySessionEngine` WITHOUT `startLive()`/`beginRecording()`. Task 6 relies on `activeOrder()` being the engine's single config read.

- [ ] **Step 1: RED via compile-fail** — delete `val recording: Boolean = false,` from `ActivityState` and the whole `startLive()` (engine lines ~67-81) + `beginRecording()` (~83-103) functions. `./gradlew :app:compileDebugUnitTestKotlinAndroid` → FAIL enumerating every consumer.

- [ ] **Step 2: Engine implementation** — in `ActivitySessionEngine.kt`:
  - Delete `private var recording = false` and every read/write of it:
    - `start()`: drop `recording = true`; the state line becomes `_state.value = ActivityState(running = true, selectedMetric = selectedMetric)`.
    - `ingest()`: `if (recording) points += ...` → unconditional `points += TrackPoint(...)`; drop `recording = recording,` from the `.copy(...)`.
    - `tick()`: drop `recording = recording,` from the `.copy(...)`.
    - `flush()`: `if (session != null && recording)` → `if (session != null)`.
    - `stop()`: the `if (recording) { ... }` wrapper goes — save + restore run unconditionally (the `session == null` guard already bars idle stops); drop `recording = false`.
    - `activeOrder()`:

```kotlin
    private fun activeOrder(): List<ActivityMetric> =
        config.enabledOrder(ActivityMode.RECORDING).ifEmpty { listOf(ActivityMetric.PACE) }
```

      (Task 6 drops the mode argument; keep `ActivityMode` here for now.)
  - Update the class kdoc: the engine only ever records.

- [ ] **Step 3: Service + UI implementation** —
  - `ActivitySessionService.startSession()`: the upgrade branch `if (ActivitySessionHost.isRunning) { scope.launch { engine.beginRecording() }; return }` → `if (ActivitySessionHost.isRunning) return` and update the comment above it (ACTION_START while running is now a no-op, not an upgrade).
  - `ActivityScreen.kt` `RunningContent`:
    - mode selection `val mode = if (state.recording) ...` → `for (metric in config.enabledOrder(ActivityMode.RECORDING))`.
    - watch-status text collapses to:

```kotlin
    val watchStatusText = if (!watchSelected) {
        "No watch — recording only"
    } else if (state.watchReachable) {
        "Watch: showing ${state.lastPushText ?: "…"}"
    } else {
        "Watch unreachable — recording continues"
    }
```

    - delete the trailing `if (!state.recording) { OutlinedButton(...) { Text("Close glance") } }` block.
  - `RunningControls`: `if (state.recording) { Stop } else { Record }` → always the Stop button (delete the Record branch); the pause/resume guard `if (state.recording && !state.stopping)` → `if (!state.stopping)`.
  - `ScreenFakes.kt`: delete `recording = true,` from the ActivityState fixture.

- [ ] **Step 4: Test implementation** —
  - DELETE `ActivitySessionEngineLiveModeTest.kt` (all three tests are glance-only).
  - `ActivitySessionEngineRecordTest.kt`: delete `begin_recording_upgrades_live_session`, `begin_recording_cold_starts_when_idle`, `begin_recording_does_not_leak_glance_time_or_distance`; in the two stopping tests replace `e.beginRecording()` with `e.start()`; replace any `state.value.recording` assertion with `state.value.running`.
  - `ActivitySessionEngineTest.kt`: delete `glance_does_not_auto_pause_when_stationary`; rewrite any other `startLive()` use to `start()`.
  - `ActivitySessionEngineCycleTest.kt`: delete `live_mode_cycles_orientation_altitude_pressure`.
  - `ActivitySessionEngineGpsLockTest.kt`: these pin the " GPS  " acquiring nameplate with ORIENTATION selected, which `startLive()` gave by default. Keep the coverage on the recording path: replace `e.startLive()` with `e.start()` and inject an orientation-first config into the engine constructor:

```kotlin
    metricsConfig = {
        ActivityMetricsConfig(
            recording = listOf(
                ActivityMetricItem(ActivityMetric.ORIENTATION),
                ActivityMetricItem(ActivityMetric.PRESSURE),
                ActivityMetricItem(ActivityMetric.PACE),
            ),
            glance = ActivityMetricsConfig.DEFAULT.glance,
        )
    },
```

    (Task 6 removes the `glance` argument; the executor of Task 6 sweeps this file again.) Delete `cycle_skips_disabled_glance_metric`; if a disabled-metric cycle test doesn't already exist for recording mode, rewrite it as one (same shape, recording list, disabled `ActivityMetricItem(ActivityMetric.PRESSURE, enabled = false)`).
  - Grep `recording =` / `\.recording` across `app/src/commonTest` — fix any remaining state constructions (drop the param) before running the gate.

- [ ] **Step 5: Run the triple gate** — BUILD SUCCESSFUL; expect the suite count to DROP (deleted glance tests) with zero failures.

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "refactor(activity)!: a session is always a recording — remove the glance engine paths"
```

---

### Task 6: Config collapse — delete ActivityMode and the glance metric list

**Files:**
- Delete: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMode.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetricsConfig.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetricsJson.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetricsRepository.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/vm/ActivityController.kt` (metric methods, lines ~62-67)
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt` (`ActivityMetricsConfigCallbacks`, line ~126)
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityMetricsConfigScreen.kt` (single section)
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngine.kt` (`activeOrder()`), `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityScreen.kt` (`enabledOrder` call), `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/AppScreenTabs.kt` (`ActivityMetricsConfigTab` callbacks)
- Tests: `ActivityMetricsConfigTest.kt`, `ActivityMetricsJsonTest.kt`, `ActivityMetricsRepositoryTest.kt`, `ActivityMetricsControllerTest.kt`, `ActivitySessionEngineGpsLockTest.kt` (drop the `glance =` argument from Task 5)

**Interfaces:**
- Produces: `ActivityMetricsConfig(recording: List<ActivityMetricItem>)` with mode-free ops `enabledOrder(): List<ActivityMetric>`, `moveUp(index: Int)`, `moveDown(index: Int)`, `setEnabled(metric, enabled)`; repository/controller/callbacks lose their `mode` parameters; `ActivityMetricsJson.encode` writes `{"recording":[...]}` only; decode ignores a legacy `"glance"` key.

- [ ] **Step 1: Write the migration pin test FIRST** (stays green through the refactor — it must compile against both shapes, so it only touches `config.recording`). Append to `ActivityMetricsJsonTest.kt`:

```kotlin
    @Test
    fun decode_tolerates_a_legacy_payload_with_a_glance_key() {
        val legacy = """{"recording":[{"m":"DISTANCE","e":true},{"m":"PACE","e":false}],""" +
            """"glance":[{"m":"ORIENTATION","e":true}]}"""
        val config = ActivityMetricsJson.decode(legacy)
        assertEquals(ActivityMetric.DISTANCE, config.recording.first().metric)
        assertEquals(false, config.recording.first { it.metric == ActivityMetric.PACE }.enabled)
    }
```

  Run it → PASS today (that's the point: it pins what must survive).

- [ ] **Step 2: RED via compile-fail** — delete `ActivityMode.kt`; compile → FAIL enumerating consumers.

- [ ] **Step 3: Implement the model** — `ActivityMetricsConfig.kt` becomes:

```kotlin
package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.timer.Reorder

/** One configurable metric row: the metric and whether it is enabled (cycled + shown). */
data class ActivityMetricItem(val metric: ActivityMetric, val enabled: Boolean = true)

/**
 * The user's recording-metric configuration: an ordered, individually-toggleable list.
 * All ops are pure (return a new config). Invariant: always >= 1 enabled metric.
 */
data class ActivityMetricsConfig(val recording: List<ActivityMetricItem>) {

    fun enabledOrder(): List<ActivityMetric> = recording.filter { it.enabled }.map { it.metric }

    fun moveUp(index: Int): ActivityMetricsConfig = copy(recording = Reorder.moveUp(recording, index))

    fun moveDown(index: Int): ActivityMetricsConfig = copy(recording = Reorder.moveDown(recording, index))

    fun setEnabled(metric: ActivityMetric, enabled: Boolean): ActivityMetricsConfig {
        // Guard the >= 1 invariant: refuse to disable the only remaining enabled metric.
        val wouldViolateInvariant = !enabled &&
            recording.count { it.enabled } <= 1 &&
            recording.any { it.metric == metric && it.enabled }
        if (wouldViolateInvariant) {
            return this
        }
        return copy(recording = recording.map { if (it.metric == metric) it.copy(enabled = enabled) else it })
    }

    companion object {
        val RECORDING_METRICS = listOf(
            ActivityMetric.PACE,
            ActivityMetric.AVG_PACE,
            ActivityMetric.DISTANCE,
            ActivityMetric.TIME,
            ActivityMetric.ORIENTATION,
            ActivityMetric.ALTITUDE,
            ActivityMetric.PRESSURE,
        )

        val DEFAULT = ActivityMetricsConfig(recording = RECORDING_METRICS.map { ActivityMetricItem(it) })
    }
}
```

  `ActivityMetricsJson.kt`: `encode` drops the `put("glance", ...)` line; `decode` drops the `glance = merge(...)` argument (the `"glance"` key in stored JSON is simply never read — that IS the migration). Update the object kdoc (canonical set is `RECORDING_METRICS` only).
  `ActivityMetricsRepository.kt`:

```kotlin
    fun moveUp(index: Int) = save(get().moveUp(index))

    fun moveDown(index: Int) = save(get().moveDown(index))

    fun setEnabled(metric: ActivityMetric, enabled: Boolean) = save(get().setEnabled(metric, enabled))
```

- [ ] **Step 4: Implement consumers** —
  - `ActivityController.kt`: `moveMetricUp(index: Int)`, `moveMetricDown(index: Int)`, `setMetricEnabled(metric, enabled)` — delegate without mode.
  - `Callbacks.kt` `ActivityMetricsConfigCallbacks`: `onMoveUp: (Int) -> Unit`, `onMoveDown: (Int) -> Unit`, `onToggle: (ActivityMetric, Boolean) -> Unit` (drop the mode params; `onBack` unchanged).
  - `ActivityMetricsConfigScreen.kt`: one `MetricSection("Recording", config, unit, callbacks)`; `MetricSection`/`MetricRow` lose their `mode` params; `config.forMode(mode)` → `config.recording`; callback invocations drop `mode`.
  - `AppScreenTabs.kt` `ActivityMetricsConfigTab`: lambdas drop the mode arg (`onMoveUp = { i -> viewModel.activity.moveMetricUp(i); revision++ }` etc.).
  - `ActivitySessionEngine.activeOrder()`: `config.enabledOrder().ifEmpty { listOf(ActivityMetric.PACE) }`.
  - `ActivityScreen.kt` `RunningContent`: `config.enabledOrder()`.
  - Tests: drop mode args everywhere; delete glance-mode-specific cases (e.g. glance reorder/toggle variants); `ActivitySessionEngineGpsLockTest` drops `glance = ...` from Task 5's injected config.

- [ ] **Step 5: Run the triple gate** — BUILD SUCCESSFUL, migration pin test still green.

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "refactor(activity)!: recording-only metrics config — ActivityMode deleted, legacy JSON tolerated"
```

---

### Task 7: Docs + final sweep

**Files:**
- Modify: `README.md` (line ~62 mentions "instrument glance one tap away")

**Interfaces:** none.

- [ ] **Step 1: README** — rewrite the Activity-mode paragraph's glance sentence to describe the replacement, e.g.: "the idle Activity screen shows live phone instruments (compass + barometer) with no recording and no GPS". Keep the surrounding claims accurate.

- [ ] **Step 2: Sweep** — all must return zero matches:

```bash
grep -rin "glance" app/src README.md
grep -rn "startLive\|ActivityMode\|GLANCE_METRICS\|beginRecording\|onShowLive" app/src
```

  Any hit = a missed consumer; fix it in the task that owned that file.

- [ ] **Step 3: Full triple gate** — BUILD SUCCESSFUL.

- [ ] **Step 4: Commit + push** (check the push window first: no pushes 9 AM–5 PM MT Mon–Fri):

```bash
git add README.md
git commit -m "docs: idle instruments replace the glance in the Activity mode description"
git push
```
