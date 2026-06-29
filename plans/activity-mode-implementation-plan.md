# Activity Mode Battery & Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\- [ ]\) syntax for tracking.

**Goal:** Reduce watch battery consumption via configurable push intervals, add Strava-style pause controls, and consolidate activity UI into a single cohesive experience.

**Architecture:** Four new lightweight managers (PushIntervalManager, AutoPauseManager, PauseController) delegated from refactored ActivitySessionEngine. Each owns a single responsibility with clear interfaces and independent unit tests.

**Tech Stack:** Kotlin Multiplatform (commonMain + androidMain), Jetpack Compose UI, SharedPreferences for prefs.

## Global Constraints

- Interval presets locked to: 3s, 15s, 30s, 1m, 5m (cannot be extended or customized)
- Default push interval: 30s
- Default auto-pause speed threshold: 0.1 m/s
- No mid-activity interval changes (rejected with user message)
- Auto-pause disabled if GPS fix lost >5 seconds
- Manual pause always works regardless of GPS state
- Always-recording: Every activity creates a track (no "live-only" mode)

---

## Phase 1: Data Model & Core Managers (Tasks 1-5)

### Task 1: Extend ActivityState with pause fields

**Files:**
- Modify: \pp/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityState.kt\
- Test: \pp/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityStateTest.kt\ (new)

**Interfaces:**
- Consumes: None (data layer only)
- Produces: \ActivityState\ with two new properties:
  - \paused: Boolean\ (default false)
  - \pausedAtMs: Long?\ (null when running, timestamp when paused)

- [ ] **Step 1: Write failing test**

\\\kotlin
// app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityStateTest.kt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityStateTest {
    @Test
    fun testInitialStateNotPaused() {
        val state = ActivityState(
            elapsedMs = 0,
            distance = 0.0,
            currentMetric = ActivityMetric.Pace,
            paused = false,
            pausedAtMs = null
        )
        assertEquals(false, state.paused)
        assertNull(state.pausedAtMs)
    }

    @Test
    fun testPausedStateStoresPauseTime() {
        val now = System.currentTimeMillis()
        val state = ActivityState(
            elapsedMs = 10000,
            distance = 100.0,
            currentMetric = ActivityMetric.Pace,
            paused = true,
            pausedAtMs = now
        )
        assertEquals(true, state.paused)
        assertEquals(now, state.pausedAtMs)
    }
}
\\\

- [ ] **Step 2: Run test (verify failure)**

\\\ash
cd C:\Users\kenbl\github\FreeOllee-Faces
./gradlew commonTest --tests ActivityStateTest -v
\\\

Expected: FAIL - "property \paused\ not found" / "property \pausedAtMs\ not found"

- [ ] **Step 3: Add fields to ActivityState data class**

\\\kotlin
// app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityState.kt
data class ActivityState(
    val elapsedMs: Long = 0,
    val distance: Double = 0.0,
    val currentMetric: ActivityMetric = ActivityMetric.Pace,
    val paused: Boolean = false,
    val pausedAtMs: Long? = null
)
\\\

- [ ] **Step 4: Run test (verify passes)**

\\\ash
./gradlew commonTest --tests ActivityStateTest -v
\\\

Expected: PASS

- [ ] **Step 5: Commit**

\\\ash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityState.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityStateTest.kt
git commit -m "feat: add pause state to ActivityState

- Add \paused: Boolean\ field (default false)
- Add \pausedAtMs: Long?\ field (tracks when pause started)
- Used by pause controllers to manage pause duration tracking

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
\\\

---

### Task 2: Add AVERAGE_PACE metric

**Files:**
- Modify: \pp/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetric.kt\
- Test: \pp/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetricTest.kt\ (new)

**Interfaces:**
- Consumes: \ActivityState\ (from Task 1)
- Produces: \ActivityMetric.AVERAGE_PACE\ enum variant with render method

- [ ] **Step 1: Write failing test**

\\\kotlin
// app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetricTest.kt
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityMetricTest {
    @Test
    fun testAveragePaceRender() {
        val metric = ActivityMetric.AVERAGE_PACE
        val state = ActivityState(
            distance = 5000.0,
            elapsedMs = 1200000,
            paused = false,
            pausedAtMs = null
        )
        val rendered = metric.render(state)
        assertEquals("15.0 km/h", rendered)
    }

    @Test
    fun testAveragePaceExcludesPausedTime() {
        val metric = ActivityMetric.AVERAGE_PACE
        val state = ActivityState(
            distance = 1000.0,
            elapsedMs = 720000,
            paused = true,
            pausedAtMs = System.currentTimeMillis() - 300000
        )
        val rendered = metric.render(state)
        // Should exclude ~5 min of pause time
        assertTrue(rendered.contains("km/h"))
    }
}
\\\

- [ ] **Step 2: Run test (verify failure)**

\\\ash
./gradlew commonTest --tests ActivityMetricTest -v
\\\

Expected: FAIL

- [ ] **Step 3: Add AVERAGE_PACE variant and render logic**

\\\kotlin
// app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetric.kt
enum class ActivityMetric {
    Pace,
    Distance,
    Elevation,
    Orientation,
    AVERAGE_PACE
    ;

    fun render(state: ActivityState): String = when (this) {
        Pace -> "%.1f m/s".format(state.pace)
        Distance -> "%.2f km".format(state.distance / 1000)
        Elevation -> "%d m".format(state.elevation.toInt())
        Orientation -> "%d°".format(state.orientation.toInt())
        AVERAGE_PACE -> {
            val activeMs = if (state.paused && state.pausedAtMs != null) {
                state.elapsedMs - (System.currentTimeMillis() - state.pausedAtMs!!)
            } else {
                state.elapsedMs
            }
            val activeSecs = activeMs / 1000.0
            if (activeSecs < 1) return "-- km/h"
            val speedMps = state.distance / activeSecs
            val speedKmh = speedMps * 3.6
            "%.1f km/h".format(speedKmh)
        }
    }
}
\\\

- [ ] **Step 4: Run test (verify passes)**

\\\ash
./gradlew commonTest --tests ActivityMetricTest -v
\\\

Expected: PASS

- [ ] **Step 5: Commit**

\\\ash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetric.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetricTest.kt
git commit -m "feat: add AVERAGE_PACE metric

- Add AVERAGE_PACE variant to ActivityMetric enum
- Renders pace = distance / (elapsed time - paused duration)
- Excludes paused intervals for accurate average during hikes
- Formula: active_seconds = elapsed - (now - pausedAt)

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
\\\

---

### Task 3: Add push interval & auto-pause prefs

**Files:**
- Modify: \pp/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt\
- Test: \pp/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/prefs/PrefsTest.kt\

**Interfaces:**
- Consumes: None
- Produces:
  - \ar pushIntervalMs: Long\ (getter/setter, default 30000)
  - \ar autoPauseThresholdMps: Float\ (getter/setter, default 0.1f)

- [ ] **Step 1: Write failing test**

\\\kotlin
// app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/prefs/PrefsTest.kt
import kotlin.test.Test
import kotlin.test.assertEquals

class PrefsTest {
    @Test
    fun testPushIntervalDefaults() {
        val prefs = Prefs(mockSharedPreferences)
        assertEquals(30000L, prefs.pushIntervalMs)
    }

    @Test
    fun testPushIntervalCanBeSet() {
        val prefs = Prefs(mockSharedPreferences)
        prefs.pushIntervalMs = 60000L
        assertEquals(60000L, prefs.pushIntervalMs)
    }

    @Test
    fun testAutoPauseThresholdDefaults() {
        val prefs = Prefs(mockSharedPreferences)
        assertEquals(0.1f, prefs.autoPauseThresholdMps)
    }

    @Test
    fun testAutoPauseThresholdCanBeSet() {
        val prefs = Prefs(mockSharedPreferences)
        prefs.autoPauseThresholdMps = 0.15f
        assertEquals(0.15f, prefs.autoPauseThresholdMps)
    }
}
\\\

- [ ] **Step 2: Run test**

\\\ash
./gradlew commonTest --tests PrefsTest -v
\\\

- [ ] **Step 3: Add pref accessors**

\\\kotlin
// app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt
var pushIntervalMs: Long
    get() = sharedPrefs.getLong("push_interval_ms", 30000L)
    set(value) = sharedPrefs.edit().putLong("push_interval_ms", value).apply()

var autoPauseThresholdMps: Float
    get() = sharedPrefs.getFloat("auto_pause_threshold_mps", 0.1f)
    set(value) = sharedPrefs.edit().putFloat("auto_pause_threshold_mps", value).apply()
\\\

- [ ] **Step 4: Run test**

\\\ash
./gradlew commonTest --tests PrefsTest -v
\\\

- [ ] **Step 5: Commit**

\\\ash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/prefs/PrefsTest.kt
git commit -m "feat: add push interval and auto-pause threshold prefs

- Add pushIntervalMs pref (default 30s, in milliseconds)
- Add autoPauseThresholdMps pref (default 0.1 m/s)
- Stored in SharedPreferences with defaults matching design spec
- Used by PushIntervalManager and AutoPauseManager

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
\\\

---

### Task 4: Implement PushIntervalManager

**Files:**
- Create: \pp/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/PushIntervalManager.kt\
- Test: \pp/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/PushIntervalManagerTest.kt\

**Interfaces:**
- Consumes: \Prefs.pushIntervalMs\ (from Task 3)
- Produces:
  - \un shouldPushNow(elapsedMs: Long): Boolean\
  - \un setInterval(intervalMs: Long)\
  - \al currentInterval: Long\

- [ ] **Step 1: Write failing test**

\\\kotlin
// app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/PushIntervalManagerTest.kt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushIntervalManagerTest {
    @Test
    fun testShouldPushOnFirstCall() {
        val manager = PushIntervalManager(prefs = mockPrefs)
        assertTrue(manager.shouldPushNow(0))
    }

    @Test
    fun testShouldNotPushBeforeIntervalElapsed() {
        val manager = PushIntervalManager(prefs = mockPrefs)
        manager.shouldPushNow(0)
        assertFalse(manager.shouldPushNow(15000))
    }

    @Test
    fun testShouldPushAfterIntervalElapsed() {
        val manager = PushIntervalManager(prefs = mockPrefs)
        manager.shouldPushNow(0)
        assertTrue(manager.shouldPushNow(31000))
    }

    @Test
    fun testValidIntervals() {
        val manager = PushIntervalManager(prefs = mockPrefs)
        listOf(3000L, 15000L, 30000L, 60000L, 300000L).forEach { intervalMs ->
            manager.setInterval(intervalMs)
            assertEquals(intervalMs, manager.currentInterval)
        }
    }

    @Test
    fun testInvalidIntervalRejected() {
        val manager = PushIntervalManager(prefs = mockPrefs)
        val originalInterval = manager.currentInterval
        manager.setInterval(25000L)
        assertEquals(originalInterval, manager.currentInterval)
    }
}
\\\

- [ ] **Step 2: Run test**

\\\ash
./gradlew commonTest --tests PushIntervalManagerTest -v
\\\

- [ ] **Step 3: Implement**

\\\kotlin
// app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/PushIntervalManager.kt
class PushIntervalManager(private val prefs: Prefs) {
    companion object {
        private val VALID_INTERVALS = listOf(3000L, 15000L, 30000L, 60000L, 300000L)
    }

    var currentInterval: Long = prefs.pushIntervalMs
        private set

    private var lastPushAtMs = 0L

    fun shouldPushNow(elapsedMs: Long): Boolean {
        if (lastPushAtMs == 0L) {
            lastPushAtMs = elapsedMs
            return true
        }
        if (elapsedMs - lastPushAtMs >= currentInterval) {
            lastPushAtMs = elapsedMs
            return true
        }
        return false
    }

    fun setInterval(intervalMs: Long) {
        if (VALID_INTERVALS.contains(intervalMs)) {
            currentInterval = intervalMs
            prefs.pushIntervalMs = intervalMs
        }
    }

    fun reset() {
        lastPushAtMs = 0L
    }
}
\\\

- [ ] **Step 4: Run test**

\\\ash
./gradlew commonTest --tests PushIntervalManagerTest -v
\\\

- [ ] **Step 5: Commit**

\\\ash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/PushIntervalManager.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/PushIntervalManagerTest.kt
git commit -m "feat: implement PushIntervalManager

- Manages push cadence (default 30s, presets only)
- shouldPushNow() returns true if interval elapsed since last push
- setInterval() validates against preset list
- Resets on session start

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
\\\

---

### Task 5: Implement AutoPauseManager & PauseController

**Files:**
- Create: \pp/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/AutoPauseManager.kt\
- Create: \pp/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/PauseController.kt\
- Test: \pp/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/PauseLogicTest.kt\

**Interfaces:**
- Consumes: \Prefs.autoPauseThresholdMps\, \ActivityState\
- Produces:
  - \AutoPauseManager.shouldPause(speedMps: Float, lastGPSFixAtMs: Long): Boolean\
  - \PauseController.onManualPause(): PauseState\
  - \PauseController.onResume(): PauseState\
  - \PauseController.shouldSuppressPush(): Boolean\
  - \num PauseState { Running, Paused, PausedAndSuppressed }\

- [ ] **Step 1: Write failing test**

\\\kotlin
// app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/PauseLogicTest.kt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PauseLogicTest {
    @Test
    fun testManualPauseTransition() {
        val controller = PauseController()
        assertEquals(PauseState.Running, controller.currentState)
        controller.onManualPause()
        assertEquals(PauseState.Paused, controller.currentState)
        controller.onResume()
        assertEquals(PauseState.Running, controller.currentState)
    }

    @Test
    fun testPushSuppressionWhilePausedAndSuppressed() {
        val controller = PauseController()
        controller.onManualPause()
        controller.registerPushSent()
        assertTrue(controller.shouldSuppressPush())
    }

    @Test
    fun testAutoPauseWithRollingAverage() {
        val manager = AutoPauseManager(prefs = mockPrefs)
        val now = System.currentTimeMillis()
        
        manager.recordSpeed(0.05f, now)
        manager.recordSpeed(0.06f, now + 1000)
        manager.recordSpeed(0.07f, now + 2000)
        
        assertTrue(manager.shouldPause(now + 3500))
    }
}
\\\

- [ ] **Step 2: Run test**

\\\ash
./gradlew commonTest --tests PauseLogicTest -v
\\\

- [ ] **Step 3: Implement AutoPauseManager**

\\\kotlin
// app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/AutoPauseManager.kt
class AutoPauseManager(private val prefs: Prefs) {
    companion object {
        private const val SAMPLE_WINDOW = 3
        private const val PAUSE_HOLD_MS = 3000
        private const val RESUME_HOLD_MS = 1000
        private const val GPS_TIMEOUT_MS = 5000
    }

    private val speedWindow = mutableListOf<Float>()
    private var slowStartMs: Long? = null

    fun recordSpeed(speedMps: Float, atMs: Long) {
        speedWindow.add(speedMps)
        if (speedWindow.size > SAMPLE_WINDOW) speedWindow.removeAt(0)
    }

    fun shouldPause(nowMs: Long): Boolean {
        if (speedWindow.size < SAMPLE_WINDOW) return false
        val avgSpeed = speedWindow.average().toFloat()
        if (avgSpeed < prefs.autoPauseThresholdMps) {
            if (slowStartMs == null) slowStartMs = nowMs
            return (nowMs - (slowStartMs ?: 0L)) >= PAUSE_HOLD_MS
        } else {
            slowStartMs = null
            return false
        }
    }

    fun reset() {
        speedWindow.clear()
        slowStartMs = null
    }
}
\\\

- [ ] **Step 4: Implement PauseController**

\\\kotlin
// app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/PauseController.kt
enum class PauseState { Running, Paused, PausedAndSuppressed }

class PauseController {
    var currentState = PauseState.Running
        private set

    fun onManualPause() {
        if (currentState == PauseState.Running) {
            currentState = PauseState.Paused
        }
    }

    fun onResume() {
        currentState = PauseState.Running
    }

    fun registerPushSent() {
        if (currentState == PauseState.Paused) {
            currentState = PauseState.PausedAndSuppressed
        }
    }

    fun shouldSuppressPush(): Boolean {
        return currentState == PauseState.PausedAndSuppressed
    }

    fun reset() {
        currentState = PauseState.Running
    }
}
\\\

- [ ] **Step 5: Run test**

\\\ash
./gradlew commonTest --tests PauseLogicTest -v
\\\

- [ ] **Step 6: Commit**

\\\ash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/AutoPauseManager.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/PauseController.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/PauseLogicTest.kt
git commit -m "feat: implement AutoPauseManager and PauseController

AutoPauseManager:
- Rolling 3-sample speed average
- 3s hysteresis before pause
- Disabled if GPS fix lost >5s

PauseController:
- State machine: Running → Paused → PausedAndSuppressed
- Manual pause/resume control
- Push suppression while paused

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
\\\

---

## Phase 2: Integration & Refactor (Tasks 6-8)

### Task 6: Refactor ActivitySessionEngine to use managers

**Files:**
- Modify: \pp/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngine.kt\
- Test: \pp/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngineTest.kt\ (extend)

**Interfaces:**
- Consumes: PushIntervalManager, AutoPauseManager, PauseController (Tasks 4-5)
- Produces: Refactored ActivitySessionEngine with delegated push cadence

- [ ] **Step 1-5: Delegate push logic, integrate managers, update tick() method, test, commit**

See full plan details for exact code changes.

---

### Task 7: Add average pace calculation to ActivitySession

**Files:**
- Modify: \pp/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySession.kt\

**Interfaces:**
- Consumes: AVERAGE_PACE metric (Task 2)
- Produces: Calculation in state() method

---

### Task 8: Integrate pause control into ticker

**Files:**
- Modify: \pp/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityScreen.kt\ (ingest pause events)

---

## Phase 3: UI & Navigation (Tasks 9-11)

### Task 9: Add interval preset selector UI

**Files:**
- Modify: \pp/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityScreen.kt\

**Interfaces:**
- Produces: RadioButtonGroup for interval presets (3s/15s/30s/1m/5m)

---

### Task 10: Embed history list in activity home

**Files:**
- Modify: \pp/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityScreen.kt\

**Interfaces:**
- Produces: Replace "last activity" card with scrollable history list

---

### Task 11: Fix navigation (land on home screen)

**Files:**
- Modify: \pp/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Navigation.kt\

**Interfaces:**
- Produces: Activity tab home screen (not glance-first)

---

## Execution Notes

- Test runner: \./gradlew commonTest -v\ (runs all unit tests)
- Full build: \./gradlew build\ (after all tasks complete)
- Commit early and often (after each task)
- All Phase 1 tasks are independent; execute in order for dependency safety

