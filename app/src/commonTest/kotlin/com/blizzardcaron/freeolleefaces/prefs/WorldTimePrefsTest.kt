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
