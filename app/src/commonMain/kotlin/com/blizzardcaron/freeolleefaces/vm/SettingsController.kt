package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.auto.Scheduler
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.ui.HomeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Owns the settings cluster extracted from [com.blizzardcaron.freeolleefaces.AppViewModel]: the
 * update-interval/sleep-window/auto-sleep setters and the lat/lng coordinate editor. Moved
 * verbatim; the only renames are `viewModelScope` -> `scope`, `state.X` -> `state().X`,
 * `state = transform(state)` -> `update { transform(it) }`, `nowMs()` -> the injected [clock]
 * (matching the [TimerController]/[ComplicationController] precedent), and the
 * two cross-cluster calls into [ComplicationController] (`complications.tempNextText()` /
 * `complications.refreshActive(false, false)`) -> the injected [tempNextText]/[refreshActive]
 * lambdas, so this controller carries no hard dependency on [ComplicationController].
 * `onWatchPicked` and the rest of the connection-lifecycle state machine are NOT part of this
 * cluster and stay on the VM facade. No moved method reads `HomeState` (only writes via [update]),
 * so unlike the other controllers this one has no `state: () -> HomeState` reader.
 */
class SettingsController(
    private val prefs: Prefs,
    private val scheduler: Scheduler,
    private val scope: CoroutineScope,
    private val update: ((HomeState) -> HomeState) -> Unit,
    private val tempNextText: () -> String,
    private val refreshActive: (force: Boolean, push: Boolean) -> Unit,
    private val clock: Clock = Clock.System,
) {
    private var debounceJob: Job? = null

    companion object {
        /** Coordinate-edit debounce before re-fetching the active complication. */
        private const val COORD_DEBOUNCE_MS = 500L
    }

    fun onCoordEdit(lat: String, lng: String) {
        update { it.copy(lat = lat, lng = lng) }
        val latD = lat.toDoubleOrNull()
        val lngD = lng.toDoubleOrNull()
        if (latD != null && lngD != null && coordsInRange(latD, lngD)) {
            prefs.lastLat = latD
            prefs.lastLng = lngD
            prefs.lastLocationFetchedMs = clock.now().toEpochMilliseconds()
            update {
                it.copy(
                    locationLabel = locLabel(latD, lngD),
                    locationFreshness = "just now",
                )
            }
        }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(COORD_DEBOUNCE_MS)
            refreshActive(false, false)
        }
    }

    fun setInterval(mins: Int) {
        prefs.updateIntervalMinutes = mins
        update { it.copy(updateIntervalMinutes = prefs.updateIntervalMinutes, tempNext = tempNextText()) }
        scheduler.reschedule()
    }

    fun setPowerSavingEnabled(enabled: Boolean) {
        prefs.powerSavingEnabled = enabled
        update { it.copy(powerSavingEnabled = enabled, tempNext = tempNextText()) }
        scheduler.reschedule()
    }

    fun setScreenSleepTimeout(sec: Int) {
        prefs.screenSleepTimeoutSec = sec
        update { it.copy(screenSleepTimeoutSec = prefs.screenSleepTimeoutSec) }
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        prefs.quietHoursEnabled = enabled
        update { it.copy(quietHoursEnabled = enabled, tempNext = tempNextText()) }
        scheduler.reschedule()
    }

    fun setQuietHoursStart(min: Int) {
        prefs.quietHoursStartMin = min
        update { it.copy(quietHoursStartMin = min, tempNext = tempNextText()) }
        scheduler.reschedule()
    }

    fun setQuietHoursEnd(min: Int) {
        prefs.quietHoursEndMin = min
        update { it.copy(quietHoursEndMin = min, tempNext = tempNextText()) }
        scheduler.reschedule()
    }
}
