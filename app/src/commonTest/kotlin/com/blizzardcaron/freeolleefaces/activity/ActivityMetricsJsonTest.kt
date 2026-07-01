package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityMetricsJsonTest {

    @Test fun round_trip_preserves_order_and_enabled() {
        val config = ActivityMetricsConfig.DEFAULT
            .moveDown(ActivityMode.RECORDING, 0)
            .setEnabled(ActivityMode.RECORDING, ActivityMetric.PRESSURE, false)
            .moveUp(ActivityMode.GLANCE, 2)
        val decoded = ActivityMetricsJson.decode(ActivityMetricsJson.encode(config))
        assertEquals(config, decoded)
    }

    @Test fun null_yields_default() {
        assertEquals(ActivityMetricsConfig.DEFAULT, ActivityMetricsJson.decode(null))
    }

    @Test fun blank_yields_default() {
        assertEquals(ActivityMetricsConfig.DEFAULT, ActivityMetricsJson.decode("   "))
    }

    @Test fun corrupt_yields_default() {
        assertEquals(ActivityMetricsConfig.DEFAULT, ActivityMetricsJson.decode("{not json"))
    }

    @Test fun missing_metric_is_appended_enabled() {
        // A stored recording list missing TIME must still surface TIME, enabled, at the end.
        val json = """{"recording":[{"m":"PACE","e":true}],"glance":[]}"""
        val decoded = ActivityMetricsJson.decode(json)
        val rec = decoded.recording
        assertEquals(ActivityMetricsConfig.RECORDING_METRICS.size, rec.size)
        assertEquals(ActivityMetric.PACE, rec.first().metric)
        assertEquals(true, rec.all { it.enabled } || rec.any { it.metric == ActivityMetric.TIME && it.enabled })
        // Glance defaults-merge from empty:
        assertEquals(ActivityMetricsConfig.GLANCE_METRICS, decoded.glance.map { it.metric })
    }

    @Test fun unknown_metric_name_is_dropped() {
        val json = """{"recording":[{"m":"BOGUS","e":false},{"m":"PACE","e":false}],"glance":[]}"""
        val decoded = ActivityMetricsJson.decode(json)
        // BOGUS dropped; PACE kept disabled; the rest appended enabled.
        assertEquals(false, decoded.recording.first { it.metric == ActivityMetric.PACE }.enabled)
        assertEquals(ActivityMetricsConfig.RECORDING_METRICS.toSet(), decoded.recording.map { it.metric }.toSet())
    }

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
}
