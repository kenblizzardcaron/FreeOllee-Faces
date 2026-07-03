package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.auto.AutoUpdateSchedule
import com.blizzardcaron.freeolleefaces.auto.Scheduler
import com.blizzardcaron.freeolleefaces.auto.isBatteryCacheFresh
import com.blizzardcaron.freeolleefaces.auto.isTempCacheFresh
import com.blizzardcaron.freeolleefaces.ble.BatteryReadback
import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.format.BatteryReadout
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.format.WeatherErrorCopy
import com.blizzardcaron.freeolleefaces.format.formatDecimal
import com.blizzardcaron.freeolleefaces.health.StepsProvider
import com.blizzardcaron.freeolleefaces.location.LocationProvider
import com.blizzardcaron.freeolleefaces.location.freshnessLabel
import com.blizzardcaron.freeolleefaces.location.isLocationStale
import com.blizzardcaron.freeolleefaces.notifications.NotificationAccessChecker
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.ring.NoopRingStepsSource
import com.blizzardcaron.freeolleefaces.ring.RingStepsSource
import com.blizzardcaron.freeolleefaces.ring.stepsIfEnabled
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.PreviewState
import com.blizzardcaron.freeolleefaces.ui.refreshing
import com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient
import com.blizzardcaron.freeolleefaces.weather.RetryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Owns the complication cluster extracted from [com.blizzardcaron.freeolleefaces.AppViewModel]:
 * weather/temperature previews, steps, notifications, custom text, and the active-complication
 * refresh/activate path. Moved verbatim; the only renames are `viewModelScope` -> `scope`,
 * `state.X` -> `state().X`, `state = transform(state)` -> `update { transform(it) }`, and
 * `Clock.System` -> the injected [clock] (matching the [vm.AlarmController]/[vm.TimerController]
 * precedent). The
 * `scheduler` dependency (used only by [activate]) is injected here since [activate] is the only
 * complication-cluster method that calls `scheduler.reschedule()` — the VM's other 6 call sites
 * (setInterval/setPowerSavingEnabled/setQuietHoursEnabled/setQuietHoursStart/setQuietHoursEnd/onStart)
 * are outside this cluster and keep
 * their own reference. The shared `HomeState.sending` flag is NOT duplicated here — [state]/[update]
 * read and write the VM's single shared flag (also used by the VM's own `sendAndReport` and
 * [vm.TimerController]'s `pushTimerFrame`), via the injected accessors.
 */
// 12 injected dependencies, each defaulted or VM-supplied — standard constructor DI, matching the
// AppViewModel precedent; bundling into a holder type would only obscure the wiring
@Suppress("LongParameterList")
class ComplicationController(
    private val prefs: Prefs,
    private val ble: BleClient,
    private val steps: StepsProvider,
    private val location: LocationProvider,
    private val notificationAccess: NotificationAccessChecker,
    private val scheduler: Scheduler,
    private val scope: CoroutineScope,
    private val showSnackbar: (String) -> Unit,
    private val state: () -> HomeState,
    private val update: ((HomeState) -> HomeState) -> Unit,
    private val ringSteps: RingStepsSource = NoopRingStepsSource,
    private val clock: Clock = Clock.System,
) {
    // Per-face jobs: a single shared job let refreshAllPreviews' pressure refresh cancel the
    // in-flight battery BLE read (card stuck on "Loading…"); a face may only cancel its own
    // predecessor.
    private var tempJob: Job? = null
    private var batteryJob: Job? = null
    private var pressureJob: Job? = null
    private var altitudeJob: Job? = null

    companion object {
        /** Custom-text face is a 6-character watch display; longer input is truncated. */
        private const val CUSTOM_TEXT_MAX_LENGTH = 6
    }

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    private fun validCoords(): Pair<Double, Double>? {
        val lat = state().lat.toDoubleOrNull()
        val lng = state().lng.toDoubleOrNull()
        return if (lat != null && lng != null && coordsInRange(lat, lng)) lat to lng else null
    }

    private fun pushIfWatch(payload: String) {
        val addr = prefs.watchAddress ?: return
        scope.launch { sendAndReport(addr, payload) }
    }

    fun pushCountIfWatch() {
        val addr = prefs.watchAddress ?: return
        val packet = NotificationCount.packetFor(prefs.notificationCount)
        scope.launch {
            ble.sendPacket(addr, packet)
                .onSuccess { showSnackbar("Sent notifications: ${prefs.notificationCount}") }
                .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
        }
    }

    fun sendCustom(text: String) {
        val addr = prefs.watchAddress ?: return
        prefs.customText = text
        scope.launch {
            val result = sendAndReport(addr, DisplayFormatter.custom(text))
            if (result.isSuccess) {
                prefs.customSentMs = nowMs()
                update { it.copy(customSent = "Sent '$text' at ${clockTime(prefs.customSentMs!!)}") }
            }
        }
    }

    private fun interval(): Int = state().updateIntervalMinutes

    // Reads from prefs (the just-persisted source of truth) so a setting change reflects
    // immediately — computing from `state` inside the same copy would see the pre-change value.
    fun tempNextText(): String {
        val sleep = prefs.pushPauseWindow()
        val fire = AutoUpdateSchedule.nextTemperatureFire(
            clock.now().toLocalDateTime(TimeZone.currentSystemDefault()),
            prefs.updateIntervalMinutes,
            sleep,
        )
        return "Next update ${CLOCK.format(fire.time)}"
    }

    fun refreshTemp(force: Boolean, push: Boolean) {
        val c = validCoords()
        if (c == null) {
            update { it.copy(tempPreview = PreviewState.Error("Location not set — open Settings (⚙)")) }
            return
        }
        val (lat, lng) = c
        if (!force && isTempCacheFresh(
                prefs.tempFetchedMs, prefs.tempCacheUnit, state().tempUnit, interval(), nowMs(),
            )
        ) {
            val cached = prefs.tempValue!!
            val payload = DisplayFormatter.temperature(cached, state().tempUnit)
            val human = "Currently: ${formatDecimal(cached, 1)}°${state().tempUnit.symbol}"
            update {
                it.copy(
                    tempPreview = PreviewState.Ready(payload, human),
                    tempUpdated = "Updated ${clockTime(prefs.tempFetchedMs!!)}",
                    tempNext = tempNextText(),
                )
            }
            if (push) pushIfWatch(payload)
            return
        }
        tempJob?.cancel()
        tempJob = scope.launch {
            update { it.copy(tempPreview = it.tempPreview.refreshing()) }
            OpenMeteoClient.currentTemp(lat, lng, state().tempUnit, RetryPolicy.Preview)
                .onSuccess { temp ->
                    prefs.recordTempFetch(temp, state().tempUnit)
                    val payload = DisplayFormatter.temperature(temp, state().tempUnit)
                    val human = "Currently: ${formatDecimal(temp, 1)}°${state().tempUnit.symbol}"
                    update {
                        it.copy(
                            tempPreview = PreviewState.Ready(payload, human),
                            tempUpdated = "Updated ${clockTime(prefs.tempFetchedMs!!)}",
                            tempNext = tempNextText(),
                        )
                    }
                    if (push) pushIfWatch(payload)
                }
                .onFailure { err ->
                    // Fetch failed: fall back to the last cached temp (only if it's in the unit we'd
                    // display) and mark it stale with a leading 'E'. No usable cache -> show the error.
                    val cached = prefs.tempValue
                    if (cached != null && prefs.tempCacheUnit == state().tempUnit) {
                        val payload = DisplayFormatter.temperature(cached, state().tempUnit, stale = true)
                        val human = "Currently: ${formatDecimal(cached, 1)}°${state().tempUnit.symbol} (stale)"
                        update {
                            it.copy(
                                tempPreview = PreviewState.Ready(payload, human),
                                tempUpdated = prefs.tempFetchedMs?.let { ms -> "Updated ${clockTime(ms)}" },
                            )
                        }
                        if (push) pushIfWatch(payload)
                    } else {
                        update { it.copy(tempPreview = PreviewState.Error(WeatherErrorCopy.describe(err))) }
                    }
                }
        }
    }

    fun refreshSteps(push: Boolean) {
        // Ring merge: when the RingConn opt-in is on, display the higher of the ring's live
        // onboard count and Health Connect; on an HC failure a ring read still counts as fresh.
        fun showFreshSteps(count: Long) {
            prefs.recordStepsFetch(count)
            val payload = DisplayFormatter.steps(count)
            update {
                it.copy(
                    stepsPreview = PreviewState.Ready(payload, stepsHuman(count)),
                    stepsUpdated = "Updated ${clockTime(prefs.stepsFetchedMs!!)}",
                )
            }
            if (push) pushIfWatch(payload)
        }

        // Read failed everywhere: fall back to the last cached step count, marked stale with 'E'.
        fun showCachedOrError() {
            val cached = prefs.lastStepCount
            if (cached != null) {
                val payload = DisplayFormatter.steps(cached, stale = true)
                update {
                    it.copy(
                        stepsPreview = PreviewState.Ready(payload, stepsHuman(cached) + " (stale)"),
                        stepsUpdated = prefs.stepsFetchedMs?.let { ms -> "Updated ${clockTime(ms)}" },
                    )
                }
                if (push) pushIfWatch(payload)
            } else {
                update {
                    it.copy(
                        stepsPreview = PreviewState.Error("Couldn't read steps from Health Connect"),
                    )
                }
            }
        }

        scope.launch {
            if (!steps.hasReadPermission()) {
                update {
                    it.copy(
                        stepsHealthGranted = false,
                        stepsPreview = PreviewState.Error("Grant Health access to read steps"),
                    )
                }
                return@launch
            }
            update { it.copy(stepsHealthGranted = true, stepsPreview = it.stepsPreview.refreshing()) }
            // Ring and Health Connect read concurrently: the ring's short-lived GATT cycle can
            // take seconds, so it must not extend the refresh past max(ring, HC) wall-clock.
            val ringAsync = async { ringSteps.stepsIfEnabled(prefs) }
            steps.todaySteps()
                .onSuccess { count -> showFreshSteps(maxOf(count, ringAsync.await() ?: count)) }
                .onFailure {
                    val ring = ringAsync.await()
                    if (ring != null) showFreshSteps(ring) else showCachedOrError()
                }
        }
    }

    fun refreshBattery(push: Boolean, force: Boolean = false) {
        val addr = prefs.watchAddress
        if (addr == null) {
            update { it.copy(batteryPreview = PreviewState.Error("Pair a watch to read battery")) }
            return
        }
        // Mirror refreshTemp: within the settings update interval, render the cached voltage
        // instead of re-reading BLE (the dashboard poll calls this every 60 s).
        val cachedMv = prefs.batteryValueMv
        if (!force && cachedMv != null && isBatteryCacheFresh(prefs.batteryFetchedMs, interval(), nowMs())) {
            val payload = DisplayFormatter.battery(cachedMv, state().batteryReadout)
            update {
                it.copy(
                    batteryPreview = PreviewState.Ready(
                        payload,
                        DisplayFormatter.batteryHuman(cachedMv, state().batteryReadout),
                    ),
                    batteryUpdated = "Updated ${clockTime(prefs.batteryFetchedMs!!)}",
                    batteryNext = tempNextText(),
                )
            }
            if (push) pushIfWatch(payload)
            return
        }
        batteryJob?.cancel()
        batteryJob = scope.launch {
            // Keep the last value on the LCD during a re-read (refreshing() shows "Updating…");
            // a bare Loading only before the first-ever read.
            update { it.copy(batteryPreview = it.batteryPreview.refreshing()) }
            val mv = BatteryReadback.read(ble, addr)
            if (mv != null) {
                prefs.recordBatteryFetch(mv)
                val payload = DisplayFormatter.battery(mv, state().batteryReadout)
                update {
                    it.copy(
                        batteryPreview = PreviewState.Ready(
                            payload, DisplayFormatter.batteryHuman(mv, state().batteryReadout),
                        ),
                        batteryUpdated = "Updated ${clockTime(prefs.batteryFetchedMs!!)}",
                        batteryNext = tempNextText(),
                    )
                }
                if (push) pushIfWatch(payload)
            } else {
                // Read failed (watch unreachable / malformed) — fall back to the last cached voltage,
                // marked stale with 'E'. No cache -> show the error.
                val cached = prefs.batteryValueMv
                if (cached != null) {
                    val payload = DisplayFormatter.battery(cached, state().batteryReadout, stale = true)
                    update {
                        it.copy(
                            batteryPreview = PreviewState.Ready(
                                payload,
                                DisplayFormatter.batteryHuman(cached, state().batteryReadout) + " (stale)",
                            ),
                            batteryUpdated = prefs.batteryFetchedMs?.let { ms -> "Updated ${clockTime(ms)}" },
                        )
                    }
                    if (push) pushIfWatch(payload)
                } else {
                    update { it.copy(batteryPreview = PreviewState.Error("Couldn't read battery from the watch")) }
                }
            }
        }
    }

    fun setBatteryReadout(readout: BatteryReadout) {
        prefs.batteryReadout = readout
        update { it.copy(batteryReadout = readout) }
        refreshBattery(push = state().activeComplication == ActiveComplication.BATTERY)
    }

    fun refreshPressure(push: Boolean) {
        val c = validCoords()
        if (c == null) {
            update { it.copy(pressurePreview = PreviewState.Error("Location not set — open Settings (⚙)")) }
            return
        }
        val (lat, lng) = c
        val imperial = prefs.activityUnit == ActivityUnit.IMPERIAL
        pressureJob?.cancel()
        pressureJob = scope.launch {
            update { it.copy(pressurePreview = it.pressurePreview.refreshing()) }
            OpenMeteoClient.currentPressureHpa(lat, lng, RetryPolicy.Preview)
                .onSuccess { hpa ->
                    prefs.recordPressureFetch(hpa)
                    val payload = DisplayFormatter.pressure(hpa, imperial)
                    update {
                        it.copy(
                            pressurePreview = PreviewState.Ready(payload, "Currently: ${formatDecimal(hpa, 1)} hPa"),
                            pressureUpdated = "Updated ${clockTime(nowMs())}",
                            pressureNext = tempNextText(),
                        )
                    }
                    if (push) pushIfWatch(payload)
                }
                .onFailure { err ->
                    val cached = prefs.pressureValueHpa
                    if (cached != null) {
                        val payload = DisplayFormatter.pressure(cached, imperial, stale = true)
                        update {
                            it.copy(
                                pressurePreview = PreviewState.Ready(
                                    payload, "Currently: ${formatDecimal(cached, 1)} hPa (stale)",
                                ),
                            )
                        }
                        if (push) pushIfWatch(payload)
                    } else {
                        update { it.copy(pressurePreview = PreviewState.Error(WeatherErrorCopy.describe(err))) }
                    }
                }
        }
    }

    fun refreshAltitude(push: Boolean) {
        val c = validCoords()
        if (c == null) {
            update { it.copy(altitudePreview = PreviewState.Error("Location not set — open Settings (⚙)")) }
            return
        }
        val (lat, lng) = c
        val imperial = prefs.activityUnit == ActivityUnit.IMPERIAL
        altitudeJob?.cancel()
        altitudeJob = scope.launch {
            update { it.copy(altitudePreview = it.altitudePreview.refreshing()) }
            OpenMeteoClient.currentElevationM(lat, lng, RetryPolicy.Preview)
                .onSuccess { meters ->
                    prefs.recordAltitudeFetch(meters)
                    val payload = DisplayFormatter.altitude(meters, imperial)
                    update {
                        it.copy(
                            altitudePreview = PreviewState.Ready(payload, "Elevation: ${formatDecimal(meters, 0)} m"),
                            altitudeUpdated = "Updated ${clockTime(nowMs())}",
                            altitudeNext = tempNextText(),
                        )
                    }
                    if (push) pushIfWatch(payload)
                }
                .onFailure { err ->
                    val cached = prefs.altitudeValueM
                    if (cached != null) {
                        val payload = DisplayFormatter.altitude(cached, imperial, stale = true)
                        update {
                            it.copy(
                                altitudePreview = PreviewState.Ready(
                                    payload, "Elevation: ${formatDecimal(cached, 0)} m (stale)",
                                ),
                            )
                        }
                        if (push) pushIfWatch(payload)
                    } else {
                        update { it.copy(altitudePreview = PreviewState.Error(WeatherErrorCopy.describe(err))) }
                    }
                }
        }
    }

    fun refreshActive(force: Boolean, push: Boolean) {
        when (state().activeComplication) {
            ActiveComplication.TEMPERATURE -> refreshTemp(force, push)
            ActiveComplication.STEPS -> refreshSteps(push)
            ActiveComplication.BATTERY -> refreshBattery(push, force)
            ActiveComplication.PRESSURE -> refreshPressure(push)
            ActiveComplication.ALTITUDE -> refreshAltitude(push)
            ActiveComplication.CUSTOM -> {}
        }
    }

    /**
     * Refresh every face's preview for the dashboard. Never pushes to the watch. Cheap: only
     * temperature can hit the network, and only when its cache has expired (refreshTemp honors
     * isTempCacheFresh); steps is a local read, notifications is read from prefs.
     */
    fun refreshAllPreviews() {
        refreshTemp(force = false, push = false)
        refreshSteps(push = false)
        refreshBattery(push = false)
        refreshPressure(push = false)
        refreshAltitude(push = false)
        update {
            it.copy(
                notificationCount = prefs.notificationCount,
                notificationAccessGranted = notificationAccess.isGranted(),
                notificationsEnabled = prefs.notificationsEnabled,
            )
        }
    }

    fun activate(complication: ActiveComplication) {
        prefs.activeComplication = complication
        update { it.copy(activeComplication = complication) }
        // No navigation: selection updates the radio in place on the Home screen and the
        // card highlight reflects it. The old silent bounce back to Home read as "nothing
        // happened" — it is gone.
        scheduler.reschedule()
        when (complication) {
            ActiveComplication.TEMPERATURE -> refreshTemp(force = false, push = true)
            ActiveComplication.STEPS -> refreshSteps(push = true)
            ActiveComplication.BATTERY -> refreshBattery(push = true)
            ActiveComplication.PRESSURE -> refreshPressure(push = true)
            ActiveComplication.ALTITUDE -> refreshAltitude(push = true)
            ActiveComplication.CUSTOM -> {
                val text = prefs.customText
                if (text.isNotEmpty()) sendCustom(text)
            }
        }
    }

    /**
     * Toggle the notification-count overlay (independent of the name-tag face). Enabling pushes the
     * current count to the weekday slot; disabling restores the real weekday table (count 0).
     */
    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.notificationsEnabled = enabled
        update { it.copy(notificationsEnabled = enabled) }
        val addr = prefs.watchAddress ?: return
        val packet = if (enabled) {
            NotificationCount.packetFor(prefs.notificationCount)
        } else {
            NotificationCount.packetFor(0) // restores the real weekday table
        }
        scope.launch {
            ble.sendPacket(addr, packet)
                .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
        }
    }

    fun setTempUnit(unit: TempUnit) {
        prefs.tempUnit = unit
        update { it.copy(tempUnit = unit) }
        refreshTemp(force = false, push = state().activeComplication == ActiveComplication.TEMPERATURE)
    }

    fun setCustomText(text: String) {
        val capped = text.take(CUSTOM_TEXT_MAX_LENGTH)
        update { it.copy(custom = capped) }
        prefs.customText = capped
    }

    /** Whether the platform health store is usable; the Activity branches on this for the prompt. */
    fun healthAvailability(): StepsProvider.Availability = steps.availability()

    fun onHealthUpdateRequired() {
        showSnackbar("Update Health Connect (in system settings) to read steps.")
    }

    fun onHealthUnavailable() {
        showSnackbar("Health Connect isn't available on this device.")
    }

    fun onPartialHealthGrant() {
        showSnackbar("Allow background read too, so steps keep syncing when the app is closed.")
    }

    fun setLocating(v: Boolean) { update { it.copy(locating = v) } }

    suspend fun fetchLocation() = location.fetch() // Activity calls when it holds permission

    /**
     * Persist fetched coords + update state, then (by default) refresh the active face. The location
     * bootstrap passes [refresh] = false — it does its own single refresh at the end — which is the
     * old `onLocationFetchedSilent` behavior folded in here.
     */
    fun onLocationFetched(lat: Double, lng: Double, refresh: Boolean = true) {
        prefs.lastLat = lat
        prefs.lastLng = lng
        prefs.lastLocationFetchedMs = nowMs()
        update {
            it.copy(
                lat = lat.toString(),
                lng = lng.toString(),
                locating = false,
                locationLabel = locLabel(lat, lng),
                locationFreshness = "just now",
            )
        }
        if (refresh) refreshActive(force = true, push = false)
    }

    /** Bootstrap-refresh failure where saved coords exist: mark "refresh failed" and warn. */
    fun onLocationRefreshFailed() {
        update {
            it.copy(
                locating = false,
                locationFreshness = (freshnessLabel(prefs.lastLocationFetchedMs, nowMs()) ?: "stale") +
                    " · refresh failed",
            )
        }
        showSnackbar("Couldn't refresh location — using saved coordinates")
    }

    fun onLocationDenied() { showSnackbar("Location permission denied — enter coordinates manually.") }

    fun onLocationFailed(message: String?) {
        update { it.copy(locating = false) }
        showSnackbar("Location failed: $message")
    }

    fun onResumeNotifications() {
        update {
            it.copy(
                notificationCount = prefs.notificationCount,
                notificationAccessGranted = notificationAccess.isGranted(),
                notificationsEnabled = prefs.notificationsEnabled,
            )
        }
    }

    /** Convenience: whether the active face is the steps face (Activity uses this when arming reads). */
    fun activeIsSteps(): Boolean = state().activeComplication == ActiveComplication.STEPS

    /** Saved coords present (used by the location bootstrap to decide whether to fetch). */
    fun hasSavedCoords(): Boolean = prefs.lastLat != null && prefs.lastLng != null

    /** Whether the saved location fix is stale (used by the location bootstrap). */
    fun locationIsStale(): Boolean =
        isLocationStale(prefs.lastLocationFetchedMs, nowMs())

    private suspend fun sendAndReport(
        address: String,
        value: String,
        target: Int = OlleeProtocol.TARGET_NAMEPLATE,
    ): Result<Unit> {
        // Backstop so faces never push illegible glyphs to the watch nameplate.
        val out = if (target == OlleeProtocol.TARGET_NAMEPLATE) {
            com.blizzardcaron.freeolleefaces.glyph.NameplateSanitizer.sanitize(value)
        } else {
            value
        }
        update { it.copy(sending = true) }
        return ble.send(address, out, target)
            .onSuccess {
                update { it.copy(sending = false) }
                showSnackbar("Sent '$out'")
            }
            .onFailure { err ->
                update { it.copy(sending = false) }
                showSnackbar("Send failed: ${err.message}")
            }
    }
}
