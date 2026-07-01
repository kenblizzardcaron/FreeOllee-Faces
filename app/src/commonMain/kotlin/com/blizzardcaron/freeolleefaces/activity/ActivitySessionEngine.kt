package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.location.Coords
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlin.random.Random

// Upper bound for the random suffix on a default track id, so two sessions started within the
// same millisecond don't collide on the store's id-keyed filename (silently overwriting a track).
private const val ID_RANDOM_BOUND = 1_000_000

/**
 * Orchestrates a live activity: feeds GPS fixes into [ActivitySession], renders the selected
 * metric, decides + performs name-tag pushes, records the track, and brackets the session with
 * auto-sleep disable/restore. Owns no coroutines — the Android service drives [ingest]/[tick].
 */
class ActivitySessionEngine(
    private val ble: BleClient,
    private val store: ActivityTrackStore,
    private val prefs: Prefs,
    private val autoSleep: SessionAutoSleep,
    private val watchAddress: () -> String?,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newId: () -> String = {
        "${Clock.System.now().toEpochMilliseconds()}-${Random.nextInt(ID_RANDOM_BOUND)}"
    },
    private val metricsConfig: () -> ActivityMetricsConfig = { ActivityMetricsConfig.DEFAULT },
) {
    private val _state = MutableStateFlow(ActivityState())
    val state: StateFlow<ActivityState> = _state.asStateFlow()

    private var session: ActivitySession? = null
    private var trackId: String = ""
    private var startedAtMs: Long = 0L
    private var unit: ActivityUnit = ActivityUnit.IMPERIAL
    private val points = mutableListOf<TrackPoint>()
    private var selectedMetric: ActivityMetric = ActivityMetric.PACE
    private var config: ActivityMetricsConfig = ActivityMetricsConfig.DEFAULT
    private var recording = false
    private var pauseSource: PauseSource = PauseSource.NONE
    private var pushIntervalMs: Long = ActivityPushDecider.DEFAULT_MIN_SPACING_MS
    private val pusher = NameplatePusher(ble)

    suspend fun start() {
        if (session != null) return
        startedAtMs = now()
        unit = prefs.activityUnit
        pushIntervalMs = prefs.activityPushIntervalMs
        trackId = newId()
        points.clear()
        config = metricsConfig()
        recording = true
        selectedMetric = activeOrder().first()
        session = ActivitySession(startedAtMs)
        _state.value = ActivityState(running = true, recording = true, selectedMetric = selectedMetric)
        watchAddress()?.let { autoSleep.disableForActivity(it) }
    }

    /** Start a non-recording live session for the instrument glance (compass/altitude); saves no track. */
    suspend fun startLive() {
        if (session != null) return
        startedAtMs = now()
        unit = prefs.activityUnit
        pushIntervalMs = prefs.activityPushIntervalMs
        points.clear()
        config = metricsConfig()
        recording = false
        selectedMetric = activeOrder().first()
        session = ActivitySession(startedAtMs)
        _state.value = ActivityState(running = true, recording = false, selectedMetric = selectedMetric)
    }

    /** Upgrade a running live session to a recording one (fresh track from now); cold-starts if idle. */
    suspend fun beginRecording() {
        if (session == null) {
            start()
            return
        }
        if (recording) return
        pushIntervalMs = prefs.activityPushIntervalMs
        trackId = newId()
        startedAtMs = now()
        points.clear()
        recording = true
        selectedMetric = activeOrder().first()
        _state.value = _state.value.copy(recording = true, selectedMetric = selectedMetric)
        watchAddress()?.let { autoSleep.disableForActivity(it) }
    }

    suspend fun ingest(coords: Coords, nowMs: Long) {
        val s = session ?: return
        s.onSample(coords, nowMs)
        if (recording) points += TrackPoint(nowMs, coords.lat, coords.lng, coords.accuracyM, coords.altM)
        val heading =
            if (coords.bearingDeg != null && (coords.speedMps ?: 0f) >= SPEED_GATE_MPS) {
                coords.bearingDeg
            } else {
                _state.value.headingDeg
            }
        _state.value = s.state(selectedMetric, nowMs).copy(
            watchReachable = _state.value.watchReachable,
            lastPushText = pusher.lastPushText,
            recording = recording,
            headingDeg = heading,
            altitudeM = coords.altM ?: _state.value.altitudeM,
            pressureHpa = _state.value.pressureHpa,
            hasFix = true,
            pausedAtMs = _state.value.pausedAtMs,
        )
    }

    /** Fold a fresh barometric-pressure reading into state (null = unavailable). */
    fun ingestPressure(hpa: Double?) {
        if (session == null) return
        _state.value = _state.value.copy(pressureHpa = hpa)
    }

    suspend fun tick(nowMs: Long) {
        val s = session ?: return
        val prev = _state.value
        val st = s.state(selectedMetric, nowMs).copy(
            recording = recording,
            headingDeg = prev.headingDeg,
            altitudeM = prev.altitudeM,
            pressureHpa = prev.pressureHpa,
            hasFix = prev.hasFix,
            pausedAtMs = prev.pausedAtMs,
        )
        val raw = when {
            prev.paused -> PAUSED_NAMEPLATE
            prev.hasFix -> selectedMetric.render(st, unit)
            selectedMetric == ActivityMetric.PRESSURE && st.pressureHpa != null ->
                selectedMetric.render(st, unit)
            else -> ACQUIRING_NAMEPLATE
        }
        val reachable = pusher.maybePush(watchAddress(), raw, nowMs, prev.watchReachable, pushIntervalMs)
        _state.value = st.copy(watchReachable = reachable, lastPushText = pusher.lastPushText)
    }

    fun pause(nowMs: Long) {
        val s = session ?: return
        pauseSource = PauseSource.MANUAL
        s.pause(nowMs)
        pusher.forceNext()
        _state.value = _state.value.copy(paused = true, pausedAtMs = nowMs)
    }

    fun resume(nowMs: Long) {
        val s = session ?: return
        pauseSource = PauseSource.NONE
        s.resume(nowMs)
        pusher.forceNext()
        _state.value = _state.value.copy(paused = false, pausedAtMs = null)
    }

    fun cycleMetric() {
        val order = activeOrder()
        val idx = order.indexOf(selectedMetric).coerceAtLeast(0)
        selectedMetric = order[(idx + 1) % order.size]
        pusher.forceNext()
        _state.value = _state.value.copy(selectedMetric = selectedMetric)
    }

    private fun activeOrder(): List<ActivityMetric> {
        val mode = if (recording) ActivityMode.RECORDING else ActivityMode.GLANCE
        return config.enabledOrder(mode).ifEmpty {
            listOf(if (recording) ActivityMetric.PACE else ActivityMetric.ORIENTATION)
        }
    }

    fun setUnit(newUnit: ActivityUnit) {
        unit = newUnit
        pusher.forceNext()
    }

    suspend fun flush() {
        if (session != null && recording) store.save(snapshot(endedAtMs = null, abnormal = false))
    }

    suspend fun stop(abnormal: Boolean = false) {
        if (session == null) return
        if (recording) {
            store.save(snapshot(endedAtMs = now(), abnormal = abnormal))
            watchAddress()?.let { autoSleep.restoreAfterActivity(it) }
        }
        session = null
        recording = false
        pauseSource = PauseSource.NONE
        _state.value = ActivityState()
    }

    private fun snapshot(endedAtMs: Long?, abnormal: Boolean): ActivityTrack {
        val elapsed = ((endedAtMs ?: now()) - startedAtMs).coerceAtLeast(0L)
        val distance = session?.distanceMeters ?: 0.0
        val avgPaceSecPerKm =
            if (distance > 0.0) (elapsed / MILLIS_PER_SECOND) / (distance / METERS_PER_KM) else 0.0
        return ActivityTrack(
            id = trackId,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            endedAbnormally = abnormal,
            unit = unit,
            points = points.toList(),
            summary = ActivitySummary(
                distanceM = distance,
                movingTimeMs = elapsed, // MVP: moving-gap subtraction is a later refinement
                elapsedTimeMs = elapsed,
                avgPaceSecPerKm = avgPaceSecPerKm,
            ),
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        const val METERS_PER_KM = 1000.0
        const val SPEED_GATE_MPS = 0.5f
        const val ACQUIRING_NAMEPLATE = " GPS  " // 6 cells: steady GPS-lock indicator until first fix
        const val PAUSED_NAMEPLATE = "PAUSE " // 6 cells: steady paused indicator
    }
}
