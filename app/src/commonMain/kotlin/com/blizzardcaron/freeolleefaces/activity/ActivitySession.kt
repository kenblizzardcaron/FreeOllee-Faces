package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.location.Coords

/**
 * Pure activity math: ingests GPS fixes, accumulates accuracy-gated, jitter-filtered haversine
 * distance, and computes a rolling-window recent pace. No coroutines, no platform — fully testable.
 */
class ActivitySession(
    private val startedAtMs: Long,
    private val accuracyGateM: Float = DEFAULT_ACCURACY_GATE_M,
    private val minMoveMeters: Double = DEFAULT_MIN_MOVE_METERS,
    private val paceWindowMs: Long = DEFAULT_PACE_WINDOW_MS,
    private val minPaceSpanMs: Long = DEFAULT_MIN_PACE_SPAN_MS,
) {
    private data class Mark(val tMs: Long, val cumulativeMeters: Double)

    private var lastAccepted: Coords? = null
    private val window = ArrayDeque<Mark>()

    private var paused = false
    private var pausedAccumMs = 0L
    private var pausedSinceMs: Long? = null

    var distanceMeters: Double = 0.0
        private set

    // each gate (accuracy, first-fix bootstrap, jitter floor) bails independently;
    // early returns are the clearest, safest form
    @Suppress("ReturnCount")
    fun onSample(coords: Coords, nowMs: Long) {
        val acc = coords.accuracyM
        if (acc != null && acc > accuracyGateM) return
        if (paused) {
            lastAccepted = coords
            return // hold position so resume doesn't count the gap
        }
        val prev = lastAccepted
        if (prev == null) {
            lastAccepted = coords
            window.addLast(Mark(nowMs, distanceMeters))
            return
        }
        val step = GeoMath.haversineMeters(prev.lat, prev.lng, coords.lat, coords.lng)
        if (step < minMoveMeters) return
        distanceMeters += step
        lastAccepted = coords
        window.addLast(Mark(nowMs, distanceMeters))
        evictOlderThan(nowMs - paceWindowMs)
    }

    private fun evictOlderThan(cutoffMs: Long) {
        while (window.size > 2 && window.first().tMs < cutoffMs) window.removeFirst()
    }

    // each missing/insufficient window precondition bails independently;
    // early returns are the clearest, safest form
    @Suppress("ReturnCount")
    fun recentPaceSecPerKm(nowMs: Long): Double? {
        evictOlderThan(nowMs - paceWindowMs)
        val oldest = window.firstOrNull() ?: return null
        val newest = window.lastOrNull() ?: return null
        val spanMs = newest.tMs - oldest.tMs
        val deltaMeters = newest.cumulativeMeters - oldest.cumulativeMeters
        if (spanMs < minPaceSpanMs || deltaMeters <= 0.0) return null
        val deltaKm = deltaMeters / METERS_PER_KM
        val spanSec = spanMs / MILLIS_PER_SECOND
        return spanSec / deltaKm
    }

    fun pause(nowMs: Long) {
        if (!paused) {
            paused = true
            pausedSinceMs = nowMs
        }
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

    fun state(selectedMetric: ActivityMetric, nowMs: Long): ActivityState = ActivityState(
        running = true,
        selectedMetric = selectedMetric,
        distanceMeters = distanceMeters,
        recentPaceSecPerKm = recentPaceSecPerKm(nowMs),
        elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0L),
        movingTimeMs = movingTimeMs(nowMs),
        paused = paused,
    )

    private companion object {
        const val DEFAULT_ACCURACY_GATE_M = 30f
        const val DEFAULT_MIN_MOVE_METERS = 2.0
        const val DEFAULT_PACE_WINDOW_MS = 30_000L
        const val DEFAULT_MIN_PACE_SPAN_MS = 5_000L
        const val METERS_PER_KM = 1000.0
        const val MILLIS_PER_SECOND = 1000.0
    }
}
