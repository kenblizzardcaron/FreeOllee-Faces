package com.blizzardcaron.freeolleefaces.activity

/**
 * Pure speed-based auto-pause policy. Pauses once ground speed has stayed below [thresholdMps] for
 * at least [pauseHoldMs] (measured from the first slow sample) while the rolling average over the
 * last [sampleWindow] samples is also below threshold; resumes once that average is back at or above
 * threshold. Both are gated off when the GPS fix is stale (more than [gpsTimeoutMs] since the last
 * sample) so a dropped fix never fabricates a pause. No time source of its own; the caller passes
 * nowMs.
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
        val previousSampleMs = lastSampleMs
        lastSampleMs = nowMs
        speeds.addLast(speedMps)
        while (speeds.size > sampleWindow) {
            speeds.removeFirst()
        }
        // A GPS gap longer than the timeout breaks the slow streak so the hold never
        // accumulates across a dropout.
        if (previousSampleMs != null && nowMs - previousSampleMs > gpsTimeoutMs) {
            slowSinceMs = null
        }
        if (speedMps < thresholdMps) {
            if (slowSinceMs == null) {
                slowSinceMs = nowMs
            }
        } else {
            slowSinceMs = null
        }
    }

    @Suppress("ReturnCount")
    fun shouldAutoPause(nowMs: Long): Boolean {
        if (isStale(nowMs)) return false
        if (speeds.size < sampleWindow) return false
        if (speeds.average() >= thresholdMps) return false
        val since = slowSinceMs ?: return false
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
