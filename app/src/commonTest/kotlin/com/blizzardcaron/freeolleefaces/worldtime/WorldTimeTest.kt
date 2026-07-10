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
