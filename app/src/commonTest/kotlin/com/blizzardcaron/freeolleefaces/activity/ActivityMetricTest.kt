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
