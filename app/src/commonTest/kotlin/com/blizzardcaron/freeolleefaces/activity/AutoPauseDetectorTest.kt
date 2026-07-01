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
        assertFalse(det.shouldAutoPause(2_000L))
        assertTrue(det.shouldAutoPause(3_000L))
    }

    @Test fun doesNotPauseWhenWindowNotFull() {
        val det = AutoPauseDetector(thresholdMps = 0.1f)
        det.onSample(0.05f, 0L)
        det.onSample(0.05f, 1_000L)
        assertFalse(det.shouldAutoPause(2_000L))
    }

    @Test fun doesNotPauseWhenFixIsStale() {
        val det = AutoPauseDetector(thresholdMps = 0.1f)
        slow(det, listOf(0L, 1_000L, 2_000L))
        assertFalse(det.shouldAutoPause(9_000L))
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

    @Test fun holdResetsAfterGpsDropout() {
        val det = AutoPauseDetector(thresholdMps = 0.1f)
        slow(det, listOf(0L, 1_000L, 2_000L))
        // GPS drops out; next slow sample arrives well past the stale timeout.
        det.onSample(0.05f, 10_000L)
        assertFalse(det.shouldAutoPause(12_000L)) // only 2s since the post-gap streak start
        assertTrue(det.shouldAutoPause(13_000L))  // 3s after the post-gap first slow sample
    }
}
