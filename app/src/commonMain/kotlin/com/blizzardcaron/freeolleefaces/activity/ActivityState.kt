package com.blizzardcaron.freeolleefaces.activity

/** Immutable live state of the running activity (not persisted). */
data class ActivityState(
    val running: Boolean = false,
    val recording: Boolean = false,
    val selectedMetric: ActivityMetric = ActivityMetric.PACE,
    val distanceMeters: Double = 0.0,
    val recentPaceSecPerKm: Double? = null,
    val elapsedMs: Long = 0L,
    val watchReachable: Boolean = true,
    val lastPushText: String? = null,
    val headingDeg: Float? = null,
    val altitudeM: Double? = null,
    val pressureHpa: Double? = null,
    val hasFix: Boolean = false,
    val paused: Boolean = false,
    val pausedAtMs: Long? = null,
    val movingTimeMs: Long = 0L,
)
