# World Time (fix + slots + swap) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `docs/superpowers/specs/2026-07-09-world-time-design.md` (issue #34)

**Goal:** Stop background badge pushes from corrupting the watch's World Time face, and ship a Casio-style world-time feature: up to 4 IANA-zone slots with one-tap switching plus a home↔world swap.

**Architecture:** The app owns the world-time value (spec "Approach A"). A pure `worldtime` package computes the 4-byte offset header that every weekday-register (`0x34`) write must carry; the hardcoded `WEEKDAY_PREFIX` capture constant is deleted. A `WorldTimeController` drives a new home-screen card; the existing background chain gains a write-on-change maintenance step. The swap rewrites the watch clock via the `02 23` frame, decoded in Phase 0.

**Tech Stack:** Kotlin Multiplatform (commonMain/androidMain), Compose Multiplatform, `kotlinx-datetime` (already a dependency), `russhwolf.settings` for Prefs, hand-written fakes in commonTest.

## Global Constraints

- Gates for every task: `./gradlew :app:testDebugUnitTest` and `./gradlew :app:detekt` — both must pass with **zero new detekt findings** (no baseline additions; name all magic numbers as consts, matching `OlleeProtocol.kt` style).
- Branch: `feat/world-time` off current `main`, in place (no worktree — cold Gradle cache on the Pi).
- Root `VERSION` file bumps to `0.36.0` in Task 1, **before any push**.
- No pushes / public-repo activity 9 AM–5 PM MT Mon–Fri. Local commits fine anytime.
- Commit style: conventional commits with scope (`feat(worldtime): …`), ending with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- **Encoding hypothesis (pending Task 10 hardware confirmation):** the weekday-register 4-byte header is the World Time UTC offset as big-endian two's-complement **seconds** (captured `00 00 7E 90` = +32,400 s = +9:00; big-endian matches the `0x32` config register convention). All Wave A code isolates this in `WorldTimeCodec`; Task 11 reconciles it against Phase 0 findings.
- Never `adb uninstall` the release app; debug builds carry the `.debug` suffix and coexist.

## Delegation (claude-local offload)

Route by task shape (see `~/.claude` memory: claude-local handles **atomic whole-new-file** tasks only; never multi-line edits to existing files):

| Task | Shape | Route | Gate |
|---|---|---|---|
| 2 (`WorldTimeCodec` + test) | 2 new files | **claude-local** | testDebugUnitTest + detekt |
| 3 (`WorldTime` model + test) | 2 new files | **claude-local** | testDebugUnitTest + detekt |
| 6 (`WorldTimeReadback` + test) | 2 new files + one single-line const add | **claude-local** | testDebugUnitTest + detekt |
| 8a (`WorldTimeCard` composable) | 1 new file (wiring stays controller) | **claude-local draft**, controller visual-verifies | detekt + compile, then verify-visual |
| 12 (`SetClock` + test) | 2 new files | **claude-local** (after Task 10) | testDebugUnitTest + detekt |
| 1, 4, 5, 7, 8b, 9, 11, 13–15 | modify-existing / judgment / hardware | **controller (Opus)** | same gates |

Every delegated output is a draft: controller runs the gate and `git diff`-verifies before accepting.

## Wave structure

- **Wave A (Tasks 1–9):** executable now, no hardware needed. Ships the corruption fix and the slots feature (no swap).
- **Task 10 — HARD BLOCK (needs Ken + watch + capture rig):** Phase 0 protocol verification.
- **Wave B (Tasks 11–13):** constants reconcile, set-clock builder, swap. Gated on Task 10.
- **Tasks 14–15 — HARD BLOCK (hardware / push window):** on-device verification, whole-branch review, PR, release.

## File map

Create (commonMain unless noted):
- `worldtime/WorldTimeCodec.kt` — header bytes ↔ offset seconds (the only file that knows the encoding).
- `worldtime/WorldTime.kt` — `WorldTimeState` + pure logic: zone offsets, `headerFor`, `reconcile`, labels.
- `worldtime/WorldTimeHeader.kt` — thin Prefs→header adapter used by every weekday-write call site.
- `worldtime/WorldTimeReadback.kt` — `0x35` read + header parse (mirrors `BatteryReadback`).
- `worldtime/SetClock.kt` (Wave B) — `02 23` clock-write builder.
- `vm/WorldTimeController.kt` — chip taps, add/remove, reconcile-on-open, swap (Wave B).
- `ui/WorldTimeCard.kt` — card + zone-picker dialog.
- Tests mirroring each under `commonTest/…/worldtime/` and `vm/`.

Modify:
- `ble/OlleeProtocol.kt` — `buildWeekdayPacket(slots, header)`, delete `WEEKDAY_PREFIX`, add `TARGET_GET_WEEKDAYS = 0x35`.
- `notifications/NotificationCount.kt` — `packetFor(n, header)`.
- `prefs/Prefs.kt` — world-time fields.
- `vm/ComplicationController.kt:98-106` — header at the badge call site.
- `androidMain/…/notifications/NotificationCountService.kt:91` — same.
- `androidMain/…/auto/AutoUpdateWorker.kt:60,105-111` — same + `maybeMaintainWorldTime`.
- `ui/HomeState.kt`, `ui/Callbacks.kt`, `ui/ComplicationCardId.kt`, `ui/HomeScreen.kt` — card wiring.
- `AppViewModel.kt` (~line 130) — construct `WorldTimeController`.
- `androidMain/…/MainActivity.kt:151` and `:227` — reconcile-on-open + callbacks.
- `screenFixtures/…/screens/ScreenFakes.kt:67`, `debug/…/ScreenshotHostActivity.kt:80` — only if new callbacks lack defaults (they won't; all new callbacks default to `{}`).
- `docs/reference/ollee-ble-protocol.md` (Task 11) — verified rows.
- `VERSION` (Task 1).

---

### Task 1: Branch + VERSION bump

**Files:**
- Modify: `VERSION` (repo root)

- [ ] **Step 1: Create the branch**

```bash
git checkout -b feat/world-time main
```

- [ ] **Step 2: Bump VERSION**

Overwrite the single line in `VERSION`: `0.35.1` → `0.36.0`. Confirm no `v0.36.0` tag exists: `git tag -l 'v0.36.0'` → empty.

- [ ] **Step 3: Commit**

```bash
git add VERSION
git commit -m "chore: bump version to 0.36.0 for world time release"
```

---

### Task 2: `WorldTimeCodec` — header bytes ↔ offset seconds

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTimeCodec.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTimeCodecTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `WorldTimeCodec.HEADER_SIZE: Int` (= 4), `WorldTimeCodec.MAX_OFFSET_SEC: Int` (= 64_800), `fun encode(offsetSeconds: Int): ByteArray` (requires |offset| ≤ MAX_OFFSET_SEC), `fun decode(header: ByteArray): Int?` (null on wrong size or out-of-range value).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.worldtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorldTimeCodecTest {

    @Test
    fun encodesCapturedTokyoOffset() {
        // The 2026-05-31 capture constant: +9 h = 32_400 s = 0x00007E90.
        assertContentEquals(
            byteArrayOf(0x00, 0x00, 0x7E, 0x90.toByte()),
            WorldTimeCodec.encode(32_400),
        )
    }

    @Test
    fun encodesNegativeOffsetAsTwosComplement() {
        // MDT (UTC-6): -21_600 s = 0xFFFFABA0 big-endian two's complement.
        assertContentEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xAB.toByte(), 0xA0.toByte()),
            WorldTimeCodec.encode(-21_600),
        )
    }

    @Test
    fun encodesZeroAndHalfHourZones() {
        assertContentEquals(byteArrayOf(0, 0, 0, 0), WorldTimeCodec.encode(0))
        // IST (+5:30) = 19_800 s = 0x00004D58.
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x4D, 0x58), WorldTimeCodec.encode(19_800))
    }

    @Test
    fun roundTripsAcrossTheFullRange() {
        for (sec in listOf(-64_800, -21_600, -3_600, 0, 19_800, 32_400, 64_800)) {
            assertEquals(sec, WorldTimeCodec.decode(WorldTimeCodec.encode(sec)))
        }
    }

    @Test
    fun rejectsOutOfRangeEncode() {
        assertFailsWith<IllegalArgumentException> { WorldTimeCodec.encode(64_801) }
        assertFailsWith<IllegalArgumentException> { WorldTimeCodec.encode(-64_801) }
    }

    @Test
    fun decodeRejectsWrongSizeAndGarbage() {
        assertNull(WorldTimeCodec.decode(ByteArray(0)))
        assertNull(WorldTimeCodec.decode(ByteArray(3)))
        assertNull(WorldTimeCodec.decode(ByteArray(5)))
        // A value far outside ±18 h is not a plausible offset — treat as unknown layout.
        assertNull(WorldTimeCodec.decode(byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorldTimeCodecTest*"`
Expected: FAIL — unresolved reference `WorldTimeCodec`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.blizzardcaron.freeolleefaces.worldtime

/**
 * Encodes/decodes the 4-byte header of the weekday-table register (`0x34` write / `0x35` read),
 * hypothesized to be the World Time face's UTC offset in big-endian two's-complement seconds
 * (pending on-device confirmation — plans/2026-07-09-world-time-plan.md Task 10). The 2026-05-31
 * capture constant `00 00 7E 90` decodes to +32,400 s = +9:00; big-endian matches the config
 * register (`0x32`/`0x33`) convention. This is the only file that knows the encoding.
 */
object WorldTimeCodec {
    const val HEADER_SIZE = 4

    private const val SECONDS_PER_HOUR = 3600
    private const val MAX_OFFSET_HOURS = 18 // ISO 8601 offset bound

    /** Largest plausible UTC offset magnitude; anything beyond it is not an offset. */
    const val MAX_OFFSET_SEC = MAX_OFFSET_HOURS * SECONDS_PER_HOUR

    private const val BYTE_MASK = 0xFF
    private const val SHIFT_24 = 24
    private const val SHIFT_16 = 16
    private const val SHIFT_8 = 8

    /** The 4-byte big-endian two's-complement encoding of [offsetSeconds]. */
    fun encode(offsetSeconds: Int): ByteArray {
        require(offsetSeconds in -MAX_OFFSET_SEC..MAX_OFFSET_SEC) {
            "offset must be within ±$MAX_OFFSET_SEC seconds (got $offsetSeconds)"
        }
        return byteArrayOf(
            ((offsetSeconds shr SHIFT_24) and BYTE_MASK).toByte(),
            ((offsetSeconds shr SHIFT_16) and BYTE_MASK).toByte(),
            ((offsetSeconds shr SHIFT_8) and BYTE_MASK).toByte(),
            (offsetSeconds and BYTE_MASK).toByte(),
        )
    }

    /** Decodes [header] to signed offset seconds; null on wrong size or implausible value. */
    fun decode(header: ByteArray): Int? {
        if (header.size != HEADER_SIZE) return null
        val value = (header[0].toInt() shl SHIFT_24) or
            ((header[1].toInt() and BYTE_MASK) shl SHIFT_16) or
            ((header[2].toInt() and BYTE_MASK) shl SHIFT_8) or
            (header[3].toInt() and BYTE_MASK)
        return value.takeIf { it in -MAX_OFFSET_SEC..MAX_OFFSET_SEC }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorldTimeCodecTest*"`
Expected: PASS (6 tests).

- [ ] **Step 5: Detekt + commit**

```bash
./gradlew :app:detekt
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTimeCodec.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTimeCodecTest.kt
git commit -m "feat(worldtime): add offset<->header codec for the weekday register prefix"
```

---

### Task 3: `WorldTime` — pure state model and offset logic

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTime.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTimeTest.kt`

**Interfaces:**
- Consumes: `WorldTimeCodec.encode` (Task 2), `kotlinx.datetime.TimeZone/Instant/offsetAt`.
- Produces:
  - `data class WorldTimeState(val slots: List<String> = emptyList(), val activeZoneId: String? = null, val customOffsetSec: Int? = null, val swapped: Boolean = false)`
  - `object WorldTime` with: `MAX_SLOTS = 4`, `fun offsetSecondsOf(zoneId: String, nowMs: Long): Int?`, `fun worldOffsetSec(state: WorldTimeState, nowMs: Long, homeZoneId: String): Int`, `fun headerFor(state: WorldTimeState, nowMs: Long, homeZoneId: String): ByteArray`, `fun reconcile(state: WorldTimeState, watchOffsetSec: Int, nowMs: Long): WorldTimeState`, `fun cityOf(zoneId: String): String`, `fun offsetLabel(sec: Int): String`, `fun timeLabel(zoneId: String, nowMs: Long): String?`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.worldtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorldTimeTest {

    // 2026-07-09T12:00Z: northern-hemisphere DST in effect (MDT=-6h, CEST=+2h, JST=+9h fixed).
    private val julyMs = 1_783_425_600_000L // 2026-07-07T12:00:00Z (any July 2026 instant works)
    // 2026-01-15T12:00Z: standard time (MST=-7h, CET=+1h).
    private val janMs = 1_768_478_400_000L

    @Test
    fun zoneOffsetsFollowDst() {
        assertEquals(-6 * 3600, WorldTime.offsetSecondsOf("America/Denver", julyMs))
        assertEquals(-7 * 3600, WorldTime.offsetSecondsOf("America/Denver", janMs))
        assertEquals(9 * 3600, WorldTime.offsetSecondsOf("Asia/Tokyo", julyMs))
        assertNull(WorldTime.offsetSecondsOf("Not/AZone", julyMs))
    }

    @Test
    fun headerUsesActiveZone() {
        val state = WorldTimeState(slots = listOf("Asia/Tokyo"), activeZoneId = "Asia/Tokyo")
        assertContentEquals(
            WorldTimeCodec.encode(9 * 3600),
            WorldTime.headerFor(state, julyMs, homeZoneId = "America/Denver"),
        )
    }

    @Test
    fun headerUsesHomeWhileSwapped() {
        val state = WorldTimeState(slots = listOf("Asia/Tokyo"), activeZoneId = "Asia/Tokyo", swapped = true)
        assertContentEquals(
            WorldTimeCodec.encode(-6 * 3600),
            WorldTime.headerFor(state, julyMs, homeZoneId = "America/Denver"),
        )
    }

    @Test
    fun headerFallsBackToCustomThenHome() {
        val custom = WorldTimeState(customOffsetSec = 3 * 3600)
        assertContentEquals(
            WorldTimeCodec.encode(3 * 3600),
            WorldTime.headerFor(custom, julyMs, homeZoneId = "America/Denver"),
        )
        // Unconfigured: neutral fallback — world time mirrors home until set up.
        assertContentEquals(
            WorldTimeCodec.encode(-6 * 3600),
            WorldTime.headerFor(WorldTimeState(), julyMs, homeZoneId = "America/Denver"),
        )
    }

    @Test
    fun reconcileAdoptsMatchingSlotElseCustom() {
        val state = WorldTimeState(slots = listOf("Asia/Tokyo", "Europe/Berlin"), activeZoneId = "Asia/Tokyo")
        // Watch says +2h — that's Berlin in July.
        val adopted = WorldTime.reconcile(state, 2 * 3600, julyMs)
        assertEquals("Europe/Berlin", adopted.activeZoneId)
        assertNull(adopted.customOffsetSec)
        // Watch says +4h — matches no slot: keep as custom, clear active.
        val custom = WorldTime.reconcile(state, 4 * 3600, julyMs)
        assertNull(custom.activeZoneId)
        assertEquals(4 * 3600, custom.customOffsetSec)
        // A mismatch means the watch no longer reflects our swap.
        val unswapped = WorldTime.reconcile(state.copy(swapped = true), 4 * 3600, julyMs)
        assertEquals(false, unswapped.swapped)
    }

    @Test
    fun labels() {
        assertEquals("Denver", WorldTime.cityOf("America/Denver"))
        assertEquals("New York", WorldTime.cityOf("America/New_York"))
        assertEquals("+9:00", WorldTime.offsetLabel(9 * 3600))
        assertEquals("-6:00", WorldTime.offsetLabel(-6 * 3600))
        assertEquals("+5:30", WorldTime.offsetLabel(5 * 3600 + 1800))
        assertNull(WorldTime.timeLabel("Not/AZone", julyMs))
        // 12:00Z in Tokyo (+9) is 21:00.
        assertEquals("21:00", WorldTime.timeLabel("Asia/Tokyo", julyMs))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorldTimeTest*"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.blizzardcaron.freeolleefaces.worldtime

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime

/**
 * Phone-side World Time state (spec: the app owns the value — "Approach A"). [slots] are IANA
 * zone ids in display order; [activeZoneId] is the zone currently pushed to the watch;
 * [customOffsetSec] holds an on-watch value adopted at reconcile that matched no slot;
 * [swapped] means the Casio swap is in effect (home lives in the World Time face and the
 * active zone drives the main clock).
 */
data class WorldTimeState(
    val slots: List<String> = emptyList(),
    val activeZoneId: String? = null,
    val customOffsetSec: Int? = null,
    val swapped: Boolean = false,
)

/** Pure world-time logic: zone offsets, the weekday-register header, reconcile, labels. */
object WorldTime {
    const val MAX_SLOTS = 4

    private const val SECONDS_PER_HOUR = 3600
    private const val MINUTES_PER_HOUR = 60
    private const val SECONDS_PER_MINUTE = 60
    private const val TWO_DIGITS = 2

    /** Current UTC offset of [zoneId] at [nowMs]; null for an unknown zone id. */
    fun offsetSecondsOf(zoneId: String, nowMs: Long): Int? = runCatching {
        TimeZone.of(zoneId).offsetAt(Instant.fromEpochMilliseconds(nowMs)).totalSeconds
    }.getOrNull()

    /**
     * The world-side offset: the active zone's current (DST-correct) offset, else the adopted
     * custom offset, else home's offset — a neutral fallback so an unconfigured install never
     * stamps a foreign zone (the +9 capture-constant bug this feature fixes).
     */
    fun worldOffsetSec(state: WorldTimeState, nowMs: Long, homeZoneId: String): Int =
        state.activeZoneId?.let { offsetSecondsOf(it, nowMs) }
            ?: state.customOffsetSec
            ?: (offsetSecondsOf(homeZoneId, nowMs) ?: 0)

    /**
     * The 4-byte header every weekday-register write must carry: home's offset while swapped
     * (home is displayed in the World Time face), else the world offset.
     */
    fun headerFor(state: WorldTimeState, nowMs: Long, homeZoneId: String): ByteArray {
        val sec = if (state.swapped) {
            offsetSecondsOf(homeZoneId, nowMs) ?: 0
        } else {
            worldOffsetSec(state, nowMs, homeZoneId)
        }
        return WorldTimeCodec.encode(sec)
    }

    /**
     * Adopts an on-watch offset that differs from our expectation (call only on mismatch): the
     * slot matching [watchOffsetSec] right now becomes active, otherwise the offset is kept as
     * custom. Either way `swapped` clears — a mismatch means the watch no longer reflects our
     * swap. The app never fights the user's on-watch change at reconcile time.
     */
    fun reconcile(state: WorldTimeState, watchOffsetSec: Int, nowMs: Long): WorldTimeState {
        val match = state.slots.firstOrNull { offsetSecondsOf(it, nowMs) == watchOffsetSec }
        return if (match != null) {
            state.copy(activeZoneId = match, customOffsetSec = null, swapped = false)
        } else {
            state.copy(activeZoneId = null, customOffsetSec = watchOffsetSec, swapped = false)
        }
    }

    /** "America/New_York" → "New York" — the picker/chip display name. */
    fun cityOf(zoneId: String): String = zoneId.substringAfterLast('/').replace('_', ' ')

    /** "+9:00", "-6:00", "+5:30" — offset chip text. */
    fun offsetLabel(sec: Int): String {
        val sign = if (sec < 0) "-" else "+"
        val abs = if (sec < 0) -sec else sec
        val h = abs / SECONDS_PER_HOUR
        val m = (abs % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        return "$sign$h:${m.toString().padStart(TWO_DIGITS, '0')}"
    }

    /** Current wall time in [zoneId] as "HH:MM"; null for an unknown zone id. */
    fun timeLabel(zoneId: String, nowMs: Long): String? = runCatching {
        val local = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(TimeZone.of(zoneId))
        "${local.hour.toString().padStart(TWO_DIGITS, '0')}:${local.minute.toString().padStart(TWO_DIGITS, '0')}"
    }.getOrNull()
}
```

Note: if detekt flags `MINUTES_PER_HOUR` as unused, delete it — only `SECONDS_PER_MINUTE` is used.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorldTimeTest*"`
Expected: PASS. If `julyMs`/`janMs` land on unexpected dates, recompute: the tests only need *any* instant in July 2026 / January 2026 — adjust the constant, not the assertions.

- [ ] **Step 5: Detekt + commit**

```bash
./gradlew :app:detekt
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTime.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTimeTest.kt
git commit -m "feat(worldtime): pure state model, DST-correct offsets, reconcile rule"
```

---

### Task 4: Prefs world-time fields

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/prefs/WorldTimePrefsTest.kt` (new file; existing Prefs tests live in this package — follow their `MapSettings`/fake-Settings pattern, check `prefs/` test dir for the harness they use)

**Interfaces:**
- Consumes: `WorldTimeState` (Task 3), `com.russhwolf.settings.Settings`.
- Produces on `Prefs`: `var worldTimeSlots: List<String>`, `var worldTimeActiveZone: String?`, `var worldTimeCustomOffsetSec: Int?`, `var worldTimeSwapped: Boolean`, `var worldTimeLastPushedOffsetSec: Int?`, `fun worldTimeState(): WorldTimeState`, `fun saveWorldTimeState(s: WorldTimeState)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.prefs

import com.blizzardcaron.freeolleefaces.worldtime.WorldTimeState
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorldTimePrefsTest {

    private val prefs = Prefs(MapSettings())

    @Test
    fun defaultsAreEmptyAndUnswapped() {
        assertEquals(WorldTimeState(), prefs.worldTimeState())
        assertNull(prefs.worldTimeLastPushedOffsetSec)
    }

    @Test
    fun roundTripsState() {
        val s = WorldTimeState(
            slots = listOf("Asia/Tokyo", "Europe/Berlin"),
            activeZoneId = "Asia/Tokyo",
            customOffsetSec = null,
            swapped = true,
        )
        prefs.saveWorldTimeState(s)
        assertEquals(s, prefs.worldTimeState())
    }

    @Test
    fun roundTripsCustomOffsetIncludingNegative() {
        prefs.saveWorldTimeState(WorldTimeState(customOffsetSec = -21_600))
        assertEquals(-21_600, prefs.worldTimeState().customOffsetSec)
        prefs.saveWorldTimeState(WorldTimeState(customOffsetSec = null))
        assertNull(prefs.worldTimeState().customOffsetSec)
    }

    @Test
    fun lastPushedOffsetRoundTrips() {
        prefs.worldTimeLastPushedOffsetSec = -21_600
        assertEquals(-21_600, prefs.worldTimeLastPushedOffsetSec)
        prefs.worldTimeLastPushedOffsetSec = null
        assertNull(prefs.worldTimeLastPushedOffsetSec)
    }
}
```

(If `MapSettings` isn't what the existing prefs tests use, mirror whatever they construct `Prefs` with.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorldTimePrefsTest*"`
Expected: FAIL — unresolved references on `Prefs`.

- [ ] **Step 3: Implement — add to `Prefs.kt`** (properties near the other feature blocks, keys in `companion object`)

```kotlin
    /** World Time slots: IANA zone ids, comma-joined, display order (max WorldTime.MAX_SLOTS). */
    var worldTimeSlots: List<String>
        get() = settings.getStringOrNull(KEY_WT_SLOTS)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = settings.putString(KEY_WT_SLOTS, value.joinToString(","))

    /** The slot zone currently pushed to the watch's World Time face; null = custom/unset. */
    var worldTimeActiveZone: String?
        get() = settings.getStringOrNull(KEY_WT_ACTIVE)
        set(value) = if (value == null) settings.remove(KEY_WT_ACTIVE) else settings.putString(KEY_WT_ACTIVE, value)

    /** An on-watch offset adopted at reconcile that matched no slot (seconds east of UTC). */
    var worldTimeCustomOffsetSec: Int?
        get() = if (settings.hasKey(KEY_WT_CUSTOM_SEC)) settings.getInt(KEY_WT_CUSTOM_SEC, 0) else null
        set(value) =
            if (value == null) settings.remove(KEY_WT_CUSTOM_SEC) else settings.putInt(KEY_WT_CUSTOM_SEC, value)

    /** Whether the Casio home<->world swap is in effect. */
    var worldTimeSwapped: Boolean
        get() = settings.getBoolean(KEY_WT_SWAPPED, false)
        set(value) = settings.putBoolean(KEY_WT_SWAPPED, value)

    /** The last world-offset (seconds) successfully written in a weekday-register push. */
    var worldTimeLastPushedOffsetSec: Int?
        get() = if (settings.hasKey(KEY_WT_LAST_PUSHED_SEC)) settings.getInt(KEY_WT_LAST_PUSHED_SEC, 0) else null
        set(value) =
            if (value == null) settings.remove(KEY_WT_LAST_PUSHED_SEC) else settings.putInt(KEY_WT_LAST_PUSHED_SEC, value)

    /** The persisted [WorldTimeState] snapshot. */
    fun worldTimeState(): WorldTimeState = WorldTimeState(
        slots = worldTimeSlots,
        activeZoneId = worldTimeActiveZone,
        customOffsetSec = worldTimeCustomOffsetSec,
        swapped = worldTimeSwapped,
    )

    /** Persists every field of [s] (slots, active zone, custom offset, swap flag). */
    fun saveWorldTimeState(s: WorldTimeState) {
        worldTimeSlots = s.slots
        worldTimeActiveZone = s.activeZoneId
        worldTimeCustomOffsetSec = s.customOffsetSec
        worldTimeSwapped = s.swapped
    }
```

Keys for the companion object:

```kotlin
        private const val KEY_WT_SLOTS = "world_time_slots"
        private const val KEY_WT_ACTIVE = "world_time_active_zone"
        private const val KEY_WT_CUSTOM_SEC = "world_time_custom_offset_sec"
        private const val KEY_WT_SWAPPED = "world_time_swapped"
        private const val KEY_WT_LAST_PUSHED_SEC = "world_time_last_pushed_offset_sec"
```

Import `com.blizzardcaron.freeolleefaces.worldtime.WorldTimeState`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorldTimePrefsTest*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Detekt + commit**

```bash
./gradlew :app:detekt
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/prefs/WorldTimePrefsTest.kt
git commit -m "feat(worldtime): persist slots, active zone, swap flag in Prefs"
```

---

### Task 5: THE FIX — explicit header on every weekday write

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTimeHeader.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt` (lines 43, 218–230)
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/notifications/NotificationCount.kt:69-73`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/vm/ComplicationController.kt:98-106`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/notifications/NotificationCountService.kt:91`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt:105-111`
- Test: update `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocolTest.kt` and `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/notifications/NotificationCountTest.kt` (compile-fix their `buildWeekdayPacket`/`packetFor` calls; add the new tests below)

**Interfaces:**
- Consumes: `WorldTime.headerFor`, `Prefs.worldTimeState()`, `WorldTimeCodec`.
- Produces: `OlleeProtocol.buildWeekdayPacket(slots: List<String>, header: ByteArray)`, `NotificationCount.packetFor(n: Int, header: ByteArray)`, `object WorldTimeHeader { fun fromPrefs(prefs: Prefs, nowMs: Long, homeZoneId: String = TimeZone.currentSystemDefault().id): ByteArray }`. `WEEKDAY_PREFIX` no longer exists.

- [ ] **Step 1: Write the failing tests** (add to the two existing test files)

In `OlleeProtocolTest.kt`:

```kotlin
    @Test
    fun weekdayPacketCarriesExplicitHeader() {
        val header = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xAB.toByte(), 0xA0.toByte())
        val packet = OlleeProtocol.buildWeekdayPacket(
            listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU"), header,
        )
        // payload starts after the 8-byte frame preamble (00 LEN AA 55 CRC CRC 02 34)
        assertContentEquals(header, packet.copyOfRange(8, 12))
    }

    @Test
    fun weekdayPacketRejectsWrongSizeHeader() {
        assertFailsWith<IllegalArgumentException> {
            OlleeProtocol.buildWeekdayPacket(List(7) { "MO" }, ByteArray(3))
        }
    }
```

In `NotificationCountTest.kt`:

```kotlin
    @Test
    fun packetForStampsTheGivenHeaderNotTheCaptureConstant() {
        val header = WorldTimeCodec.encode(-21_600)
        val packet = NotificationCount.packetFor(3, header)
        assertContentEquals(header, packet.copyOfRange(8, 12))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*OlleeProtocolTest*" --tests "*NotificationCountTest*"`
Expected: FAIL — no such overloads.

- [ ] **Step 3: Implement**

`OlleeProtocol.kt` — delete line 43 (`private val WEEKDAY_PREFIX = …`) and change `buildWeekdayPacket` (keep the existing KDoc, extend it):

```kotlin
    /** Byte width of the weekday-register header (the World Time offset — see WorldTimeCodec). */
    const val WEEKDAY_HEADER_SIZE = 4

    /**
     * Builds the weekday-table write (0x34). [slots] must be 7 entries of exactly 2 ASCII chars,
     * in Mon..Sun order (captured default: `MO TU WE TH FR SA SU`). The firmware shows the slot
     * matching the current date in the upper-left letter pair. Pass all-identical slots (e.g.
     * `List(7){"TE"}`) to make the panel show a fixed 2-char label regardless of weekday.
     *
     * [header] is the register's 4-byte prefix — the World Time offset (WorldTimeCodec). Every
     * caller must pass the app-computed value (WorldTimeHeader.fromPrefs); replaying the captured
     * constant `00 00 7E 90` is the issue-#34 bug that reset World Time to +9:00 on every push.
     */
    fun buildWeekdayPacket(slots: List<String>, header: ByteArray): ByteArray {
        require(header.size == WEEKDAY_HEADER_SIZE) {
            "weekday header must be $WEEKDAY_HEADER_SIZE bytes (got ${header.size})"
        }
        require(slots.size == 7) { "weekday table needs 7 slots (got ${slots.size})" }
        require(slots.all { it.length == 2 && it.all { c -> c.code in 0..ASCII_MAX } }) {
            "each slot must be exactly 2 ASCII chars (got $slots)"
        }
        val payload = header + slots.joinToString("").toByteArray(Charsets.US_ASCII)
        return buildRawPacket(TARGET_WEEKDAYS, payload)
    }
```

(The literals `7` and `2` are pre-existing in these `require`s; if detekt objects after the edit, lift them to `WEEKDAY_SLOT_COUNT`/`WEEKDAY_SLOT_CHARS` consts.)

`NotificationCount.kt`:

```kotlin
    /**
     * The weekday-table BLE packet for [n]: the formatted count in all 7 slots (so it shows
     * regardless of the current day), or the real weekday table when [n] is zero. [header] is
     * the register's World Time offset prefix — always pass the app-computed value
     * (WorldTimeHeader.fromPrefs); see issue #34.
     */
    fun packetFor(n: Int, header: ByteArray): ByteArray {
        val label = format(n)
        val slots = if (label == null) REAL_WEEKDAYS else List(WEEKDAY_SLOT_COUNT) { label }
        return OlleeProtocol.buildWeekdayPacket(slots, header)
    }
```

New `WorldTimeHeader.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.worldtime

import com.blizzardcaron.freeolleefaces.prefs.Prefs
import kotlinx.datetime.TimeZone

/**
 * The one place weekday-write call sites get their 4-byte register header from: the
 * app-computed World Time offset (spec Approach A — the app owns the value).
 */
object WorldTimeHeader {
    fun fromPrefs(
        prefs: Prefs,
        nowMs: Long,
        homeZoneId: String = TimeZone.currentSystemDefault().id,
    ): ByteArray = WorldTime.headerFor(prefs.worldTimeState(), nowMs, homeZoneId)
}
```

Call sites — each computes the header once, sends, and **stamps `prefs.worldTimeLastPushedOffsetSec` on success**:

`ComplicationController.pushCountIfWatch()` (line 98) becomes:

```kotlin
    fun pushCountIfWatch() {
        val addr = prefs.watchAddress ?: return
        val header = WorldTimeHeader.fromPrefs(prefs, nowMs())
        val packet = NotificationCount.packetFor(prefs.notificationCount, header)
        scope.launch {
            ble.sendPacket(addr, packet)
                .onSuccess {
                    prefs.worldTimeLastPushedOffsetSec = WorldTimeCodec.decode(header)
                    showSnackbar("Sent notifications: ${prefs.notificationCount}")
                }
                .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
        }
    }
```

`NotificationCountService.kt:91` — same pattern (it has `prefs`; use `System.currentTimeMillis()` for nowMs):

```kotlin
                .sendPacket(addr, NotificationCount.packetFor(prefs.notificationCount, header))
                .onSuccess { prefs.worldTimeLastPushedOffsetSec = WorldTimeCodec.decode(header) }
```

with `val header = WorldTimeHeader.fromPrefs(prefs, System.currentTimeMillis())` computed just above. Read the surrounding function first and keep its existing result handling intact.

`AutoUpdateWorker.maybePushNotificationCount()` (lines 105–111) becomes:

```kotlin
    private suspend fun maybePushNotificationCount(ctx: Context, prefs: Prefs, address: String?) {
        if (!prefs.notificationsEnabled || address == null || inSleepNow(prefs)) return
        val count = if (AndroidNotificationAccess(ctx).isGranted()) prefs.notificationCount else 0
        val header = WorldTimeHeader.fromPrefs(prefs, System.currentTimeMillis())
        runCatching {
            AndroidBleClient(ctx).sendPacket(address, NotificationCount.packetFor(count, header))
                .onSuccess { prefs.worldTimeLastPushedOffsetSec = WorldTimeCodec.decode(header) }
        }
    }
```

- [ ] **Step 4: Fix remaining compile errors in tests**

Any existing test calling the old one-arg overloads: pass an explicit header, e.g. `WorldTimeCodec.encode(32_400)` where the old behavior (capture constant) was assumed, and update byte-level assertions accordingly.

- [ ] **Step 5: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — everything compiles, new tests green.

- [ ] **Step 6: Detekt + commit**

```bash
./gradlew :app:detekt
git add -A app/src
git commit -m "fix(worldtime): stop badge pushes clobbering World Time with the +9 capture constant

Every weekday-register (0x34) write now carries an app-computed offset
header instead of replaying the 2026-05-31 capture bytes 00 00 7E 90.
Closes the root cause of #34 (pending on-device confirmation)."
```

---

### Task 6: `WorldTimeReadback` — read the watch's current header

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTimeReadback.kt`
- Modify (single-line add): `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt` — next to `const val TARGET_WEEKDAYS = 0x34` (line 42) add:

```kotlin
    const val TARGET_GET_WEEKDAYS = 0x35
```

- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTimeReadbackTest.kt`

**Interfaces:**
- Consumes: `BleClient.sendAndAwait`, `OlleeProtocol.readRequest/Frame/RESPONSE_TARGET_OFFSET`, `WorldTimeCodec.decode`. Mirror `BatteryReadback` (`ble/BatteryReadback.kt`) exactly in shape; mirror `BatteryReadbackTest` for the test harness (it shows how to build a fake `Frame` — read it first).
- Produces: `object WorldTimeReadback { suspend fun read(ble: BleClient, address: String): Int?; fun parseOffsetSec(frame: OlleeProtocol.Frame): Int? }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.worldtime

import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorldTimeReadbackTest {

    // Build a reply frame the way BatteryReadbackTest does (real framing via buildRawPacket +
    // OlleeProtocol's frame parser) — payload = 4-byte header + "MOTUWETHFRSASU".
    private fun replyFrame(header: ByteArray): OlleeProtocol.Frame {
        val payload = header + "MOTUWETHFRSASU".encodeToByteArray()
        val raw = OlleeProtocol.buildRawPacket(
            OlleeProtocol.TARGET_GET_WEEKDAYS + OlleeProtocol.RESPONSE_TARGET_OFFSET,
            payload,
        )
        // Reuse whatever parse entry point BatteryReadbackTest uses to turn raw bytes into a Frame.
        return OlleeProtocol.parseFrame(raw)!!
    }

    @Test
    fun parsesTheHeaderOffset() {
        assertEquals(32_400, WorldTimeReadback.parseOffsetSec(replyFrame(WorldTimeCodec.encode(32_400))))
        assertEquals(-21_600, WorldTimeReadback.parseOffsetSec(replyFrame(WorldTimeCodec.encode(-21_600))))
    }

    @Test
    fun rejectsShortPayloadAndWrongTarget() {
        val short = replyFrame(WorldTimeCodec.encode(0)).let {
            OlleeProtocol.parseFrame(
                OlleeProtocol.buildRawPacket(
                    OlleeProtocol.TARGET_GET_WEEKDAYS + OlleeProtocol.RESPONSE_TARGET_OFFSET,
                    ByteArray(2),
                ),
            )!!
        }
        assertNull(WorldTimeReadback.parseOffsetSec(short))
        val wrongTarget = OlleeProtocol.parseFrame(
            OlleeProtocol.buildRawPacket(0x4A, WorldTimeCodec.encode(0) + ByteArray(14)),
        )!!
        assertNull(WorldTimeReadback.parseOffsetSec(wrongTarget))
    }
}
```

**Note:** `OlleeProtocol.parseFrame(bytes: ByteArray): Frame?` is the real entry point (`OlleeProtocol.kt:288`) — verified. Still skim `BatteryReadbackTest.kt` for any additional frame-construction conventions.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorldTimeReadbackTest*"`
Expected: FAIL — unresolved `WorldTimeReadback`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.blizzardcaron.freeolleefaces.worldtime

import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol

/**
 * Reads the watch's current World Time offset: request `0x35`, await the `0x55` weekday-table
 * reply, decode its 4-byte header (see [WorldTimeCodec]). Returns null on timeout / link loss
 * or a malformed payload. Mirrors [com.blizzardcaron.freeolleefaces.ble.BatteryReadback].
 */
object WorldTimeReadback {

    suspend fun read(ble: BleClient, address: String): Int? {
        val reply = ble.sendAndAwait(
            address,
            OlleeProtocol.readRequest(OlleeProtocol.TARGET_GET_WEEKDAYS),
            OlleeProtocol.TARGET_GET_WEEKDAYS + OlleeProtocol.RESPONSE_TARGET_OFFSET,
        )
        return reply.getOrNull()?.let { parseOffsetSec(it) }
    }

    /** Decodes the header offset from a `0x55` reply; null for wrong target/CRC/short payload. */
    fun parseOffsetSec(frame: OlleeProtocol.Frame): Int? {
        val expectedTarget = OlleeProtocol.TARGET_GET_WEEKDAYS + OlleeProtocol.RESPONSE_TARGET_OFFSET
        if (!frame.crcOk || frame.target != expectedTarget) return null
        if (frame.payload.size < WorldTimeCodec.HEADER_SIZE) return null
        return WorldTimeCodec.decode(frame.payload.copyOfRange(0, WorldTimeCodec.HEADER_SIZE))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorldTimeReadbackTest*"`
Expected: PASS.

- [ ] **Step 5: Detekt + commit**

```bash
./gradlew :app:detekt
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTimeReadback.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTimeReadbackTest.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt
git commit -m "feat(worldtime): 0x35 readback of the watch's world-time header"
```

---

### Task 7: `WorldTimeController`

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/vm/WorldTimeController.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeState.kt` — add one field (below)
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/vm/WorldTimeControllerTest.kt`

**Interfaces:**
- Consumes: `Prefs` world-time fields (Task 4), `WorldTime`/`WorldTimeCodec` (Tasks 2–3), `WorldTimeHeader` (Task 5), `WorldTimeReadback` (Task 6), `NotificationCount.packetFor(n, header)`, `FakeBleClient` from `commonTest/fakes/Fakes.kt` (use `awaitResult`/`sentPackets`), `kotlinx.coroutines.test.runTest` + `StandardTestDispatcher` (copy the coroutine-test setup from an existing controller test, e.g. `vm/` tests).
- Produces: `class WorldTimeController(prefs, ble, scope, showSnackbar, state, update, clock = Clock.System, homeZoneId: () -> String = { TimeZone.currentSystemDefault().id })` with `fun addSlot(zoneId: String)`, `fun removeSlot(zoneId: String)`, `fun activate(zoneId: String)`, `fun reconcileOnOpen()`, `fun refreshPreviews()`. Also `data class WorldTimeUiState` + `data class WorldTimeSlotUi` (in `HomeState.kt`), and `HomeState.worldTime: WorldTimeUiState`.

**HomeState additions** (in `HomeState.kt`, alongside the other feature blocks):

```kotlin
data class WorldTimeSlotUi(
    val zoneId: String,
    val city: String,
    val timeLabel: String,
    val offsetLabel: String,
)

data class WorldTimeUiState(
    val slots: List<WorldTimeSlotUi> = emptyList(),
    val activeZoneId: String? = null,
    val customOffsetLabel: String? = null,
    val swapped: Boolean = false,
    val homeCity: String = "",
)
```

and in `HomeState`: `val worldTime: WorldTimeUiState = WorldTimeUiState(),`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.worldtime.WorldTimeCodec
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WorldTimeControllerTest {

    private val julyMs = 1_783_425_600_000L // July 2026: Denver=-6h, Tokyo=+9h
    private val prefs = Prefs(MapSettings()).apply { watchAddress = "AA:BB" }
    private val ble = FakeBleClient()
    private var state = HomeState()

    private fun controller(scope: kotlinx.coroutines.CoroutineScope) = WorldTimeController(
        prefs = prefs,
        ble = ble,
        scope = scope,
        showSnackbar = {},
        state = { state },
        update = { t -> state = t(state) },
        clock = FixedClock(julyMs), // reuse/mirror however existing vm tests inject a fixed Clock
        homeZoneId = { "America/Denver" },
    )

    @Test
    fun addSlotCapsAtMaxAndRejectsUnknownZones() = runTest {
        val c = controller(this)
        listOf("Asia/Tokyo", "Europe/Berlin", "America/New_York", "Australia/Sydney").forEach { c.addSlot(it) }
        c.addSlot("Europe/London") // 5th: dropped
        c.addSlot("Not/AZone") // invalid: dropped
        assertEquals(4, prefs.worldTimeSlots.size)
        assertEquals(4, state.worldTime.slots.size)
    }

    @Test
    fun activatePersistsIntentThenPushesHeader() = runTest {
        val c = controller(this)
        c.addSlot("Asia/Tokyo")
        c.activate("Asia/Tokyo")
        testScheduler.advanceUntilIdle()
        assertEquals("Asia/Tokyo", prefs.worldTimeActiveZone) // intent persisted even if send fails
        val expected = NotificationCount.packetFor(0, WorldTimeCodec.encode(9 * 3600))
        assertContentEquals(expected, ble.sentPackets.single())
        assertEquals(9 * 3600, prefs.worldTimeLastPushedOffsetSec)
    }

    @Test
    fun activateFailureKeepsIntentButNotLastPushed() = runTest {
        ble.sendResult = Result.failure(IllegalStateException("watch asleep"))
        val c = controller(this)
        c.addSlot("Asia/Tokyo")
        c.activate("Asia/Tokyo")
        testScheduler.advanceUntilIdle()
        assertEquals("Asia/Tokyo", prefs.worldTimeActiveZone)
        assertNull(prefs.worldTimeLastPushedOffsetSec)
    }

    @Test
    fun removeActiveSlotClearsActive() = runTest {
        val c = controller(this)
        c.addSlot("Asia/Tokyo")
        c.activate("Asia/Tokyo")
        testScheduler.advanceUntilIdle()
        c.removeSlot("Asia/Tokyo")
        assertNull(prefs.worldTimeActiveZone)
        assertTrue(prefs.worldTimeSlots.isEmpty())
    }

    @Test
    fun reconcileAdoptsOnWatchChange() = runTest {
        prefs.worldTimeSlots = listOf("Asia/Tokyo", "Europe/Berlin")
        prefs.worldTimeActiveZone = "Asia/Tokyo"
        // Watch reports +2h (Berlin in July) — user changed it on-watch.
        ble.awaitResult = Result.success(weekdayReplyFrame(WorldTimeCodec.encode(2 * 3600)))
        val c = controller(this)
        c.reconcileOnOpen()
        testScheduler.advanceUntilIdle()
        assertEquals("Europe/Berlin", prefs.worldTimeActiveZone)
    }

    @Test
    fun reconcileNoOpsWhenWatchMatches() = runTest {
        prefs.worldTimeSlots = listOf("Asia/Tokyo")
        prefs.worldTimeActiveZone = "Asia/Tokyo"
        ble.awaitResult = Result.success(weekdayReplyFrame(WorldTimeCodec.encode(9 * 3600)))
        val c = controller(this)
        c.reconcileOnOpen()
        testScheduler.advanceUntilIdle()
        assertEquals("Asia/Tokyo", prefs.worldTimeActiveZone)
        assertTrue(ble.sentPackets.isEmpty()) // reconcile never writes
    }
}
```

`weekdayReplyFrame(header)` and `FixedClock` are small test helpers: build the frame exactly as `WorldTimeReadbackTest` does (Task 6); for the clock, check how existing `vm`/`prefs` tests pin `Clock` (a `Clock` returning `Instant.fromEpochMilliseconds(julyMs)`) and reuse that pattern — define locally if none is shared.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorldTimeControllerTest*"`
Expected: FAIL — unresolved `WorldTimeController` / `HomeState.worldTime`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.WorldTimeSlotUi
import com.blizzardcaron.freeolleefaces.ui.WorldTimeUiState
import com.blizzardcaron.freeolleefaces.worldtime.WorldTime
import com.blizzardcaron.freeolleefaces.worldtime.WorldTimeCodec
import com.blizzardcaron.freeolleefaces.worldtime.WorldTimeHeader
import com.blizzardcaron.freeolleefaces.worldtime.WorldTimeReadback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * Drives the World Time card: slot management, one-tap zone switching, and the app-open
 * reconcile that adopts on-watch changes (spec Approach A — the app owns the value between
 * opens, the user's on-watch change wins at reconcile). Swap arrives with the Wave B tasks.
 */
class WorldTimeController(
    private val prefs: Prefs,
    private val ble: BleClient,
    private val scope: CoroutineScope,
    private val showSnackbar: (String) -> Unit,
    private val state: () -> HomeState,
    private val update: ((HomeState) -> HomeState) -> Unit,
    private val clock: Clock = Clock.System,
    private val homeZoneId: () -> String = { TimeZone.currentSystemDefault().id },
) {
    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    fun addSlot(zoneId: String) {
        val slots = prefs.worldTimeSlots
        if (slots.size >= WorldTime.MAX_SLOTS || zoneId in slots) return
        if (WorldTime.offsetSecondsOf(zoneId, nowMs()) == null) return
        prefs.worldTimeSlots = slots + zoneId
        refreshPreviews()
    }

    fun removeSlot(zoneId: String) {
        prefs.worldTimeSlots = prefs.worldTimeSlots - zoneId
        if (prefs.worldTimeActiveZone == zoneId) prefs.worldTimeActiveZone = null
        refreshPreviews()
    }

    /** Chip tap: persist the intent first, then push the new offset to the watch. */
    fun activate(zoneId: String) {
        if (zoneId !in prefs.worldTimeSlots) return
        prefs.worldTimeActiveZone = zoneId
        prefs.worldTimeCustomOffsetSec = null
        refreshPreviews()
        pushHeader("World time → ${WorldTime.cityOf(zoneId)}")
    }

    /** Best-effort adopt of an on-watch change at app open; silent on read failure. */
    fun reconcileOnOpen() {
        val addr = prefs.watchAddress ?: return
        scope.launch {
            val watchSec = WorldTimeReadback.read(ble, addr) ?: return@launch
            val expected = WorldTimeCodec.decode(WorldTimeHeader.fromPrefs(prefs, nowMs(), homeZoneId()))
            if (watchSec == expected) return@launch
            prefs.saveWorldTimeState(WorldTime.reconcile(prefs.worldTimeState(), watchSec, nowMs()))
            refreshPreviews()
        }
    }

    /** Recomputes the card's labels from prefs + the injected clock. */
    fun refreshPreviews() {
        val now = nowMs()
        val s = prefs.worldTimeState()
        update {
            it.copy(
                worldTime = WorldTimeUiState(
                    slots = s.slots.map { zone ->
                        WorldTimeSlotUi(
                            zoneId = zone,
                            city = WorldTime.cityOf(zone),
                            timeLabel = WorldTime.timeLabel(zone, now) ?: "--:--",
                            offsetLabel = WorldTime.offsetLabel(WorldTime.offsetSecondsOf(zone, now) ?: 0),
                        )
                    },
                    activeZoneId = s.activeZoneId,
                    customOffsetLabel = s.customOffsetSec?.let { sec -> WorldTime.offsetLabel(sec) },
                    swapped = s.swapped,
                    homeCity = WorldTime.cityOf(homeZoneId()),
                ),
            )
        }
    }

    private fun pushHeader(successMessage: String) {
        val addr = prefs.watchAddress ?: return
        val header = WorldTimeHeader.fromPrefs(prefs, nowMs(), homeZoneId())
        val count = if (prefs.notificationsEnabled) prefs.notificationCount else 0
        scope.launch {
            ble.sendPacket(addr, NotificationCount.packetFor(count, header))
                .onSuccess {
                    prefs.worldTimeLastPushedOffsetSec = WorldTimeCodec.decode(header)
                    showSnackbar(successMessage)
                }
                .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*WorldTimeControllerTest*"`
Expected: PASS (6 tests).

- [ ] **Step 5: Detekt + commit**

```bash
./gradlew :app:detekt
git add -A app/src
git commit -m "feat(worldtime): controller for slots, one-tap switch, app-open reconcile"
```

---

### Task 8: World Time card UI + wiring

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/WorldTimeCard.kt` (8a — claude-local draftable)
- Modify (8b — controller): `ui/Callbacks.kt`, `ui/ComplicationCardId.kt`, `ui/HomeScreen.kt` (card list at `ComplicationCardsList`, insert after `NotificationsCard`), `AppViewModel.kt` (construct controller after `settings`, ~line 130), `androidMain/…/MainActivity.kt` (lines 151 and 227)

**Interfaces:**
- Consumes: `HomeState.worldTime` (Task 7), `WorldTimeController` (Task 7), `kotlinx.datetime.TimeZone.availableZoneIds`.
- Produces: `@Composable internal fun WorldTimeCard(state: HomeState, callbacks: HomeCallbacks, expanded: Boolean, onToggle: () -> Unit)`; new `HomeCallbacks` fields (all defaulted so `ScreenFakes.kt:67` and `ScreenshotHostActivity.kt:80` keep compiling): `onWorldTimeActivate: (String) -> Unit = {}`, `onWorldTimeAddSlot: (String) -> Unit = {}`, `onWorldTimeRemoveSlot: (String) -> Unit = {}`, `onWorldTimeRefresh: () -> Unit = {}`; enum value `ComplicationCardId.WORLD_TIME`; `AppViewModel.worldTime: WorldTimeController`.

- [ ] **Step 1 (8a): Write the card composable**

Follow `NotificationsCard` (`HomeScreen.kt:179`) for Card/typography idioms and `ComplicationCards.kt` for row layout. Content requirements (the approved mockup):

- Collapsed row: title "World Time"; active line = active slot's `city  timeLabel  (offsetLabel)`, or `Custom (customOffsetLabel)` when `activeZoneId == null && customOffsetLabel != null`, or "Not set" otherwise.
- `FlowRow` of `FilterChip`s — one per `state.worldTime.slots`, `selected = zoneId == activeZoneId`, `onClick = { callbacks.onWorldTimeActivate(zoneId) }`, label `"$city $timeLabel"`.
- Expanded content: per-slot rows with a Remove `TextButton` (`callbacks.onWorldTimeRemoveSlot`), and an "Add zone" button (enabled while `slots.size < 4`) opening a picker dialog: `OutlinedTextField` filter + `LazyColumn` of `TimeZone.availableZoneIds.sorted()` filtered case-insensitively by the query, each row `WorldTime.cityOf(id)` + `id`, tap → `callbacks.onWorldTimeAddSlot(id)` + close.
- A minute ticker so the time labels stay live while visible:

```kotlin
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            callbacks.onWorldTimeRefresh()
        }
    }
```

(`60_000` → named const `TICK_MS`. The fixture default `{}` keeps screenshots deterministic.)

- No swap button yet — Wave B (Task 13) adds it.

- [ ] **Step 2 (8b): Wire it**

- `ComplicationCardId.kt`: add `WORLD_TIME` to the enum.
- `Callbacks.kt`: add the four defaulted fields to `HomeCallbacks`.
- `HomeScreen.kt` `ComplicationCardsList`: after the `NotificationsCard(...)` block insert:

```kotlin
        WorldTimeCard(
            state = state,
            callbacks = callbacks,
            expanded = expanded == ComplicationCardId.WORLD_TIME,
            onToggle = { onToggle(ComplicationCardId.WORLD_TIME) },
        )
```

- `AppViewModel.kt` after the `settings` controller:

```kotlin
    val worldTime = WorldTimeController(
        prefs = prefs,
        ble = ble,
        scope = viewModelScope,
        showSnackbar = ::emitEvent,
        state = { state },
        update = { t -> state = t(state) },
        clock = clock,
    )
```

- `MainActivity.kt:151` — alongside `viewModel.complications.refreshAllPreviews()` add:

```kotlin
                viewModel.worldTime.refreshPreviews()
                viewModel.worldTime.reconcileOnOpen()
```

- `MainActivity.kt:227` `HomeCallbacks(` — add:

```kotlin
        onWorldTimeActivate = viewModel.worldTime::activate,
        onWorldTimeAddSlot = viewModel.worldTime::addSlot,
        onWorldTimeRemoveSlot = viewModel.worldTime::removeSlot,
        onWorldTimeRefresh = viewModel.worldTime::refreshPreviews,
```

- [ ] **Step 3: Build + full test suite + detekt**

Run: `./gradlew :app:testDebugUnitTest :app:detekt`
Expected: PASS — fixtures compile untouched thanks to defaulted callbacks.

- [ ] **Step 4: Visual verification (controller judgment — NOT delegated)**

Use the verify-visual skill / screenshot flow (debug build, `ScreenshotHostActivity`) if a device is reachable; otherwise defer to Task 14's on-device pass. Check: chips row, active highlight, picker dialog, "Not set" empty state.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "feat(worldtime): world time card with slot chips and zone picker"
```

---

### Task 9: Background-chain DST maintenance

**Files:**
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt` (call site line ~60–61; new helper next to `maybePushNotificationCount`)

**Interfaces:**
- Consumes: `WorldTimeHeader.fromPrefs`, `WorldTimeCodec.decode`, `prefs.worldTimeLastPushedOffsetSec` (stamped by every weekday write since Task 5), `NotificationCount.packetFor(n, header)`, `inSleepNow`, `AndroidNotificationAccess`, `AndroidBleClient`.
- Produces: `maybeMaintainWorldTime(ctx, prefs, address)` — write-on-change only (~2 writes/year/zone).

- [ ] **Step 1: Implement the helper** (after `maybePushNotificationCount`, mirroring its KDoc style)

```kotlin
    /**
     * Best-effort write-on-change keeper of the World Time offset across DST transitions.
     * When the badge overlay is enabled, [maybePushNotificationCount] already re-stamps the
     * header every run and this is a no-op via the lastPushed dedupe; it matters when the
     * overlay is off (or access revoked), where nothing else would refresh the register.
     * Fire-and-forget: never affects face scheduling or failure accounting.
     */
    private suspend fun maybeMaintainWorldTime(ctx: Context, prefs: Prefs, address: String?) {
        if (address == null || inSleepNow(prefs)) return
        val header = WorldTimeHeader.fromPrefs(prefs, System.currentTimeMillis())
        val sec = WorldTimeCodec.decode(header) ?: return
        if (sec == prefs.worldTimeLastPushedOffsetSec) return
        val count = if (prefs.notificationsEnabled && AndroidNotificationAccess(ctx).isGranted()) {
            prefs.notificationCount
        } else {
            0
        }
        runCatching {
            AndroidBleClient(ctx).sendPacket(address, NotificationCount.packetFor(count, header))
                .onSuccess { prefs.worldTimeLastPushedOffsetSec = sec }
        }
    }
```

- [ ] **Step 2: Call it** — at the line-60 block, after the existing calls:

```kotlin
        maybePushNotificationCount(ctx, prefs, address)
        maybeReconcileAutoSleep(ctx, prefs, address)
        maybeMaintainWorldTime(ctx, prefs, address)
```

- [ ] **Step 3: Full gates + commit**

Run: `./gradlew :app:testDebugUnitTest :app:detekt` → PASS.

```bash
git add app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt
git commit -m "feat(worldtime): write-on-change DST upkeep in the background chain"
```

*(Worker logic is androidMain and untested by commonTest; the decision logic it uses — header computation, codec, dedupe value — is fully covered by Tasks 2–5. This matches how `maybePushNotificationCount` is covered today.)*

---

### Task 10: Phase 0 — protocol verification ⛔ HARD BLOCK (needs Ken + watch + capture rig)

**Files:**
- Modify: `docs/reference/ollee-ble-protocol.md` (findings recorded in Task 11)

Controller + Ken, on hardware. Rig: instrumented official app (`ollee-graphene` `CAPTURE=1` build), `adb37` (`~/.local/bin/adb37`, start `adb37-server` first), logcat tag `OLLEE_BLE`. Watch under test: `00:80:E1:26:DC:86`.

**DONE 2026-07-10 — results below. Full record: scratchpad `phase0-findings.md`.**

- [x] **Step 1: World-time register — CONFIRMED.** `0x34`/`0x35` header = World Time UTC offset, big-endian two's-complement **seconds**. Verified `00007E90`=+9:00, `FFFFABA0`=-6:00, `00004D58`=+5:30 (each round-tripped and drove the face). `WorldTimeCodec` needs no change. Caveat: an on-watch zone change does NOT appear in `0x35` (stored elsewhere) — foreground reconcile can't adopt on-watch changes; app stays authoritative (matches spec's accepted trade-off).
- [x] ~~**Step 2: register sweep fallback**~~ **RETIRED AS UNSAFE.** An empty-payload read to one of `0x20`–`0x22` put the watch into its **bootloader** ("boot" screen); recovery needed a firmware re-flash via the official app. Never sweep unknown registers on this firmware. Moot anyway — Step 1 confirmed the register.
- [x] **Step 3: `02 23` set-clock — DECODED (CRC-validated, current 1.0.6 layout).** Captured via HCI snoop (Method B), not the instrumented build. Payload is 20 bytes, **all little-endian**: `[0:4]` LE u32 unix `now`; `[4:8]` LE s32 UTC offset seconds (home; two's complement); `[8:12]` latitude ×1000; `[12:16]` longitude ×1000; `[16:18]` `0300` constant; `[18:20]` `FFFF` constant (1.0.5 omitted it). Lat/long confirmed vs `dumpsys location`. Golden vector in Task 12.
- [ ] **Step 4: Deliberate bug repro** — NOT run; mechanism already proven (register's initial value was `00007E90`=+9, the replayed constant). Optional on-device regression folded into Task 14; skipped today after the bootloader scare.
- [x] **Step 5: Results handed to Tasks 11/12** (Task 11 committed: `b9c8006`).

---

### Task 11: Reconcile constants + protocol doc (after Task 10)

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/worldtime/WorldTimeCodec.kt` (only if Phase 0 contradicts the hypothesis)
- Modify: `docs/reference/ollee-ble-protocol.md`

- [ ] **Step 1:** If Phase 0 confirmed big-endian two's-complement seconds: update `WorldTimeCodec`'s KDoc from "hypothesized" to "on-device-verified (date)". If it found different units/endianness/sign: fix `encode`/`decode` and the golden vectors in `WorldTimeCodecTest` — nothing else in the codebase knows the encoding.
- [ ] **Step 2:** Protocol doc: promote the `02 34`/`02 35` rows to describe the header as the World Time offset (confidence: on-device-verified + date); replace the `02 23` row's raw capture bytes with the decoded field layout from Task 10 Step 3; note the World Time face-record cross-reference.
- [ ] **Step 3:** Gates + commit:

```bash
./gradlew :app:testDebugUnitTest :app:detekt
git add -A
git commit -m "docs(protocol): world-time header + set-clock layouts verified on-device"
```

---

### Task 12: `SetClock` — the 02 23 clock-write builder (after Task 10)

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/worldtime/SetClock.kt`
- Modify (single-line add): `OlleeProtocol.kt` — `const val TARGET_SET_CLOCK = 0x23`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/worldtime/SetClockTest.kt`

**Interfaces:**
- Consumes: `OlleeProtocol.buildRawPacket`.
- Produces: `object SetClock { fun build(nowMs: Long, offsetSec: Int, latE3: Int, lonE3: Int): ByteArray }` — a frame that sets the watch clock to the wall time of UTC+`offsetSec` at instant `nowMs`, carrying the phone position `latE3`/`lonE3` (degrees × 1000, truncated toward zero) for the Sun & Moon face.

**Decoded layout (Task 10 Step 3, on-device-verified 2026-07-10).** Inner = `02 23` + a 20-byte
payload, **all little-endian**, then framed by `buildRawPacket` (which prepends `00 LEN AA 55`
+ CRC-16/CCITT-FALSE and sets `LEN = inner_len + 4 = 0x1a`):

```
[0:4]   LE  uint32  nowMs / 1000                 (Unix epoch seconds)
[4:8]   LE  int32   offsetSec                     (two's complement, e.g. -21600 = -6h)
[8:12]  LE  int32   latE3                         (latitude  × 1000, e.g. 40140)
[12:16] LE  int32   lonE3                         (longitude × 1000, e.g. -105144)
[16:18] LE          0x0003                        (constant flag; hard-code, comment as replay)
[18:20]             0xFFFF                         (constant; hard-code, comment as replay)
```

**Golden vector for the test** (assert exact bytes):
`build(nowMs = 1_783_729_820_000, offsetSec = -21600, latE3 = 40140, lonE3 = -105144)`
→ `001aaa55fb5302239c8e516aa0abffffcc9c00004865feff0300ffff`.

Test-first (same 5-step cycle as Task 2): golden-vector test → failing run → implement `build`
→ passing run → detekt + commit `feat(worldtime): 02 23 set-clock builder (layout verified on-device)`.
Keep the two constant fields as named consts with a `// captured constant; meaning unconfirmed`
comment — do NOT silently bake an opaque blob (that pattern caused #34).

**Location sourcing (for Task 13, not this task):** reuse the existing
`location/LocationProvider.fetch(): Result<Coords>` (`Coords.lat`/`.lng`) — FreeOllee already
has it (weather/activity) with fine+coarse permissions. Task 13's `toggleSwap` fetches a fix and
passes `(lat*1000).toInt()`, `(lng*1000).toInt()`; on `Result.failure` (no permission/fix) it
aborts the swap with the existing "send failed"-style status rather than writing 0/0 (which would
corrupt the watch's Sun & Moon position). Keep `SetClock.build` pure — coordinates are inputs.

---

### Task 13: Casio swap (after Task 12)

**Files:**
- Modify: `vm/WorldTimeController.kt` — add `fun toggleSwap()`, extend `refreshPreviews`
- Modify: `ui/WorldTimeCard.kt` — swap button + swapped banner
- Modify: `ui/Callbacks.kt` — `onWorldTimeSwap: () -> Unit = {}`
- Modify: `androidMain/…/MainActivity.kt:227` — `onWorldTimeSwap = viewModel.worldTime::toggleSwap`
- Modify: `androidMain/…/auto/AutoUpdateWorker.kt` — clock upkeep while swapped
- Test: extend `vm/WorldTimeControllerTest.kt`

**Interfaces:**
- Consumes: `SetClock.build` (Task 12), everything from Task 7.
- Produces: `WorldTimeController.toggleSwap()`; `Prefs.worldTimeLastPushedClockOffsetSec: Int?` (new pref, key `world_time_last_pushed_clock_offset_sec`, same nullable-Int pattern as Task 4).

- [ ] **Step 1: Write the failing tests** (add to `WorldTimeControllerTest`)

```kotlin
    @Test
    fun swapWritesClockToActiveZoneAndWorldToHome() = runTest {
        val c = controller(this)
        c.addSlot("Asia/Tokyo")
        c.activate("Asia/Tokyo")
        testScheduler.advanceUntilIdle()
        ble.sentPackets.clear()
        c.toggleSwap()
        testScheduler.advanceUntilIdle()
        assertTrue(prefs.worldTimeSwapped)
        // Two writes: the clock set to Tokyo time, the world register set to home (-6h).
        assertContentEquals(SetClock.build(julyMs, 9 * 3600), ble.sentPackets[0])
        assertContentEquals(
            NotificationCount.packetFor(0, WorldTimeCodec.encode(-6 * 3600)),
            ble.sentPackets[1],
        )
    }

    @Test
    fun unswapRestoresBoth() = runTest {
        prefs.worldTimeSlots = listOf("Asia/Tokyo")
        prefs.worldTimeActiveZone = "Asia/Tokyo"
        prefs.worldTimeSwapped = true
        val c = controller(this)
        c.toggleSwap()
        testScheduler.advanceUntilIdle()
        assertEquals(false, prefs.worldTimeSwapped)
        assertContentEquals(SetClock.build(julyMs, -6 * 3600), ble.sentPackets[0])
        assertContentEquals(
            NotificationCount.packetFor(0, WorldTimeCodec.encode(9 * 3600)),
            ble.sentPackets[1],
        )
    }

    @Test
    fun activateWhileSwappedRetargetsTheClock() = runTest {
        prefs.worldTimeSlots = listOf("Asia/Tokyo", "Europe/Berlin")
        prefs.worldTimeActiveZone = "Asia/Tokyo"
        prefs.worldTimeSwapped = true
        val c = controller(this)
        c.activate("Europe/Berlin")
        testScheduler.advanceUntilIdle()
        // Living in the world zone: the clock follows the new chip (+2h), world stays home.
        assertContentEquals(SetClock.build(julyMs, 2 * 3600), ble.sentPackets[0])
        assertContentEquals(
            NotificationCount.packetFor(0, WorldTimeCodec.encode(-6 * 3600)),
            ble.sentPackets[1],
        )
    }
```

- [ ] **Step 2: Implement in `WorldTimeController`**

```kotlin
    /** Casio swap: persist intent, then write the clock and the world register (spec §swap). */
    fun toggleSwap() {
        val active = prefs.worldTimeActiveZone ?: return
        prefs.worldTimeSwapped = !prefs.worldTimeSwapped
        refreshPreviews()
        pushSwappedPair(active)
    }

    private fun pushSwappedPair(activeZone: String) {
        val addr = prefs.watchAddress ?: return
        val now = nowMs()
        val clockOffset = if (prefs.worldTimeSwapped) {
            WorldTime.offsetSecondsOf(activeZone, now) ?: return
        } else {
            WorldTime.offsetSecondsOf(homeZoneId(), now) ?: return
        }
        val header = WorldTimeHeader.fromPrefs(prefs, now, homeZoneId())
        val count = if (prefs.notificationsEnabled) prefs.notificationCount else 0
        scope.launch {
            val clockOk = ble.sendPacket(addr, SetClock.build(now, clockOffset)).isSuccess
            if (clockOk) prefs.worldTimeLastPushedClockOffsetSec = clockOffset
            ble.sendPacket(addr, NotificationCount.packetFor(count, header))
                .onSuccess { prefs.worldTimeLastPushedOffsetSec = WorldTimeCodec.decode(header) }
            val where = if (prefs.worldTimeSwapped) WorldTime.cityOf(activeZone) else "home"
            showSnackbar(
                if (clockOk) "Clock → $where" else "Send failed — long-press ALARM to wake the watch, then retry",
            )
        }
    }
```

and in `activate(zoneId)`, after `refreshPreviews()`: `if (prefs.worldTimeSwapped) pushSwappedPair(zoneId) else pushHeader(…)` (keep the existing message).

Partial-failure model (spec §error handling): intent persists first; the chain backstop (Step 4) reconciles until watch == intent. No rollback logic.

- [ ] **Step 3: Card swap UI** — below the chips: when `state.worldTime.swapped` show `"⚠ ${cityOf(active)} is on the clock — home (${homeCity}) is in World Time"` + button "⇄ Restore home time"; else button "⇄ Swap with home" (enabled only when `activeZoneId != null`), both → `callbacks.onWorldTimeSwap()`.

- [ ] **Step 4: Chain clock upkeep** — extend `maybeMaintainWorldTime`: when `prefs.worldTimeSwapped`, also compute the active zone's current offset; if it differs from `worldTimeLastPushedClockOffsetSec`, send `SetClock.build(System.currentTimeMillis(), sec)` and stamp on success.

- [ ] **Step 5: Gates + visual check + commit**

```bash
./gradlew :app:testDebugUnitTest :app:detekt
git add -A app/src
git commit -m "feat(worldtime): Casio-style home<->world swap"
```

---

### Task 14: On-device verification ⛔ HARD BLOCK (needs Ken + watch)

Before merge — merge to main cuts a signed release.

- [ ] 1. Task 10 Step 4's regression: badge push on the release-candidate build no longer flips the World Time zone.
- [ ] 2. Chip tap changes the watch's World Time face to the chosen zone.
- [ ] 3. Swap flips the main clock to the world zone and puts home in World Time; un-swap restores both.
- [ ] 4. Change World Time on-watch, reopen the app: the change is adopted (matching slot activates, else Custom shown).
- [ ] 5. Battery sanity: with badge overlay enabled, confirm no extra BLE writes per chain run vs v0.35.1 (the header rides existing writes).

---

### Task 15: Whole-branch review, PR, release

- [ ] **Step 1:** Whole-branch review (per-task reviews can't see cross-task seams): `/code-review` at high effort over `main..feat/world-time`; fix findings.
- [ ] **Step 2:** `git fetch` + rebase on latest `main`; confirm `VERSION` (0.36.0) is still ahead of main's and tag `v0.36.0` doesn't exist.
- [ ] **Step 3:** **Clock check:** no push 9 AM–5 PM MT Mon–Fri. Refuse and wait if inside the window.
- [ ] **Step 4:** Push branch, open PR titled `feat: world time — fix #34 corruption + Casio-style slots & swap`, body: root cause (replayed capture constant `00 00 7E 90` = +9:00 in every badge push), the fix, the feature, hardware-verification results, `Closes #34`. End with the standard Claude Code attribution line.
- [ ] **Step 5:** Merge only after CI green → push-to-main cuts signed `v0.36.0`. Comment on #34 explaining the root cause to ICH88.

---

## Self-review notes (controller, post-write)

- Spec coverage: fix (T5), slots+chips (T3/4/7/8), picker (T8), DST chain upkeep (T9), reconcile (T6/7), swap+clock upkeep (T10/12/13), protocol doc (T11), on-device checklist (T14), release mechanics (T1/T15). Foreground badge path, service path, and worker path all pass the header (T5). No spec item unmapped.
- Known deliberate deferrals: Task 12's payload code awaits Task 10 data (cannot be honestly written from a guess); Task 8's visual check may defer to Task 14 if no device is reachable.
- Type consistency: `packetFor(n, header)`, `buildWeekdayPacket(slots, header)`, `WorldTimeHeader.fromPrefs(prefs, nowMs, homeZoneId)`, `worldTimeLastPushedOffsetSec` used identically across Tasks 5/7/9/13.
