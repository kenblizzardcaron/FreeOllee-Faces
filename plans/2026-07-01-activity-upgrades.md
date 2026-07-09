# Activity Upgrades Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut watch BLE battery drain via a configurable name-tag push interval, add Strava-style manual + automatic pause with moving-time accounting, surface a moving-time average-pace metric, and consolidate the Activity tab UI.

**Architecture:** Extend the *existing* pure primitives rather than inventing parallel managers. Push cadence stays in `ActivityPushDecider` (made spacing-configurable) → `NameplatePusher` → `ActivitySessionEngine.tick`. Pause + moving-time live in the pure `ActivitySession`; the engine orchestrates manual/auto pause and a new pure `AutoPauseDetector`. A new `ActivityMetric.AVG_PACE` renders moving-time pace. The Android `ActivitySessionService` gains pause/resume/interval intents; the Compose `ActivityScreen` gains an interval picker + pause button + embedded history.

**Tech Stack:** Kotlin Multiplatform (single `:app` module, `androidTarget` only — `commonMain` compiles against the JVM stdlib), Compose Multiplatform UI, `com.russhwolf.settings` for prefs, `kotlinx.datetime.Clock` for time, `kotlinx.coroutines` `StateFlow`.

## Global Constraints

- **Time source:** NEVER `System.currentTimeMillis()` in `commonMain`/`commonTest`. Use injected `now: () -> Long` / `kotlinx.datetime.Clock`, matching `ActivitySessionEngine`. (The one existing `System.currentTimeMillis()` in `ActivityStateTest.kt` is fixed in Task 1.)
- **Push interval presets (fixed, non-customizable):** `3s, 15s, 30s, 1m, 5m` — i.e. `3_000, 15_000, 30_000, 60_000, 300_000` ms. Default `30_000` ms.
- **Interval semantics:** the configured interval is the **minimum spacing between ANY name-tag write** (it replaces the old 1 Hz floor's role). A `forced` write (MODE / unit change) bypasses spacing.
- **No mid-activity interval changes:** the picker is only settable while idle; attempting to change during a running session is rejected with a snackbar. The engine reads the interval once at session start.
- **Auto-pause:** default speed threshold `0.1f` m/s; disabled while the GPS fix is lost (`> 5_000` ms since last fix); rolling speed average over the last 3 samples with a 3 s hysteresis hold before pausing.
- **Manual pause always works** regardless of GPS/auto-pause state and takes precedence over auto-resume.
- **Always-recording:** every recording session creates a track (already true — no change).
- **Average pace basis:** sec/km over **moving time** (elapsed minus paused spans), rendered exactly like `PACE` (unit-aware sec/unit).
- **Detekt-clean:** every literal is a named `const`; keep files ASCII; run `:app:detekt` after each task. Test task: `./gradlew :app:testDebugUnitTest --tests "<FQCN>"`. Build: `./gradlew :app:assembleDebug`.
- **Co-author trailer on every commit:** `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

## Compute Offload (claude-local)

DRY across all tasks. Delegate only whole-**new-file** units with a deterministic gate; the controller (Opus) writes every *modify-existing* edit, every exhaustiveness ripple, and all text-out (commit bodies, PR body). Gate = the task's own `:app:testDebugUnitTest --tests` run **plus** `:app:detekt`, verified by the controller via `git diff` (never the local agent's self-report). Run `claude-local-brief-check BRIEF.md` before dispatching.

- **Delegatable new-file tasks:** Task 2 test file (`ActivityPushDeciderTest` additions are modify — skip), Task 6 `AutoPauseDetector.kt` + `AutoPauseDetectorTest.kt` (pure, fully gated), Task 4/5/8 test files where they are brand-new. Give full ASCII file contents; brief must say "write a NEW file".
- **Not delegatable (controller does directly):** all `Modify:` edits (Prefs, ActivityPushDecider, NameplatePusher, ActivitySessionEngine, ActivityMetric, ActivityState, ActivitySession, the service, controller, launcher, Compose screens, navigation) and the 4-way `when` ripple in Task 8.
- Auto-fallback: if the box/proxy is unreachable, the controller implements the chunk itself. claude-local output is a draft until the gate is green.

---

## Phase 1 — Prefs & configurable push spacing

### Task 1: Fix ActivityStateTest time source (convention debt)

**Files:**
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityStateTest.kt`

**Interfaces:**
- Consumes: `ActivityState` (already has `paused`, `pausedAtMs` from the WIP commit).
- Produces: nothing new — removes the `System.currentTimeMillis()` call that violates the injected-clock convention.

- [ ] **Step 1: Replace the JVM clock call with a fixed literal**

In `ActivityStateTest.kt`, replace:

```kotlin
        val now = System.currentTimeMillis()
```

with a deterministic constant (no platform clock in commonTest):

```kotlin
        val now = 1_700_000_000_000L
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.activity.ActivityStateTest"`
Expected: PASS (2 tests).

- [ ] **Step 3: Detekt**

Run: `./gradlew :app:detekt`
Expected: no new findings.

- [ ] **Step 4: Commit**

```bash
git add app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityStateTest.kt
git commit -m "test(activity): use fixed clock literal in ActivityStateTest

Removes a System.currentTimeMillis() call that violated the injected-Clock
convention used across the activity engine.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Add push-interval & auto-pause-threshold prefs

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/prefs/PrefsTest.kt`

**Interfaces:**
- Consumes: `com.russhwolf.settings.Settings` (already the `Prefs` ctor arg).
- Produces:
  - `var Prefs.activityPushIntervalMs: Long` (getter/setter, default `30_000L`)
  - `var Prefs.autoPauseThresholdMps: Float` (getter/setter, default `0.1f`)
  - `Prefs.Companion` constants: `DEFAULT_PUSH_INTERVAL_MS = 30_000L`, `PUSH_INTERVAL_PRESETS_MS = listOf(3_000L, 15_000L, 30_000L, 60_000L, 300_000L)`, `DEFAULT_AUTO_PAUSE_MPS = 0.1f`.

- [ ] **Step 1: Write failing tests**

Add to `PrefsTest.kt` (uses the existing `freshPrefs(...)` `MapSettings` helper):

```kotlin
    @Test
    fun testPushIntervalDefaultsTo30s() {
        assertEquals(30_000L, freshPrefs().activityPushIntervalMs)
    }

    @Test
    fun testPushIntervalRoundTrips() {
        val prefs = freshPrefs()
        prefs.activityPushIntervalMs = 60_000L
        assertEquals(60_000L, prefs.activityPushIntervalMs)
    }

    @Test
    fun testAutoPauseThresholdDefaults() {
        assertEquals(0.1f, freshPrefs().autoPauseThresholdMps)
    }

    @Test
    fun testAutoPauseThresholdRoundTrips() {
        val prefs = freshPrefs()
        prefs.autoPauseThresholdMps = 0.25f
        assertEquals(0.25f, prefs.autoPauseThresholdMps)
    }

    @Test
    fun testPresetsAreTheFiveLockedValues() {
        assertEquals(
            listOf(3_000L, 15_000L, 30_000L, 60_000L, 300_000L),
            Prefs.PUSH_INTERVAL_PRESETS_MS,
        )
    }
```

- [ ] **Step 2: Run tests (verify failure)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.prefs.PrefsTest"`
Expected: FAIL — unresolved reference `activityPushIntervalMs` / `autoPauseThresholdMps` / `PUSH_INTERVAL_PRESETS_MS`.

- [ ] **Step 3: Add the pref accessors**

In `Prefs.kt`, add accessors alongside the existing ones (mirroring the `getLong`/`putLong` style):

```kotlin
    var activityPushIntervalMs: Long
        get() = settings.getLong(KEY_PUSH_INTERVAL_MS, DEFAULT_PUSH_INTERVAL_MS)
        set(value) = settings.putLong(KEY_PUSH_INTERVAL_MS, value)

    var autoPauseThresholdMps: Float
        get() = settings.getFloat(KEY_AUTO_PAUSE_MPS, DEFAULT_AUTO_PAUSE_MPS)
        set(value) = settings.putFloat(KEY_AUTO_PAUSE_MPS, value)
```

In the `Prefs` companion object add the keys + defaults + presets:

```kotlin
        const val KEY_PUSH_INTERVAL_MS = "activity_push_interval_ms"
        const val KEY_AUTO_PAUSE_MPS = "activity_auto_pause_mps"
        const val DEFAULT_PUSH_INTERVAL_MS = 30_000L
        const val DEFAULT_AUTO_PAUSE_MPS = 0.1f
        val PUSH_INTERVAL_PRESETS_MS = listOf(3_000L, 15_000L, 30_000L, 60_000L, 300_000L)
```

- [ ] **Step 4: Run tests (verify pass)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.prefs.PrefsTest"`
Expected: PASS.

- [ ] **Step 5: Detekt**

Run: `./gradlew :app:detekt` — Expected: clean.

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/prefs/PrefsTest.kt
git commit -m "feat(activity): add push-interval and auto-pause-threshold prefs

- activityPushIntervalMs (default 30s) gates name-tag write spacing
- autoPauseThresholdMps (default 0.1 m/s) for speed-based auto-pause
- PUSH_INTERVAL_PRESETS_MS locks the five selectable intervals

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Make ActivityPushDecider spacing configurable

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityPushDecider.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityPushDeciderTest.kt`

**Interfaces:**
- Consumes: caller supplies `minSpacingMs`.
- Produces: `ActivityPushDecider.shouldPush(lastPushedText: String?, newText: String, msSinceLastPush: Long, forced: Boolean, minSpacingMs: Long): Boolean`. Semantics: `forced` → true; first push (`lastPushedText == null`) → true; `msSinceLastPush < minSpacingMs` → false; otherwise → true (spacing elapsed ⇒ refresh latest value). `DEFAULT_MIN_SPACING_MS = 30_000L`.

- [ ] **Step 1: Rewrite the decider test** to the new signature (replace the whole existing `ActivityPushDeciderTest` body):

```kotlin
package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityPushDeciderTest {
    private val spacing = 30_000L

    @Test fun firstPushAlwaysAllowed() =
        assertTrue(ActivityPushDecider.shouldPush(null, "P5 00", 0, forced = false, minSpacingMs = spacing))

    @Test fun suppressedBeforeSpacingEvenOnChange() =
        assertFalse(ActivityPushDecider.shouldPush("P5 00", "P4 59", 15_000, forced = false, minSpacingMs = spacing))

    @Test fun allowedAfterSpacingElapses() =
        assertTrue(ActivityPushDecider.shouldPush("P5 00", "P5 00", 30_000, forced = false, minSpacingMs = spacing))

    @Test fun forcedBypassesSpacing() =
        assertTrue(ActivityPushDecider.shouldPush("P5 00", "d1-23", 1, forced = true, minSpacingMs = spacing))

    @Test fun smallIntervalHonored() =
        assertTrue(ActivityPushDecider.shouldPush("P5 00", "P4 59", 3_000, forced = false, minSpacingMs = 3_000L))
}
```

- [ ] **Step 2: Run (verify failure)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.activity.ActivityPushDeciderTest"`
Expected: FAIL — signature mismatch.

- [ ] **Step 3: Rewrite the decider** (`ActivityPushDecider.kt` in full):

```kotlin
package com.blizzardcaron.freeolleefaces.activity

/**
 * Pure policy for when to write the name-tag. The configured [minSpacingMs] is the minimum gap
 * between ANY two writes (battery saver); a MODE/unit change (`forced`) bypasses it, and the very
 * first write is always allowed. Once the spacing has elapsed we re-push the latest text (this also
 * serves as the heartbeat), so a value that changed mid-interval is coalesced into one write.
 */
object ActivityPushDecider {

    const val DEFAULT_MIN_SPACING_MS = 30_000L

    fun shouldPush(
        lastPushedText: String?,
        newText: String,
        msSinceLastPush: Long,
        forced: Boolean,
        minSpacingMs: Long = DEFAULT_MIN_SPACING_MS,
    ): Boolean {
        if (forced) return true
        if (lastPushedText == null) return true
        return msSinceLastPush >= minSpacingMs
    }
}
```

Two deliberate choices that keep every task's build green:
- `minSpacingMs` has a **default** (`DEFAULT_MIN_SPACING_MS`), so `NameplatePusher`'s existing 4-arg call still compiles after this task; Task 4 threads the real per-session value.
- `newText` is retained in the signature (callers pass the sanitized text) even though the coalescing policy no longer branches on it — keeping it preserves the call site and leaves room for a future "changed-only" mode without another signature churn.

- [ ] **Step 4: Run (verify pass)** — same command as Step 2. Expected: PASS.

- [ ] **Step 5: Detekt** — `./gradlew :app:detekt`. Expected: clean.

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityPushDecider.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityPushDeciderTest.kt
git commit -m "feat(activity): configurable min spacing in ActivityPushDecider

The push interval now gates ALL name-tag writes (default 30s), replacing the
fixed 1 Hz floor + 3s heartbeat. Forced writes (MODE/unit) still bypass.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Phase 2 — Interval plumbing, moving time, manual pause

### Task 4: Thread the per-session push interval through the pusher and engine

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/NameplatePusher.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngine.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/NameplatePusherTest.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngineTest.kt`

**Interfaces:**
- Consumes: `Prefs.activityPushIntervalMs` (Task 2), `ActivityPushDecider.shouldPush(..., minSpacingMs)` (Task 3).
- Produces: `NameplatePusher.maybePush(address, rawText, nowMs, currentlyReachable, minSpacingMs: Long = ActivityPushDecider.DEFAULT_MIN_SPACING_MS)`; engine reads the interval once per session into a private `pushIntervalMs` field.

- [ ] **Step 1: Write/adjust failing tests**

Add to `NameplatePusherTest.kt` a spacing test:

```kotlin
    @Test fun honorsConfiguredSpacingForChangedText() = runTest {
        val ble = RecordingBle()
        val pusher = NameplatePusher(ble)
        pusher.maybePush("AA:BB", "P5 30", nowMs = 0, currentlyReachable = true, minSpacingMs = 30_000L)
        // Changed value, but only 5s later and spacing is 30s -> suppressed (battery saver).
        pusher.maybePush("AA:BB", "P5 25", nowMs = 5_000, currentlyReachable = true, minSpacingMs = 30_000L)
        assertEquals(1, ble.sent.size)
        // At 30s the spacing has elapsed -> the latest value is written.
        pusher.maybePush("AA:BB", "P5 10", nowMs = 30_000, currentlyReachable = true, minSpacingMs = 30_000L)
        assertEquals(2, ble.sent.size)
        assertEquals("P5 10", pusher.sent(ble).last())
    }
```

Add to `ActivitySessionEngineTest.kt` an engine-level plumbing test:

```kotlin
    @Test fun engine_uses_configured_push_interval() = runTest {
        val h = Harness()
        h.prefs.activityPushIntervalMs = 3_000L
        h.engine.start()
        h.engine.ingest(h.fix(0.0, 0.0), 0L)
        h.engine.tick(0L)
        val first = h.ble.sentNameplate().size
        h.engine.tick(1_000L) // within 3s spacing -> no new write
        assertEquals(first, h.ble.sentNameplate().size)
        h.engine.tick(3_000L) // spacing elapsed -> a new write
        assertTrue(h.ble.sentNameplate().size > first)
    }
```

- [ ] **Step 2: Audit tests broken by the Task 3 semantics change** — search and update:

Run: `grep -rn "HEARTBEAT\|heartbeat\|MIN_PUSH_INTERVAL\|within.*floor\|3s heartbeat" app/src/commonTest`

Rewrite any assertion that depended on the old 1 Hz floor / 3 s heartbeat. Known cases in `NameplatePusherTest`:
- `skipsUnchangedTextWithinHeartbeat` → still valid (spacing suppresses); rename to `skipsRepeatWithinSpacing` and pass `minSpacingMs = 30_000L` explicitly for clarity.
- `forceNextRepushesIdenticalText` → still valid (`forced` bypasses spacing); keep.
- `forceSurvivesFloorSuppressionAndFiresNextEligiblePush` → **obsolete** (there is no sub-spacing floor now; `forced` fires immediately). Replace with `forcedFiresImmediatelyRegardlessOfSpacing`:

```kotlin
    @Test fun forcedFiresImmediatelyRegardlessOfSpacing() = runTest {
        val ble = RecordingBle()
        val pusher = NameplatePusher(ble)
        pusher.maybePush("AA:BB", "P5 30", nowMs = 0, currentlyReachable = true, minSpacingMs = 30_000L)
        pusher.forceNext()
        pusher.maybePush("AA:BB", "P5 30", nowMs = 200, currentlyReachable = true, minSpacingMs = 30_000L)
        assertEquals(2, ble.sent.size)
    }
```

Also add a tiny helper to the test file if not present: `private fun NameplatePusher.sent(ble: RecordingBle) = ble.sent.map { it.second }` (or inline `ble.sent.map { it.second }`).

- [ ] **Step 3: Run (verify failures)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.activity.NameplatePusherTest" --tests "com.blizzardcaron.freeolleefaces.activity.ActivitySessionEngineTest"`
Expected: FAIL — `maybePush` has no `minSpacingMs` param yet; engine ignores the interval.

- [ ] **Step 4: Add the `minSpacingMs` param to the pusher**

In `NameplatePusher.kt`, change the signature and the decider call:

```kotlin
    suspend fun maybePush(
        address: String?,
        rawText: String,
        nowMs: Long,
        currentlyReachable: Boolean,
        minSpacingMs: Long = ActivityPushDecider.DEFAULT_MIN_SPACING_MS,
    ): Boolean {
        val text = NameplateSanitizer.sanitize(rawText)
        var reachable = currentlyReachable
        val approved = address != null &&
            ActivityPushDecider.shouldPush(lastPushedText, text, nowMs - lastPushMs, force, minSpacingMs)
```

(The rest of the method body is unchanged.)

- [ ] **Step 5: Read the interval once per session in the engine**

In `ActivitySessionEngine.kt`, add a field near `private var recording = false`:

```kotlin
    private var pushIntervalMs: Long = ActivityPushDecider.DEFAULT_MIN_SPACING_MS
```

Set it in `start()`, `startLive()`, and `beginRecording()` right after `unit`/config is read (interval is fixed for the session — Global Constraint "no mid-activity changes"):

```kotlin
        pushIntervalMs = prefs.activityPushIntervalMs
```

In `tick()`, pass it to the pusher — change the `pusher.maybePush(...)` call to:

```kotlin
        val reachable = pusher.maybePush(watchAddress(), raw, nowMs, prev.watchReachable, pushIntervalMs)
```

- [ ] **Step 6: Run (verify pass)** — same commands as Step 3. Expected: PASS (whole suite; run `./gradlew :app:testDebugUnitTest` to be sure nothing else regressed).

- [ ] **Step 7: Detekt** — `./gradlew :app:detekt`. Expected: clean.

- [ ] **Step 8: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/NameplatePusher.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngine.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/NameplatePusherTest.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngineTest.kt
git commit -m "feat(activity): drive name-tag push spacing from the interval pref

Engine reads activityPushIntervalMs once per session and threads it through
NameplatePusher into ActivityPushDecider. Updates pusher/engine tests for the
new spacing semantics (no sub-spacing floor).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Moving-time accounting + distance freeze in ActivitySession

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySession.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityState.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `ActivitySession.pause(nowMs)`, `.resume(nowMs)`, `.movingTimeMs(nowMs): Long`; distance no longer accrues while paused; `ActivitySession.state(...)` now sets `movingTimeMs` and `paused`. New `ActivityState.movingTimeMs: Long = 0L` field.

- [ ] **Step 1: Write failing tests** — add to `ActivitySessionTest.kt`:

```kotlin
    @Test fun movingTimeExcludesPausedSpan() {
        val s = ActivitySession(startedAtMs = 0L)
        s.pause(10_000L)
        s.resume(15_000L)              // 5s paused
        assertEquals(25_000L, s.movingTimeMs(30_000L)) // 30s elapsed - 5s paused
    }

    @Test fun movingTimeCountsOngoingPause() {
        val s = ActivitySession(startedAtMs = 0L)
        s.pause(10_000L)
        assertEquals(10_000L, s.movingTimeMs(25_000L)) // frozen at pause instant
    }

    @Test fun distanceDoesNotAccrueWhilePaused() {
        val s = ActivitySession(startedAtMs = 0L)
        s.onSample(Coords(0.0, 0.0, 5f, "gps"), 0L)
        s.pause(1_000L)
        s.onSample(Coords(0.01, 0.0, 5f, "gps"), 2_000L) // ~1.1 km jump, but paused
        assertEquals(0.0, s.distanceMeters)
        s.resume(3_000L)
        s.onSample(Coords(0.01, 0.0, 5f, "gps"), 4_000L) // same spot as resume -> no teleport
        assertEquals(0.0, s.distanceMeters)
    }
```

- [ ] **Step 2: Run (verify failure)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.activity.ActivitySessionTest"`
Expected: FAIL — `pause`/`resume`/`movingTimeMs` unresolved.

- [ ] **Step 3: Add the field to `ActivityState`** — insert after `pausedAtMs`:

```kotlin
    val movingTimeMs: Long = 0L,
```

- [ ] **Step 4: Implement pause/moving-time in `ActivitySession`**

Add fields after `private val window = ArrayDeque<Mark>()`:

```kotlin
    private var paused = false
    private var pausedAccumMs = 0L
    private var pausedSinceMs: Long? = null
```

Add methods (place above `state(...)`):

```kotlin
    fun pause(nowMs: Long) {
        if (!paused) { paused = true; pausedSinceMs = nowMs }
    }

    fun resume(nowMs: Long) {
        if (!paused) return
        pausedSinceMs?.let { pausedAccumMs += (nowMs - it).coerceAtLeast(0L) }
        pausedSinceMs = null
        paused = false
    }

    fun movingTimeMs(nowMs: Long): Long {
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(0L)
        val ongoing = pausedSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        return (elapsed - pausedAccumMs - ongoing).coerceAtLeast(0L)
    }
```

Freeze distance while paused — in `onSample`, after the accuracy gate (`if (acc != null && acc > accuracyGateM) return`), add:

```kotlin
        if (paused) { lastAccepted = coords; return } // hold position so resume doesn't count the gap
```

Extend `state(...)` to carry the new fields:

```kotlin
    fun state(selectedMetric: ActivityMetric, nowMs: Long): ActivityState = ActivityState(
        running = true,
        selectedMetric = selectedMetric,
        distanceMeters = distanceMeters,
        recentPaceSecPerKm = recentPaceSecPerKm(nowMs),
        elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0L),
        movingTimeMs = movingTimeMs(nowMs),
        paused = paused,
    )
```

- [ ] **Step 5: Run (verify pass)** — same command as Step 2. Expected: PASS. Then full suite: `./gradlew :app:testDebugUnitTest`.

- [ ] **Step 6: Detekt** — `./gradlew :app:detekt`. Expected: clean.

- [ ] **Step 7: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySession.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityState.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionTest.kt
git commit -m "feat(activity): moving-time accounting and paused-distance freeze

ActivitySession tracks paused spans, exposes movingTimeMs, and stops accruing
distance while paused (holding position to avoid a resume teleport). Adds
ActivityState.movingTimeMs for the average-pace metric.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Manual pause/resume in the engine (+ paused nameplate)

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngine.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngineTest.kt`

**Interfaces:**
- Consumes: `ActivitySession.pause/resume` (Task 5).
- Produces: `ActivitySessionEngine.pause(nowMs)`, `.resume(nowMs)`; a private `pauseSource: PauseSource` (`NONE/MANUAL/AUTO`, top-level `enum class PauseSource` in the activity package — Task 8 reuses `AUTO`); while paused the nameplate shows `PAUSED_NAMEPLATE`.

- [ ] **Step 1: Write failing tests** — add to `ActivitySessionEngineTest.kt`:

```kotlin
    @Test fun manual_pause_sets_state_and_freezes_distance() = runTest {
        val h = Harness()
        h.engine.start()
        h.engine.ingest(h.fix(0.0, 0.0), 0L)
        h.engine.pause(1_000L)
        assertTrue(h.engine.state.value.paused)
        h.engine.ingest(h.fix(0.01, 0.0), 2_000L) // big jump while paused
        assertEquals(0.0, h.engine.state.value.distanceMeters)
        h.engine.resume(3_000L)
        assertFalse(h.engine.state.value.paused)
    }

    @Test fun tick_while_paused_pushes_paused_nameplate() = runTest {
        val h = Harness()
        h.engine.start()
        h.engine.ingest(h.fix(0.0, 0.0), 0L)
        h.engine.pause(1_000L)
        h.engine.tick(1_000L)
        assertTrue(h.ble.sentNameplate().any { it.trim() == "PAUSE" })
    }
```

- [ ] **Step 2: Run (verify failure)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.activity.ActivitySessionEngineTest"`
Expected: FAIL — `pause`/`resume` unresolved.

- [ ] **Step 3: Add `PauseSource` enum** — new top-level enum. Add to a new file `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/PauseSource.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.activity

/** Who owns the current pause. MANUAL takes precedence: auto-resume only lifts an AUTO pause. */
enum class PauseSource { NONE, MANUAL, AUTO }
```

- [ ] **Step 4: Implement engine pause/resume**

In `ActivitySessionEngine.kt` add a field near `private var recording = false`:

```kotlin
    private var pauseSource: PauseSource = PauseSource.NONE
```

Add the paused-nameplate constant to the companion object:

```kotlin
        const val PAUSED_NAMEPLATE = "PAUSE " // 6 cells: steady paused indicator
```

Add the public control methods (place near `cycleMetric()`):

```kotlin
    fun pause(nowMs: Long) {
        val s = session ?: return
        pauseSource = PauseSource.MANUAL
        s.pause(nowMs)
        pusher.forceNext()
        _state.value = _state.value.copy(paused = true, pausedAtMs = nowMs)
    }

    fun resume(nowMs: Long) {
        val s = session ?: return
        pauseSource = PauseSource.NONE
        s.resume(nowMs)
        pusher.forceNext()
        _state.value = _state.value.copy(paused = false, pausedAtMs = null)
    }
```

In `tick()`, make the paused state win the render branch — change the `raw` assignment to:

```kotlin
        val raw = when {
            prev.paused -> PAUSED_NAMEPLATE
            prev.hasFix -> selectedMetric.render(st, unit)
            selectedMetric == ActivityMetric.PRESSURE && st.pressureHpa != null ->
                selectedMetric.render(st, unit)
            else -> ACQUIRING_NAMEPLATE
        }
```

Reset `pauseSource = PauseSource.NONE` in `stop()` (alongside `recording = false`).

- [ ] **Step 5: Run (verify pass)** — same command as Step 2, then full suite. Expected: PASS.

- [ ] **Step 6: Detekt** — clean.

- [ ] **Step 7: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngine.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/PauseSource.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngineTest.kt
git commit -m "feat(activity): manual pause/resume with paused nameplate

engine.pause()/resume() freeze the session, force a PAUSE indicator to the
watch, and track pause ownership via PauseSource (MANUAL precedence prep for
auto-pause).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Phase 3 — Auto-pause

### Task 7: AutoPauseDetector (pure)

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/AutoPauseDetector.kt`
- Create: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/AutoPauseDetectorTest.kt`

> **claude-local candidate:** whole-new-file pair, fully gated by the test below + `:app:detekt`. Brief must say "write two NEW files", ASCII, trailing newline.

**Interfaces:**
- Consumes: a `thresholdMps: Float` (from `Prefs.autoPauseThresholdMps`), plus per-sample `(speedMps, nowMs)`.
- Produces: `AutoPauseDetector(thresholdMps).onSample(speedMps, nowMs)`, `.shouldAutoPause(nowMs): Boolean`, `.shouldAutoResume(nowMs): Boolean`, `.reset()`.

- [ ] **Step 1: Write the failing test** (`AutoPauseDetectorTest.kt`, whole file):

```kotlin
package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoPauseDetectorTest {

    private fun slow(det: AutoPauseDetector, times: List<Long>) =
        times.forEach { det.onSample(0.05f, it) }

    @Test fun pausesAfterSlowHoldWithFullWindow() {
        val det = AutoPauseDetector(thresholdMps = 0.1f)
        slow(det, listOf(0L, 1_000L, 2_000L))
        assertFalse(det.shouldAutoPause(2_000L)) // window just filled, hold not yet met
        assertTrue(det.shouldAutoPause(3_000L))  // 3s below threshold
    }

    @Test fun doesNotPauseWhenWindowNotFull() {
        val det = AutoPauseDetector(thresholdMps = 0.1f)
        det.onSample(0.05f, 0L)
        assertFalse(det.shouldAutoPause(9_000L))
    }

    @Test fun doesNotPauseWhenFixIsStale() {
        val det = AutoPauseDetector(thresholdMps = 0.1f)
        slow(det, listOf(0L, 1_000L, 2_000L))
        assertFalse(det.shouldAutoPause(9_000L)) // >5s since last sample -> GPS lost
    }

    @Test fun aboveThresholdNeverPausesAndResumes() {
        val det = AutoPauseDetector(thresholdMps = 0.1f)
        listOf(0L, 1_000L, 2_000L).forEach { det.onSample(1.5f, it) }
        assertFalse(det.shouldAutoPause(3_000L))
        assertTrue(det.shouldAutoResume(3_000L))
    }

    @Test fun resumeFalseWhenStale() {
        val det = AutoPauseDetector(thresholdMps = 0.1f)
        det.onSample(1.5f, 0L)
        assertFalse(det.shouldAutoResume(9_000L))
    }
}
```

- [ ] **Step 2: Run (verify failure)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.activity.AutoPauseDetectorTest"`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement** (`AutoPauseDetector.kt`, whole file):

```kotlin
package com.blizzardcaron.freeolleefaces.activity

/**
 * Pure speed-based auto-pause policy. Averages the last [sampleWindow] ground speeds; pauses once
 * that average stays below [thresholdMps] for [pauseHoldMs]; resumes once it is back at/above
 * threshold. Both are gated off when the GPS fix is stale (> [gpsTimeoutMs] since the last sample)
 * so a dropped fix never fabricates a pause. No time source of its own — the caller passes nowMs.
 */
class AutoPauseDetector(
    private val thresholdMps: Float,
    private val sampleWindow: Int = DEFAULT_SAMPLE_WINDOW,
    private val pauseHoldMs: Long = DEFAULT_PAUSE_HOLD_MS,
    private val gpsTimeoutMs: Long = DEFAULT_GPS_TIMEOUT_MS,
) {
    private val speeds = ArrayDeque<Float>()
    private var slowSinceMs: Long? = null
    private var lastSampleMs: Long? = null

    fun onSample(speedMps: Float, nowMs: Long) {
        lastSampleMs = nowMs
        speeds.addLast(speedMps)
        while (speeds.size > sampleWindow) speeds.removeFirst()
    }

    fun shouldAutoPause(nowMs: Long): Boolean {
        if (isStale(nowMs)) { slowSinceMs = null; return false }
        if (speeds.size < sampleWindow) return false
        if (speeds.average() >= thresholdMps) { slowSinceMs = null; return false }
        val since = slowSinceMs ?: nowMs.also { slowSinceMs = it }
        return nowMs - since >= pauseHoldMs
    }

    fun shouldAutoResume(nowMs: Long): Boolean {
        if (isStale(nowMs) || speeds.isEmpty()) return false
        return speeds.average() >= thresholdMps
    }

    fun reset() {
        speeds.clear()
        slowSinceMs = null
        lastSampleMs = null
    }

    private fun isStale(nowMs: Long): Boolean {
        val last = lastSampleMs ?: return true
        return nowMs - last > gpsTimeoutMs
    }

    private companion object {
        const val DEFAULT_SAMPLE_WINDOW = 3
        const val DEFAULT_PAUSE_HOLD_MS = 3_000L
        const val DEFAULT_GPS_TIMEOUT_MS = 5_000L
    }
}
```

(`speeds.average()` returns `Double`; comparing to a `Float` threshold widens the `Float` to `Double` — no cast needed.)

- [ ] **Step 4: Run (verify pass)** — same command as Step 2. Expected: PASS.

- [ ] **Step 5: Detekt** — clean.

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/AutoPauseDetector.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/AutoPauseDetectorTest.kt
git commit -m "feat(activity): AutoPauseDetector speed policy

Rolling 3-sample speed average with a 3s hold to pause and immediate resume
above threshold; both disabled while the GPS fix is stale (>5s).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: Wire auto-pause into the engine

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngine.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngineTest.kt`

**Interfaces:**
- Consumes: `AutoPauseDetector` (Task 7), `PauseSource` (Task 6), `Prefs.autoPauseThresholdMps` (Task 2), `Coords.speedMps`.
- Produces: engine auto-pauses/-resumes from GPS speed during `ingest`, respecting MANUAL precedence. Refactors the pause body into a private `applyPause(nowMs, source)`.

- [ ] **Step 1: Write failing tests** — add to `ActivitySessionEngineTest.kt` (extend `Harness` with speed helpers):

```kotlin
        fun slowFix() = Coords(0.0, 0.0, 5f, "gps", speedMps = 0.0f)
        fun fastFix() = Coords(0.0, 0.0, 5f, "gps", speedMps = 5.0f)
```

```kotlin
    @Test fun auto_pause_after_slow_hold() = runTest {
        val h = Harness()
        h.prefs.autoPauseThresholdMps = 0.5f
        h.engine.start()
        h.engine.ingest(h.slowFix(), 0L)
        h.engine.ingest(h.slowFix(), 1_000L)
        h.engine.ingest(h.slowFix(), 2_000L)
        h.engine.ingest(h.slowFix(), 3_000L) // window full + 3s hold
        assertTrue(h.engine.state.value.paused)
    }

    @Test fun auto_resume_when_moving_again() = runTest {
        val h = Harness()
        h.prefs.autoPauseThresholdMps = 0.5f
        h.engine.start()
        repeat(4) { h.engine.ingest(h.slowFix(), it * 1_000L) }
        assertTrue(h.engine.state.value.paused)
        h.engine.ingest(h.fastFix(), 4_000L)
        assertFalse(h.engine.state.value.paused)
    }

    @Test fun manual_pause_not_lifted_by_movement() = runTest {
        val h = Harness()
        h.prefs.autoPauseThresholdMps = 0.5f
        h.engine.start()
        h.engine.pause(1_000L)
        h.engine.ingest(h.fastFix(), 2_000L) // moving fast, but manual pause holds
        assertTrue(h.engine.state.value.paused)
    }
```

- [ ] **Step 2: Run (verify failure)** — auto-pause not implemented; `state.paused` stays false.

- [ ] **Step 3: Add the detector field + creation**

In `ActivitySessionEngine.kt` add near `pauseSource`:

```kotlin
    private var autoPause: AutoPauseDetector? = null
```

In `start()`, `startLive()`, and `beginRecording()`, after `pushIntervalMs = prefs.activityPushIntervalMs`, add:

```kotlin
        autoPause = AutoPauseDetector(prefs.autoPauseThresholdMps)
        pauseSource = PauseSource.NONE
```

- [ ] **Step 4: Refactor pause into `applyPause` and add the auto path**

Replace the `pause()` body from Task 6 with a delegating version and add `applyPause`:

```kotlin
    fun pause(nowMs: Long) = applyPause(nowMs, PauseSource.MANUAL)

    private fun applyPause(nowMs: Long, source: PauseSource) {
        val s = session ?: return
        pauseSource = source
        s.pause(nowMs)
        pusher.forceNext()
        _state.value = _state.value.copy(paused = true, pausedAtMs = nowMs)
    }
```

At the end of `ingest(...)` (after the `_state.value = s.state(...).copy(...)` assignment), add the auto-pause evaluation:

```kotlin
        autoPause?.let { det ->
            det.onSample(coords.speedMps ?: 0f, nowMs)
            when (pauseSource) {
                PauseSource.NONE -> if (det.shouldAutoPause(nowMs)) applyPause(nowMs, PauseSource.AUTO)
                PauseSource.AUTO -> if (det.shouldAutoResume(nowMs)) resume(nowMs)
                PauseSource.MANUAL -> Unit // manual precedence: movement never lifts a manual pause
            }
        }
```

- [ ] **Step 5: Run (verify pass)** — same command, then full `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 6: Detekt** — clean.

- [ ] **Step 7: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngine.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionEngineTest.kt
git commit -m "feat(activity): drive auto-pause/resume from GPS speed

Engine feeds ground speed into AutoPauseDetector each ingest and pauses/resumes
accordingly, with MANUAL pause taking precedence over auto-resume.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Phase 4 — Average-pace metric

### Task 9: Add ActivityMetric.AVG_PACE (render + human + exhaustiveness ripple)

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetric.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityScreen.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityMetricsConfigScreen.kt`
- Create: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetricTest.kt`

**Interfaces:**
- Consumes: `ActivityState.movingTimeMs` + `distanceMeters` (Task 5), `ActivityUnit.paceSecondsPerUnit`.
- Produces: `ActivityMetric.AVG_PACE` rendering sec/unit over moving time (nameplate tag `A`), human `"m:ss /<unit> avg"`.
- **Exhaustiveness ripple — a new enum value forces these 4 `when`s to add a branch:** `ActivityMetric.render`, `ActivityMetric.human`, `ActivityScreen.metricLabel`, `ActivityMetricsConfigScreen.metricLabel`. Missing any is a compile error (good — the compiler enforces it).

- [ ] **Step 1: Write the failing test** (`ActivityMetricTest.kt`, whole file):

```kotlin
package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityMetricTest {
    @Test fun avgPaceOverMovingTimeRendersLikePace() {
        val state = ActivityState(distanceMeters = 1000.0, movingTimeMs = 300_000L) // 5:00 /km
        assertEquals("A5 00", ActivityMetric.AVG_PACE.render(state, ActivityUnit.METRIC))
    }

    @Test fun avgPaceBlanksWithoutDistance() {
        val state = ActivityState(distanceMeters = 0.0, movingTimeMs = 60_000L)
        assertEquals("A --", ActivityMetric.AVG_PACE.render(state, ActivityUnit.METRIC))
    }

    @Test fun avgPaceHumanIncludesUnitAndAvg() {
        val state = ActivityState(distanceMeters = 1000.0, movingTimeMs = 300_000L)
        assertEquals("5:00 /km avg", ActivityMetric.AVG_PACE.human(state, ActivityUnit.METRIC))
    }

    @Test fun avgPaceHumanNullWithoutDistance() {
        assertNull(ActivityMetric.AVG_PACE.human(ActivityState(movingTimeMs = 60_000L), ActivityUnit.METRIC))
    }
}
```

- [ ] **Step 2: Run (verify failure)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.activity.ActivityMetricTest"`
Expected: FAIL — `AVG_PACE` unresolved.

- [ ] **Step 3: Add the enum value + render/human branches**

In `ActivityMetric.kt`, add `AVG_PACE` to the entry list:

```kotlin
    PACE, DISTANCE, TIME, ORIENTATION, ALTITUDE, PRESSURE, AVG_PACE;
```

Add a branch to `render`'s `when (this)`:

```kotlin
        AVG_PACE -> renderAvgPace(state, unit)
```

Add a branch to `human`'s `when (this)`:

```kotlin
        AVG_PACE -> humanAvgPace(state, unit)
```

Add these helpers + constants to the private companion object:

```kotlin
        const val METERS_PER_KM = 1000.0
        const val MILLIS_PER_SEC_D = 1000.0
        const val AVG_PACE_TAG = "A"

        private fun avgPaceSecPerKm(state: ActivityState): Double? {
            val km = state.distanceMeters / METERS_PER_KM
            if (km <= 0.0 || state.movingTimeMs <= 0L) return null
            return (state.movingTimeMs / MILLIS_PER_SEC_D) / km
        }

        fun renderAvgPace(state: ActivityState, unit: ActivityUnit): String {
            val secPerKm = avgPaceSecPerKm(state) ?: return "$AVG_PACE_TAG --"
            val secs = unit.paceSecondsPerUnit(secPerKm).roundToInt().coerceIn(0, MAX_PACE_SECONDS)
            val mm = secs / SECONDS_PER_MINUTE
            val ss = (secs % SECONDS_PER_MINUTE).toString().padStart(2, '0')
            return "$AVG_PACE_TAG$mm $ss"
        }

        fun humanAvgPace(state: ActivityState, unit: ActivityUnit): String? {
            val secPerKm = avgPaceSecPerKm(state) ?: return null
            val secs = unit.paceSecondsPerUnit(secPerKm).roundToInt().coerceAtLeast(0)
            val mm = secs / SECONDS_PER_MINUTE
            val ss = (secs % SECONDS_PER_MINUTE).toString().padStart(2, '0')
            return "$mm:$ss /${unit.distanceSuffix} avg"
        }
```

- [ ] **Step 4: Satisfy the two UI `when`s (compile ripple)**

In `ActivityScreen.kt` `metricLabel(...)` add:

```kotlin
    ActivityMetric.AVG_PACE -> "Avg pace"
```

In `ActivityMetricsConfigScreen.kt` `metricLabel(...)` add the same branch:

```kotlin
    ActivityMetric.AVG_PACE -> "Avg pace"
```

- [ ] **Step 5: Run (verify pass)** — same command as Step 2, then full suite `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 6: Detekt** — clean.

- [ ] **Step 7: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetric.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityScreen.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityMetricsConfigScreen.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetricTest.kt
git commit -m "feat(activity): AVG_PACE metric over moving time

Renders average pace (sec/unit, nameplate tag A) from movingTimeMs/distance,
consistent with the PACE metric. Adds the required render/human/label branches.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 10: Enable AVG_PACE in the default recording metric set

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetricsConfig.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetricsJsonTest.kt`

**Interfaces:**
- Consumes: `ActivityMetric.AVG_PACE` (Task 9).
- Produces: `AVG_PACE` in `RECORDING_METRICS`. Existing stored configs auto-migrate via `ActivityMetricsJson.merge` (missing canonical metrics are appended enabled) — no migration code needed.

- [ ] **Step 1: Write the failing tests** — add to `ActivityMetricsJsonTest.kt`:

```kotlin
    @Test fun avgPaceIsInDefaultRecordingSet() {
        assertTrue(ActivityMetricsConfig.RECORDING_METRICS.contains(ActivityMetric.AVG_PACE))
    }

    @Test fun oldStoredConfigGainsAvgPaceOnDecode() {
        // A config persisted before AVG_PACE existed (recording lacks it).
        val legacy = """{"recording":[{"m":"PACE","e":true},{"m":"DISTANCE","e":true},
            {"m":"TIME","e":true},{"m":"ORIENTATION","e":true},{"m":"ALTITUDE","e":true},
            {"m":"PRESSURE","e":true}],"glance":[{"m":"ORIENTATION","e":true}]}""".trimIndent()
        val decoded = ActivityMetricsJson.decode(legacy)
        assertTrue(decoded.recording.any { it.metric == ActivityMetric.AVG_PACE && it.enabled })
    }
```

(If `ActivityMetricsJsonTest.kt` does not already import `assertTrue`, add `import kotlin.test.assertTrue`.)

- [ ] **Step 2: Run (verify failure)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.activity.ActivityMetricsJsonTest"`
Expected: FAIL — `AVG_PACE` not in `RECORDING_METRICS`.

- [ ] **Step 3: Add AVG_PACE to `RECORDING_METRICS`**

In `ActivityMetricsConfig.kt`, append to the `RECORDING_METRICS` list (order: after `PACE` so the two pace readouts sit together):

```kotlin
        val RECORDING_METRICS = listOf(
            ActivityMetric.PACE,
            ActivityMetric.AVG_PACE,
            ActivityMetric.DISTANCE,
            ActivityMetric.TIME,
            ActivityMetric.ORIENTATION,
            ActivityMetric.ALTITUDE,
            ActivityMetric.PRESSURE,
        )
```

- [ ] **Step 4: Run (verify pass)** — same command as Step 2, then full suite. Expected: PASS.

- [ ] **Step 5: Detekt** — clean.

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetricsConfig.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivityMetricsJsonTest.kt
git commit -m "feat(activity): include AVG_PACE in default recording metrics

Existing stored configs auto-migrate via the codec's defaults-merge (missing
canonical metrics are appended enabled).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Phase 5 — Control & service plumbing

### Task 11: Pause/resume + interval through launcher, controller, and service

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionLauncher.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/vm/ActivityController.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/activity/AndroidActivitySessionLauncher.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionService.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/vm/ActivityControllerTest.kt`

**Interfaces:**
- Consumes: `engine.pause/resume` (Task 6/8), `Prefs.activityPushIntervalMs` + `Prefs.PUSH_INTERVAL_PRESETS_MS` (Task 2).
- Produces:
  - `ActivitySessionLauncher.pause()`, `.resume()` (+ Noop no-ops; **ripple:** `AndroidActivitySessionLauncher` and the test `FakeLauncher` must implement them).
  - `ActivityController.onPause()`, `.onResume()`, `val pushIntervalMs: Long`, `val pushIntervalPresetsMs: List<Long>`, `fun setPushInterval(ms: Long)` (idle-only; rejected with a snackbar while running).
  - `ActivitySessionService.ACTION_PAUSE/ACTION_RESUME` + `pause(context)`/`resume(context)`.

- [ ] **Step 1: Write failing controller tests** — first extend the test `FakeLauncher` so its state is mutable and it records pause/resume:

```kotlin
    private class FakeLauncher : ActivitySessionLauncher {
        val stateFlow = MutableStateFlow(ActivityState())
        override val state: StateFlow<ActivityState> = stateFlow
        val calls = mutableListOf<String>()
        override fun start() { calls += "start" }
        override fun startLive() { calls += "startLive" }
        override fun stop() { calls += "stop" }
        override fun cycleMetric() { calls += "cycle" }
        override fun setUnit(unit: ActivityUnit) { calls += "setUnit($unit)" }
        override fun pause() { calls += "pause" }
        override fun resume() { calls += "resume" }
    }
```

Add tests:

```kotlin
    @Test fun onPause_and_onResume_delegate_to_launcher() {
        val l = FakeLauncher()
        val c = controller(l, Prefs(MapSettings()), permission = true, mutableListOf())
        c.onPause(); c.onResume()
        assertEquals(listOf("pause", "resume"), l.calls)
    }

    @Test fun setPushInterval_while_idle_persists_a_preset() {
        val prefs = Prefs(MapSettings())
        controller(FakeLauncher(), prefs, permission = true, mutableListOf()).setPushInterval(15_000L)
        assertEquals(15_000L, prefs.activityPushIntervalMs)
    }

    @Test fun setPushInterval_ignores_non_preset_values() {
        val prefs = Prefs(MapSettings())
        controller(FakeLauncher(), prefs, permission = true, mutableListOf()).setPushInterval(25_000L)
        assertEquals(30_000L, prefs.activityPushIntervalMs) // unchanged default
    }

    @Test fun setPushInterval_while_running_is_rejected_with_snackbar() {
        val launcher = FakeLauncher().apply { stateFlow.value = ActivityState(running = true) }
        val prefs = Prefs(MapSettings())
        val snackbars = mutableListOf<String>()
        controller(launcher, prefs, permission = true, snackbars).setPushInterval(3_000L)
        assertEquals(30_000L, prefs.activityPushIntervalMs)
        assertEquals(1, snackbars.size)
    }
```

- [ ] **Step 2: Run (verify failure)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.vm.ActivityControllerTest"`
Expected: FAIL — `pause`/`resume`/`setPushInterval` unresolved (and the interface won't compile until Step 3).

- [ ] **Step 3: Add to the launcher interface + Noop**

In `ActivitySessionLauncher.kt`, add to the interface:

```kotlin
    fun pause()
    fun resume()
```

and to `NoopActivitySessionLauncher`:

```kotlin
    override fun pause() = Unit
    override fun resume() = Unit
```

- [ ] **Step 4: Implement in the Android launcher**

In `AndroidActivitySessionLauncher.kt`:

```kotlin
    override fun pause() = ActivitySessionService.pause(context)
    override fun resume() = ActivitySessionService.resume(context)
```

- [ ] **Step 5: Add service actions**

In `ActivitySessionService.kt` companion, add the actions + senders:

```kotlin
        const val ACTION_PAUSE = "com.blizzardcaron.freeolleefaces.activity.PAUSE"
        const val ACTION_RESUME = "com.blizzardcaron.freeolleefaces.activity.RESUME"
```

```kotlin
        fun pause(context: Context) = send(context, ACTION_PAUSE, foreground = false)
        fun resume(context: Context) = send(context, ACTION_RESUME, foreground = false)
```

In `onStartCommand`'s `when`, add (engine.pause/resume are non-suspend, like `cycleMetric`):

```kotlin
            ACTION_PAUSE -> engine.pause(System.currentTimeMillis())
            ACTION_RESUME -> engine.resume(System.currentTimeMillis())
```

- [ ] **Step 6: Add controller methods**

In `ActivityController.kt`:

```kotlin
    val pushIntervalMs: Long get() = prefs.activityPushIntervalMs
    val pushIntervalPresetsMs: List<Long> get() = Prefs.PUSH_INTERVAL_PRESETS_MS

    fun onPause() = launcher.pause()
    fun onResume() = launcher.resume()

    /** Idle-only: the engine reads the interval once at session start (no mid-activity changes). */
    fun setPushInterval(ms: Long) {
        if (state.value.running) {
            showSnackbar("Stop the activity to change the push interval.")
            return
        }
        if (ms in Prefs.PUSH_INTERVAL_PRESETS_MS) prefs.activityPushIntervalMs = ms
    }
```

- [ ] **Step 7: Run (verify pass)** — Step 1 command, then full `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 8: Detekt + commit**

Run `./gradlew :app:detekt` (clean), then:

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionLauncher.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/vm/ActivityController.kt app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/activity/AndroidActivitySessionLauncher.kt app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/activity/ActivitySessionService.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/vm/ActivityControllerTest.kt
git commit -m "feat(activity): pause/resume + push-interval control plumbing

Launcher/controller/service gain pause/resume; controller exposes the interval
pref (idle-only setter rejected mid-activity with a snackbar).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Phase 6 — UI

> **On-device verification (release process):** Phase 6 changes are visual/behavioral. After the suite + detekt pass, hardware-verify on the watch+phone: (1) interval picker persists and the watch push cadence visibly slows at 30s/5m; (2) Pause shows `PAUSE` on the nameplate and freezes distance/time; (3) auto-pause triggers when you stand still on a walk and resumes when moving; (4) Avg pace reads plausibly; (5) the Activity tab opens on the idle home, not straight into the glance. Do NOT declare done until verified on hardware.

**Callback/signature ripple (applies to Tasks 12–14):** `ActivityScreen` and `ActivityCallbacks` are also constructed in screenshot fixtures. After changing their signatures, run `grep -rn "ActivityScreen(\|ActivityCallbacks(" app/src` and update every call site (notably under `app/src/screenFixtures`). The build fails until all are updated — treat that as the gate.

### Task 12: Push-interval picker on the idle Activity home

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityScreen.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/AppScreenTabs.kt`
- Modify: fixture call sites surfaced by the grep above.

**Interfaces:**
- Consumes: `ActivityController.pushIntervalMs`, `.pushIntervalPresetsMs`, `.setPushInterval` (Task 11).
- Produces: `ActivityCallbacks.onSelectInterval: (Long) -> Unit`; `ActivityScreen(... pushIntervalMs: Long, intervalPresetsMs: List<Long> ...)`; an interval chip row in `IdleContent`.

- [ ] **Step 1: Extend `ActivityCallbacks`** — add the field:

```kotlin
    val onSelectInterval: (Long) -> Unit,
```

- [ ] **Step 2: Add params + picker to `ActivityScreen`**

Add to the `ActivityScreen` signature (and thread into `IdleContent`):

```kotlin
    pushIntervalMs: Long,
    intervalPresetsMs: List<Long>,
```

Add a picker composable and call it from `IdleContent` (after the unit toggle, before the last-activity/history section):

```kotlin
@Composable
private fun IntervalPicker(
    selectedMs: Long,
    presetsMs: List<Long>,
    onSelect: (Long) -> Unit,
) {
    Text("Watch update interval", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (ms in presetsMs) {
            val selected = ms == selectedMs
            if (selected) {
                Button(onClick = { onSelect(ms) }, modifier = Modifier.weight(1f)) { Text(intervalLabel(ms)) }
            } else {
                OutlinedButton(onClick = { onSelect(ms) }, modifier = Modifier.weight(1f)) { Text(intervalLabel(ms)) }
            }
        }
    }
}

private const val MS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE_UI = 60L

private fun intervalLabel(ms: Long): String {
    val seconds = ms / MS_PER_SECOND
    return if (seconds < SECONDS_PER_MINUTE_UI) "${seconds}s" else "${seconds / SECONDS_PER_MINUTE_UI}m"
}
```

Call it inside `IdleContent`:

```kotlin
    IntervalPicker(pushIntervalMs, intervalPresetsMs, callbacks.onSelectInterval)
```

(Thread `pushIntervalMs`/`intervalPresetsMs` from `ActivityScreen` into `IdleContent`'s parameter list.)

- [ ] **Step 3: Wire in `AppScreenTabs.ActivityTab`** — add to the `ActivityScreen(...)` call:

```kotlin
        pushIntervalMs = viewModel.activity.pushIntervalMs,
        intervalPresetsMs = viewModel.activity.pushIntervalPresetsMs,
```

and to `ActivityCallbacks(...)`:

```kotlin
            onSelectInterval = { viewModel.activity.setPushInterval(it) },
```

- [ ] **Step 4: Fix fixture call sites** — update every site from the grep (pass a fixed `pushIntervalMs = 30_000L`, `intervalPresetsMs = com.blizzardcaron.freeolleefaces.prefs.Prefs.PUSH_INTERVAL_PRESETS_MS`, and `onSelectInterval = {}`).

- [ ] **Step 5: Build + detekt**

Run: `./gradlew :app:assembleDebug :app:detekt`
Expected: BUILD SUCCESSFUL, detekt clean. (No new unit test — this is Compose glue; the on-device check in the Phase 6 banner covers behavior.)

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityScreen.kt app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/AppScreenTabs.kt app/src/screenFixtures
git commit -m "feat(activity): push-interval picker on the idle Activity home

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 13: Pause/Resume button while recording

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityScreen.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/AppScreenTabs.kt`
- Modify: fixture call sites (grep as above).

**Interfaces:**
- Consumes: `ActivityController.onPause/onResume` (Task 11), `ActivityState.paused`.
- Produces: `ActivityCallbacks.onPause: () -> Unit`, `.onResume: () -> Unit`; a Pause/Resume button in `RunningContent` (recording only).

- [ ] **Step 1: Extend `ActivityCallbacks`**:

```kotlin
    val onPause: () -> Unit,
    val onResume: () -> Unit,
```

- [ ] **Step 2: Add the button in `RunningContent`** — inside the `if (state.recording) { ... }` control area, add a full-width pause toggle below the MODE/Stop row:

```kotlin
    if (state.recording) {
        if (state.paused) {
            Button(onClick = callbacks.onResume, modifier = Modifier.fillMaxWidth()) { Text("Resume") }
        } else {
            OutlinedButton(onClick = callbacks.onPause, modifier = Modifier.fillMaxWidth()) { Text("Pause") }
        }
    }
```

- [ ] **Step 3: Wire in `AppScreenTabs`** — add to `ActivityCallbacks(...)`:

```kotlin
            onPause = { viewModel.activity.onPause() },
            onResume = { viewModel.activity.onResume() },
```

- [ ] **Step 4: Fix fixture call sites** — add `onPause = {}`, `onResume = {}` to each.

- [ ] **Step 5: Build + detekt** — `./gradlew :app:assembleDebug :app:detekt`. Expected: SUCCESS + clean.

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityScreen.kt app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/AppScreenTabs.kt app/src/screenFixtures
git commit -m "feat(activity): Pause/Resume button while recording

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 14: Embed recent history on the Activity home

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityScreen.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/AppScreenTabs.kt`
- Modify: fixture call sites (grep as above).

**Interfaces:**
- Consumes: `AppViewModel.activityHistory(): List<ActivityTrack>`, `AppViewModel.openActivity(id)`.
- Produces: `ActivityScreen(... recent: List<ActivityTrack> ...)` replacing the single `lastSummary`; `ActivityCallbacks.onOpenActivity: (String) -> Unit`; a compact clickable recent-activities list in `IdleContent` (keep the full-screen "History" button).

- [ ] **Step 1: Change `ActivityScreen` signature** — replace `lastSummary: ActivitySummary?` with:

```kotlin
    recent: List<ActivityTrack>,
```

and add to `ActivityCallbacks`:

```kotlin
    val onOpenActivity: (String) -> Unit,
```

- [ ] **Step 2: Replace the last-activity card in `IdleContent`** with a short list (cap at a small constant so the home stays glanceable):

```kotlin
private const val RECENT_LIMIT = 3

@Composable
private fun RecentActivities(
    recent: List<ActivityTrack>,
    unit: ActivityUnit,
    onOpen: (String) -> Unit,
) {
    if (recent.isEmpty()) return
    Text("Recent activities", fontWeight = FontWeight.Bold)
    for (track in recent.take(RECENT_LIMIT)) {
        Card(
            elevation = CardDefaults.cardElevation(),
            modifier = Modifier.fillMaxWidth(),
            onClick = { onOpen(track.id) },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Distance ${distanceText(track.summary.distanceM, unit)}")
                Text("Time ${hms(track.summary.elapsedTimeMs)}")
                Text("Avg pace ${paceText(track.summary.avgPaceSecPerKm, unit)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
```

Call it in `IdleContent` where the old `if (lastSummary != null) { ... }` card was:

```kotlin
    RecentActivities(recent, unit, callbacks.onOpenActivity)
```

(If `Card(onClick = ...)` needs `androidx.compose.material3.ExperimentalMaterial3Api`, prefer wrapping the existing `Card` content in a `Column(Modifier.clickable { onOpen(track.id) })` to avoid the opt-in; use whichever the codebase already uses elsewhere — check `ActivityHistoryScreen.kt` for the established list-row pattern and match it.)

- [ ] **Step 3: Wire in `AppScreenTabs.ActivityTab`** — replace `lastSummary = ...` with:

```kotlin
        recent = viewModel.activityHistory(),
```

and add to callbacks:

```kotlin
            onOpenActivity = { viewModel.openActivity(it) },
```

Also add `viewModel.historyRevision` read at the top of `ActivityTab` (as `ActivityHistoryTab` does) so the home list refreshes after a delete.

- [ ] **Step 4: Fix fixture call sites** — pass a small fixed `recent = emptyList()` (or a sample list the fixture already builds) and `onOpenActivity = {}`.

- [ ] **Step 5: Build + detekt** — `./gradlew :app:assembleDebug :app:detekt`. Expected: SUCCESS + clean.

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/ActivityScreen.kt app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/AppScreenTabs.kt app/src/screenFixtures
git commit -m "feat(activity): embed recent activities on the Activity home

Replaces the single last-activity card with a compact clickable recent list;
the full History screen stays reachable via its button.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 15: Activity tab opens on the idle home (not glance-first)

**Files:**
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/AppScreenTabs.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: entering the Activity tab no longer auto-starts the live instrument glance; it shows the idle home (Start / interval picker / recent list). The glance stays reachable via the "Instrument glance" button.

- [ ] **Step 1: Remove the glance auto-start** — delete the `LaunchedEffect(Unit) { if (!activityState.running && hasLocation()) viewModel.activity.onShowLive() }` block in `ActivityTab` (lines ~54–59). If a running session is re-entered, `ActivityScreen` already renders `RunningContent` from `activityState.running`, so nothing else changes.

- [ ] **Step 2: Build + detekt** — `./gradlew :app:assembleDebug :app:detekt`. Expected: SUCCESS + clean.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/AppScreenTabs.kt
git commit -m "feat(activity): open the Activity tab on the idle home

Stops auto-launching the instrument glance on tab entry; it stays reachable via
its button.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Phase 7 — Release wrap-up

### Task 16: Version bump, docs, full verification, plan cleanup

**Files:**
- Modify: `VERSION`
- Modify: `README.md` / changelog (feature entry — text authored by the controller/Opus, not delegated)
- Delete: `plans/activity-mode-implementation-plan.md` (superseded Copilot plan)
- Delete: `.superpowers/sdd/progress.md` (stale tracker for the old plan)

- [ ] **Step 1: Full green build** — run all three gates and confirm each:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:detekt
./gradlew :app:assembleDebug
```

Expected: all SUCCESSFUL / clean.

- [ ] **Step 2: Bump VERSION** (release process: bump BEFORE any push) — set `VERSION` to `0.32.0`.

- [ ] **Step 3: Docs** — add a README feature entry for the activity upgrades (configurable watch update interval, pause/auto-pause, average pace, recent-activities home). Controller authors this prose directly.

- [ ] **Step 4: Remove the superseded plan + stale tracker**

```bash
git rm plans/activity-mode-implementation-plan.md .superpowers/sdd/progress.md
```

- [ ] **Step 5: Hardware verification** — complete the on-device checks listed in the Phase 6 banner. Do not proceed to release until all pass.

- [ ] **Step 6: Commit + release** — follow the release checklist (fetch + rebase on latest `main`; confirm `v0.32.0` tag is free; respect the no-push window 9 AM–5 PM MT Mon–Fri; push branch, open PR, cut the release only after CI is green).

```bash
git add VERSION README.md
git commit -m "chore(release): activity upgrades — v0.32.0

Configurable push interval, manual + auto pause with moving-time average pace,
and a consolidated Activity home. Removes the superseded Copilot plan.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage** (against the four subsystems + Global Constraints):
- Configurable push interval / battery → Tasks 2, 3, 4, 11, 12. Interval gates ALL writes (Task 3); presets locked (Task 2); no mid-activity change (Task 11 reject + engine reads once, Task 4).
- Manual + auto pause → Tasks 5, 6, 7, 8, 11, 13. Manual precedence over auto-resume (Task 8); GPS-loss disables auto-pause (Task 7); paused nameplate + distance/time freeze (Tasks 5, 6).
- Average pace over moving time → Tasks 5 (moving time), 9 (metric), 10 (default set).
- UI consolidation → Tasks 12 (interval), 13 (pause), 14 (recent history), 15 (land on home).
- Always-recording: unchanged (already true) — no task needed.

**Type consistency:** `pause(nowMs: Long)`/`resume(nowMs: Long)` consistent across `ActivitySession` (Task 5), engine (Tasks 6/8), launcher/controller/service (Task 11, no nowMs at the boundary — the service supplies `System.currentTimeMillis()`). `PauseSource` introduced in Task 6, reused in Task 8. `movingTimeMs` added to `ActivityState` (Task 5) and consumed by `AVG_PACE` (Task 9). `ActivityPushDecider.shouldPush(..., minSpacingMs)` (Task 3) called by `NameplatePusher.maybePush(..., minSpacingMs)` (Task 4). `ActivityCallbacks` grows `onSelectInterval` (T12), `onPause`/`onResume` (T13), `onOpenActivity` (T14) — all wired in `AppScreenTabs` and fixtures.

**Placeholder scan:** none — every code step carries concrete code; UI tasks name exact call-site grep + fixture updates as their gate.

**Known green-build guards:** Task 3 defaults `minSpacingMs` so the old call site compiles; the launcher-interface change (Task 11) explicitly updates Noop + Android + FakeLauncher in the same task; enum ripple (Task 9) updates all 4 `when`s in one task.

## Execution Handoff

Plan complete. Two execution options:
1. **Subagent-Driven (recommended)** — a fresh subagent per task, two-stage review between tasks. Pure-logic tasks (2–10) gate cleanly on `:app:testDebugUnitTest` + `:app:detekt`; delegate the whole-new-file Task 7 to `claude-local` per the Compute Offload section.
2. **Inline Execution** — batch tasks in this session with checkpoints (`superpowers:executing-plans`).



