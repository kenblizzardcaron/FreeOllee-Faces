package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityStateTest {
    @Test
    fun testInitialStateNotPaused() {
        val state = ActivityState(
            elapsedMs = 0,
            distanceMeters = 0.0,
            selectedMetric = ActivityMetric.PACE,
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
            distanceMeters = 100.0,
            selectedMetric = ActivityMetric.PACE,
            paused = true,
            pausedAtMs = now
        )
        assertEquals(true, state.paused)
        assertEquals(now, state.pausedAtMs)
    }
}
