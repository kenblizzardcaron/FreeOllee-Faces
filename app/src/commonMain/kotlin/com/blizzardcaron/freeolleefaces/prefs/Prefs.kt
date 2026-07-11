package com.blizzardcaron.freeolleefaces.prefs

import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.auto.SleepWindow
import com.blizzardcaron.freeolleefaces.format.BatteryReadout
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.notify.FailureKind
import com.blizzardcaron.freeolleefaces.worldtime.WorldTimeState
import com.russhwolf.settings.Settings
import kotlinx.datetime.Clock

class Prefs(
    private val settings: Settings,
    private val clock: Clock = Clock.System,
) {

    init {
        // Clean reset: the unified power-saving prefs supersede the old sleep / auto-sleep keys.
        // Purge the dead keys once so they don't linger in storage. Idempotent (remove is a no-op
        // when absent).
        listOf(
            "sleep_enabled", "sleep_start_min", "sleep_end_min",
            "auto_sleep_schedule_enabled", "auto_sleep_window_start_min", "auto_sleep_window_end_min",
            "auto_sleep_in_on", "auto_sleep_in_period_sec", "auto_sleep_out_on", "auto_sleep_out_period_sec",
        ).forEach { settings.remove(it) }
    }

    var lastLat: Double?
        get() = if (settings.hasKey(KEY_LAT)) settings.getFloat(KEY_LAT, 0f).toDouble() else null
        set(value) = if (value == null) settings.remove(KEY_LAT) else settings.putFloat(KEY_LAT, value.toFloat())

    var lastLng: Double?
        get() = if (settings.hasKey(KEY_LNG)) settings.getFloat(KEY_LNG, 0f).toDouble() else null
        set(value) = if (value == null) settings.remove(KEY_LNG) else settings.putFloat(KEY_LNG, value.toFloat())

    var watchAddress: String?
        get() = settings.getStringOrNull(KEY_WATCH)
        set(value) = if (value == null) settings.remove(KEY_WATCH) else settings.putString(KEY_WATCH, value)

    var tempUnit: TempUnit
        get() = settings.getStringOrNull(KEY_TEMP_UNIT)
            ?.let { runCatching { TempUnit.valueOf(it) }.getOrNull() }
            ?: TempUnit.FAHRENHEIT
        set(value) = settings.putString(KEY_TEMP_UNIT, value.name)

    var activeComplication: ActiveComplication
        get() {
            val stored = settings.getStringOrNull(KEY_ACTIVE_COMPLICATION)
            // Legacy: NOTIFICATIONS used to be a mutually-exclusive face. It's now an independent
            // weekday-slot overlay, so migrate it to "the count overlay is on, name-tag face is
            // the default" — once, in place.
            if (stored == LEGACY_NOTIFICATIONS_COMPLICATION) {
                notificationsEnabled = true
                settings.putString(KEY_ACTIVE_COMPLICATION, ActiveComplication.TEMPERATURE.name)
                return ActiveComplication.TEMPERATURE
            }
            stored?.let {
                runCatching { ActiveComplication.valueOf(it) }.getOrNull()?.let { face -> return face }
            }
            // No stored/parseable active face: default to TEMPERATURE and write it in place. The
            // legacy `auto_source` key only ever held TEMPERATURE or the now-retired SUN, both of
            // which collapse to TEMPERATURE, so it no longer needs to be consulted.
            settings.putString(KEY_ACTIVE_COMPLICATION, ActiveComplication.TEMPERATURE.name)
            return ActiveComplication.TEMPERATURE
        }
        set(value) = settings.putString(KEY_ACTIVE_COMPLICATION, value.name)

    /**
     * Whether the notification count rides the watch's weekday slot (`0x34`). Independent of
     * [activeComplication] — the count overlay coexists with whichever name-tag face is active.
     */
    var notificationsEnabled: Boolean
        get() = settings.getBoolean(KEY_NOTIFICATIONS_ENABLED, false)
        set(value) = settings.putBoolean(KEY_NOTIFICATIONS_ENABLED, value)

    /** Opt-in: read live steps from the bonded RingConn ring over BLE (off by default). */
    var ringConnStepsEnabled: Boolean
        get() = settings.getBoolean(KEY_RINGCONN_STEPS, false)
        set(value) = settings.putBoolean(KEY_RINGCONN_STEPS, value)

    /** BLE address of the chosen RingConn ring; null until discovery selects one. */
    var ringConnAddress: String?
        get() = settings.getStringOrNull(KEY_RINGCONN_ADDRESS)
        set(value) {
            if (value == null) {
                settings.remove(KEY_RINGCONN_ADDRESS)
            } else {
                settings.putString(KEY_RINGCONN_ADDRESS, value)
            }
        }

    /** True while an activity session is live; a crash-safety breadcrumb the watchdog reconciles. */
    var activityActive: Boolean
        get() = settings.getBoolean(KEY_ACTIVITY_ACTIVE, false)
        set(value) = settings.putBoolean(KEY_ACTIVITY_ACTIVE, value)

    /** Distance/pace unit system for activity mode (independent of the temperature unit). */
    var activityUnit: ActivityUnit
        get() = settings.getStringOrNull(KEY_ACTIVITY_UNIT)
            ?.let { runCatching { ActivityUnit.valueOf(it) }.getOrNull() }
            ?: ActivityUnit.IMPERIAL
        set(value) = settings.putString(KEY_ACTIVITY_UNIT, value.name)

    /**
     * The watch auto-sleep profile captured at activity start, restored on stop. Null when no
     * session has stashed one. Stored as a present-flag + on-flag + period triple.
     */
    var savedAutoSleepProfile: AutoSleepProfile?
        get() = if (!settings.getBoolean(KEY_SAVED_AS_PRESENT, false)) {
            null
        } else {
            AutoSleepProfile(
                autoSleepOn = settings.getBoolean(KEY_SAVED_AS_ON, false),
                periodSec = settings.getInt(KEY_SAVED_AS_PERIOD, 0),
            )
        }
        set(value) {
            if (value == null) {
                settings.remove(KEY_SAVED_AS_PRESENT)
                settings.remove(KEY_SAVED_AS_ON)
                settings.remove(KEY_SAVED_AS_PERIOD)
            } else {
                settings.putBoolean(KEY_SAVED_AS_PRESENT, true)
                settings.putBoolean(KEY_SAVED_AS_ON, value.autoSleepOn)
                settings.putInt(KEY_SAVED_AS_PERIOD, value.periodSec)
            }
        }

    var tempValue: Double?
        get() = if (settings.hasKey(KEY_TEMP_VALUE)) settings.getFloat(KEY_TEMP_VALUE, 0f).toDouble() else null
        set(
        value
        ) = if (value == null) {
            settings.remove(
                KEY_TEMP_VALUE
            )
        } else {
            settings.putFloat(KEY_TEMP_VALUE, value.toFloat())
        }

    var tempCacheUnit: TempUnit?
        get() = settings.getStringOrNull(KEY_TEMP_CACHE_UNIT)
            ?.let { runCatching { TempUnit.valueOf(it) }.getOrNull() }
        set(
        value
        ) = if (value == null) {
            settings.remove(
                KEY_TEMP_CACHE_UNIT
            )
        } else {
            settings.putString(KEY_TEMP_CACHE_UNIT, value.name)
        }

    var tempFetchedMs: Long?
        get() = if (settings.hasKey(KEY_TEMP_FETCHED_MS)) settings.getLong(KEY_TEMP_FETCHED_MS, 0L) else null
        set(
        value
        ) = if (value == null) {
            settings.remove(
                KEY_TEMP_FETCHED_MS
            )
        } else {
            settings.putLong(KEY_TEMP_FETCHED_MS, value)
        }

    var lastLocationFetchedMs: Long?
        get() = if (settings.hasKey(KEY_LOCATION_FETCHED_MS)) settings.getLong(KEY_LOCATION_FETCHED_MS, 0L) else null
        set(
        value
        ) = if (value == null) {
            settings.remove(
                KEY_LOCATION_FETCHED_MS
            )
        } else {
            settings.putLong(KEY_LOCATION_FETCHED_MS, value)
        }

    var customText: String
        get() = settings.getString(KEY_CUSTOM_TEXT, "")
        set(value) = settings.putString(KEY_CUSTOM_TEXT, value)

    var quickTimerSeconds: Int
        get() = settings.getInt(KEY_QUICK_TIMER_SECONDS, DEFAULT_QUICK_TIMER_SECONDS)
        set(value) = settings.putInt(KEY_QUICK_TIMER_SECONDS, value.coerceAtLeast(0))

    /**
     * The two independent "Send to watch" modifiers, mirroring the official app and decoupled from
     * the quick-timer time itself (see [com.blizzardcaron.freeolleefaces.ble.OlleeProtocol.TimerStartMode.of]).
     * Defaults reproduce the previous single "Start on watch" behavior: start the quick timer as a
     * single countdown.
     */
    var quickTimerStartFromApp: Boolean
        get() = settings.getBoolean(KEY_QUICK_TIMER_START, true)
        set(value) = settings.putBoolean(KEY_QUICK_TIMER_START, value)

    var quickTimerIntervalMode: Boolean
        get() = settings.getBoolean(KEY_QUICK_TIMER_INTERVAL, false)
        set(value) = settings.putBoolean(KEY_QUICK_TIMER_INTERVAL, value)

    /** Alarm-mode quick timer: when on, the card takes a wall-clock target time instead of H/M/S. */
    var quickTimerAlarmMode: Boolean
        get() = settings.getBoolean(KEY_QUICK_TIMER_ALARM_MODE, false)
        set(value) = settings.putBoolean(KEY_QUICK_TIMER_ALARM_MODE, value)

    /** Alarm-mode target hour, 0..23 (24h; UI renders 12h + AM/PM). */
    var quickTimerAlarmHour: Int
        get() = settings.getInt(KEY_QUICK_TIMER_ALARM_HOUR, DEFAULT_ALARM_HOUR)
        set(value) = settings.putInt(KEY_QUICK_TIMER_ALARM_HOUR, value.coerceIn(0, MAX_HOUR))

    /** Alarm-mode target minute, 0..59. */
    var quickTimerAlarmMinute: Int
        get() = settings.getInt(KEY_QUICK_TIMER_ALARM_MINUTE, 0)
        set(value) = settings.putInt(KEY_QUICK_TIMER_ALARM_MINUTE, value.coerceIn(0, MAX_MINUTE))

    var customSentMs: Long?
        get() = if (settings.hasKey(KEY_CUSTOM_SENT_MS)) settings.getLong(KEY_CUSTOM_SENT_MS, 0L) else null
        set(
        value
        ) = if (value == null) {
            settings.remove(
                KEY_CUSTOM_SENT_MS
            )
        } else {
            settings.putLong(KEY_CUSTOM_SENT_MS, value)
        }

    /** Live count of undismissed, non-persistent notifications, kept by the listener service. */
    var notificationCount: Int
        get() = settings.getInt(KEY_NOTIFICATION_COUNT, 0)
        set(value) = settings.putInt(KEY_NOTIFICATION_COUNT, value)

    /** World Time slots: IANA zone ids, comma-joined, display order (max WorldTime.MAX_SLOTS). */
    var worldTimeSlots: List<String>
        get() = settings.getStringOrNull(KEY_WT_SLOTS)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = settings.putString(KEY_WT_SLOTS, value.joinToString(","))

    /** The slot zone currently pushed to the watch's World Time face; null = custom/unset. */
    var worldTimeActiveZone: String?
        get() = settings.getStringOrNull(KEY_WT_ACTIVE)
        set(value) = if (value == null) settings.remove(KEY_WT_ACTIVE) else settings.putString(KEY_WT_ACTIVE, value)

    /** An on-watch offset adopted at reconcile that matched no slot (seconds east of UTC). */
    var worldTimeCustomOffsetSec: Int?
        get() =
            if (settings.hasKey(KEY_WT_CUSTOM_SEC)) {
                settings.getInt(KEY_WT_CUSTOM_SEC, 0)
            } else {
                null
            }
        set(value) {
            if (value == null) {
                settings.remove(KEY_WT_CUSTOM_SEC)
            } else {
                settings.putInt(KEY_WT_CUSTOM_SEC, value)
            }
        }

    /** Whether the Casio home<->world swap is in effect. */
    var worldTimeSwapped: Boolean
        get() = settings.getBoolean(KEY_WT_SWAPPED, false)
        set(value) = settings.putBoolean(KEY_WT_SWAPPED, value)

    /** The last world-offset (seconds) successfully written in a weekday-register push. */
    var worldTimeLastPushedOffsetSec: Int?
        get() =
            if (settings.hasKey(KEY_WT_LAST_PUSHED_SEC)) {
                settings.getInt(KEY_WT_LAST_PUSHED_SEC, 0)
            } else {
                null
            }
        set(value) {
            if (value == null) {
                settings.remove(KEY_WT_LAST_PUSHED_SEC)
            } else {
                settings.putInt(KEY_WT_LAST_PUSHED_SEC, value)
            }
        }

    /** The last main-clock offset (seconds) successfully written by a Casio swap set-clock write. */
    var worldTimeLastPushedClockOffsetSec: Int?
        get() =
            if (settings.hasKey(KEY_WT_LAST_PUSHED_CLOCK_SEC)) {
                settings.getInt(KEY_WT_LAST_PUSHED_CLOCK_SEC, 0)
            } else {
                null
            }
        set(value) {
            if (value == null) {
                settings.remove(KEY_WT_LAST_PUSHED_CLOCK_SEC)
            } else {
                settings.putInt(KEY_WT_LAST_PUSHED_CLOCK_SEC, value)
            }
        }

    /**
     * Latitude (degrees × 1000) stamped by the last successful swap clock write. The background
     * chain reuses [worldTimeSwapLatE3]/[worldTimeSwapLonE3] so it never needs a fresh GPS fix
     * (Task 13 addendum); null until a foreground swap has stamped a fix.
     */
    var worldTimeSwapLatE3: Int?
        get() =
            if (settings.hasKey(KEY_WT_SWAP_LAT_E3)) {
                settings.getInt(KEY_WT_SWAP_LAT_E3, 0)
            } else {
                null
            }
        set(value) {
            if (value == null) {
                settings.remove(KEY_WT_SWAP_LAT_E3)
            } else {
                settings.putInt(KEY_WT_SWAP_LAT_E3, value)
            }
        }

    /** Longitude (degrees × 1000) counterpart to [worldTimeSwapLatE3]. */
    var worldTimeSwapLonE3: Int?
        get() =
            if (settings.hasKey(KEY_WT_SWAP_LON_E3)) {
                settings.getInt(KEY_WT_SWAP_LON_E3, 0)
            } else {
                null
            }
        set(value) {
            if (value == null) {
                settings.remove(KEY_WT_SWAP_LON_E3)
            } else {
                settings.putInt(KEY_WT_SWAP_LON_E3, value)
            }
        }

    /** The persisted [WorldTimeState] snapshot. */
    fun worldTimeState(): WorldTimeState = WorldTimeState(
        slots = worldTimeSlots,
        activeZoneId = worldTimeActiveZone,
        customOffsetSec = worldTimeCustomOffsetSec,
        swapped = worldTimeSwapped,
    )

    /** Persists every field of [s] (slots, active zone, custom offset, swap flag). */
    fun saveWorldTimeState(s: WorldTimeState) {
        worldTimeSlots = s.slots
        worldTimeActiveZone = s.activeZoneId
        worldTimeCustomOffsetSec = s.customOffsetSec
        worldTimeSwapped = s.swapped
    }

    /** Stamp the cached temperature value, the unit it was fetched in, and the fetch time. */
    fun recordTempFetch(value: Double, unit: TempUnit) {
        tempValue = value
        tempCacheUnit = unit
        tempFetchedMs = clock.now().toEpochMilliseconds()
    }

    /** Cached surface pressure in hPa (raw; formatted on send). null when never fetched. */
    var pressureValueHpa: Double?
        get() =
            if (settings.hasKey(KEY_PRESSURE_VALUE_HPA)) {
                settings.getFloat(KEY_PRESSURE_VALUE_HPA, 0f).toDouble()
            } else {
                null
            }
        set(value) {
            if (value == null) {
                settings.remove(KEY_PRESSURE_VALUE_HPA)
            } else {
                settings.putFloat(KEY_PRESSURE_VALUE_HPA, value.toFloat())
            }
        }

    /** Stamp the cached surface pressure (raw hPa). */
    fun recordPressureFetch(hpa: Double) {
        pressureValueHpa = hpa
    }

    /** Cached terrain elevation in metres (raw; formatted on send). null when never fetched. */
    var altitudeValueM: Double?
        get() =
            if (settings.hasKey(KEY_ALTITUDE_VALUE_M)) {
                settings.getFloat(KEY_ALTITUDE_VALUE_M, 0f).toDouble()
            } else {
                null
            }
        set(value) {
            if (value == null) {
                settings.remove(KEY_ALTITUDE_VALUE_M)
            } else {
                settings.putFloat(KEY_ALTITUDE_VALUE_M, value.toFloat())
            }
        }

    /** Stamp the cached terrain elevation (raw metres). */
    fun recordAltitudeFetch(meters: Double) {
        altitudeValueM = meters
    }

    /** Shared push cadence (minutes) for the interval-driven faces; one of [IntervalOptions.ALLOWED]. */
    var updateIntervalMinutes: Int
        get() = IntervalOptions.coerce(settings.getInt(KEY_UPDATE_INTERVAL, IntervalOptions.DEFAULT))
        set(value) = settings.putInt(KEY_UPDATE_INTERVAL, IntervalOptions.coerce(value))

    var lastStepCount: Long?
        get() = if (settings.hasKey(KEY_STEPS_COUNT)) settings.getLong(KEY_STEPS_COUNT, 0L) else null
        set(value) = if (value == null) settings.remove(KEY_STEPS_COUNT) else settings.putLong(KEY_STEPS_COUNT, value)

    var stepsFetchedMs: Long?
        get() = if (settings.hasKey(KEY_STEPS_FETCHED_MS)) settings.getLong(KEY_STEPS_FETCHED_MS, 0L) else null
        set(
        value
        ) = if (value == null) {
            settings.remove(
                KEY_STEPS_FETCHED_MS
            )
        } else {
            settings.putLong(KEY_STEPS_FETCHED_MS, value)
        }

    /** Stamp the cached step count and the time it was read from Health Connect. */
    fun recordStepsFetch(count: Long) {
        lastStepCount = count
        stepsFetchedMs = clock.now().toEpochMilliseconds()
    }

    /** How the Battery face renders the watch voltage. Defaults to a percentage estimate. */
    var batteryReadout: BatteryReadout
        get() = settings.getStringOrNull(KEY_BATTERY_READOUT)
            ?.let { runCatching { BatteryReadout.valueOf(it) }.getOrNull() }
            ?: BatteryReadout.PERCENT
        set(value) = settings.putString(KEY_BATTERY_READOUT, value.name)

    /** Cached watch battery voltage in millivolts (raw; formatted on send). null when never read. */
    var batteryValueMv: Int?
        get() = if (settings.hasKey(KEY_BATTERY_VALUE_MV)) settings.getInt(KEY_BATTERY_VALUE_MV, 0) else null
        set(value) =
            if (value == null) settings.remove(KEY_BATTERY_VALUE_MV) else settings.putInt(KEY_BATTERY_VALUE_MV, value)

    var batteryFetchedMs: Long?
        get() = if (settings.hasKey(KEY_BATTERY_FETCHED_MS)) settings.getLong(KEY_BATTERY_FETCHED_MS, 0L) else null
        set(value) {
            if (value == null) {
                settings.remove(KEY_BATTERY_FETCHED_MS)
            } else {
                settings.putLong(KEY_BATTERY_FETCHED_MS, value)
            }
        }

    /** Stamp the cached battery voltage (millivolts) and the time it was read from the watch. */
    fun recordBatteryFetch(milliVolts: Int) {
        batteryValueMv = milliVolts
        batteryFetchedMs = clock.now().toEpochMilliseconds()
    }

    var powerSavingEnabled: Boolean
        get() = settings.getBoolean(KEY_PS_ENABLED, true)
        set(value) = settings.putBoolean(KEY_PS_ENABLED, value)

    var screenSleepTimeoutSec: Int
        get() = settings.getInt(KEY_PS_TIMEOUT, DEFAULT_SCREEN_SLEEP_SEC)
        set(value) = settings.putInt(KEY_PS_TIMEOUT, value.coerceAtLeast(1))

    var quietHoursEnabled: Boolean
        get() = settings.getBoolean(KEY_QH_ENABLED, true)
        set(value) = settings.putBoolean(KEY_QH_ENABLED, value)

    var quietHoursStartMin: Int
        get() = settings.getInt(KEY_QH_START, DEFAULT_QH_START_HOUR * MINUTES_PER_HOUR)
        set(value) = settings.putInt(KEY_QH_START, value)

    var quietHoursEndMin: Int
        get() = settings.getInt(KEY_QH_END, DEFAULT_QH_END_HOUR * MINUTES_PER_HOUR)
        set(value) = settings.putInt(KEY_QH_END, value)

    /** App-side push-pause window: non-null only when power saving and quiet hours are both on. */
    fun pushPauseWindow(): SleepWindow? =
        if (powerSavingEnabled && quietHoursEnabled) {
            SleepWindow(quietHoursStartMin, quietHoursEndMin)
        } else {
            null
        }

    var lastAutoSendMs: Long?
        get() = if (settings.hasKey(KEY_LAST_SEND_MS)) settings.getLong(KEY_LAST_SEND_MS, 0L) else null
        set(value) = if (value == null) settings.remove(KEY_LAST_SEND_MS) else settings.putLong(KEY_LAST_SEND_MS, value)

    var lastAutoSendSummary: String?
        get() = settings.getStringOrNull(KEY_LAST_SEND_SUMMARY)
        set(
        value
        ) = if (value == null) {
            settings.remove(
                KEY_LAST_SEND_SUMMARY
            )
        } else {
            settings.putString(KEY_LAST_SEND_SUMMARY, value)
        }

    var activityPushIntervalMs: Long
        get() = settings.getLong(KEY_PUSH_INTERVAL_MS, DEFAULT_PUSH_INTERVAL_MS)
        set(value) = settings.putLong(KEY_PUSH_INTERVAL_MS, value)

    var autoPauseThresholdMps: Float
        get() = settings.getFloat(KEY_AUTO_PAUSE_MPS, DEFAULT_AUTO_PAUSE_MPS)
        set(value) = settings.putFloat(KEY_AUTO_PAUSE_MPS, value)

    var lastNotifiedKind: FailureKind?
        get() = settings.getStringOrNull(KEY_LAST_NOTIFIED_KIND)
            ?.let { runCatching { FailureKind.valueOf(it) }.getOrNull() }
        set(
        value
        ) = if (value == null) {
            settings.remove(
                KEY_LAST_NOTIFIED_KIND
            )
        } else {
            settings.putString(KEY_LAST_NOTIFIED_KIND, value.name)
        }

    /** Convenience: stamp the time and summary of the most recent background send attempt. */
    fun recordAutoSend(summary: String) {
        lastAutoSendMs = clock.now().toEpochMilliseconds()
        lastAutoSendSummary = summary
    }

    /** Watch screen-off register desired-state, derived from the unified power-saving prefs. */
    fun autoSleepWindowConfig(): AutoSleepWindowConfig {
        if (!powerSavingEnabled) {
            return AutoSleepWindowConfig(
                enabled = false,
                startMin = quietHoursStartMin,
                endMin = quietHoursEndMin,
                inWindow = AutoSleepProfile(autoSleepOn = false, periodSec = screenSleepTimeoutSec),
                outWindow = AutoSleepProfile(autoSleepOn = false, periodSec = screenSleepTimeoutSec),
            )
        }
        val sleep = AutoSleepProfile(autoSleepOn = true, periodSec = screenSleepTimeoutSec)
        return if (!quietHoursEnabled) {
            // 24/7: in == out → window boundaries irrelevant, watch always holds the sleep profile.
            AutoSleepWindowConfig(true, quietHoursStartMin, quietHoursEndMin, sleep, sleep)
        } else {
            // Sleep in window, stay on outside (active off-write outside the window).
            AutoSleepWindowConfig(
                enabled = true,
                startMin = quietHoursStartMin,
                endMin = quietHoursEndMin,
                inWindow = sleep,
                outWindow = AutoSleepProfile(autoSleepOn = false, periodSec = screenSleepTimeoutSec),
            )
        }
    }

    companion object {
        private const val KEY_LAT = "last_lat"
        private const val KEY_LNG = "last_lng"
        private const val KEY_WATCH = "watch_address"
        private const val KEY_TEMP_UNIT = "temp_unit"
        private const val KEY_ACTIVE_COMPLICATION = "active_face"
        private const val KEY_TEMP_VALUE = "temp_value"
        private const val KEY_PRESSURE_VALUE_HPA = "pressure_value_hpa"
        private const val KEY_ALTITUDE_VALUE_M = "altitude_value_m"
        private const val KEY_TEMP_CACHE_UNIT = "temp_cache_unit"
        private const val KEY_TEMP_FETCHED_MS = "temp_fetched_ms"
        private const val KEY_LOCATION_FETCHED_MS = "location_fetched_ms"
        private const val KEY_CUSTOM_TEXT = "custom_text"
        private const val KEY_QUICK_TIMER_SECONDS = "quick_timer_seconds"
        private const val KEY_QUICK_TIMER_START = "quick_timer_start_from_app"
        private const val KEY_QUICK_TIMER_INTERVAL = "quick_timer_interval_mode"
        private const val KEY_QUICK_TIMER_ALARM_MODE = "quick_timer_alarm_mode"
        private const val KEY_QUICK_TIMER_ALARM_HOUR = "quick_timer_alarm_hour"
        private const val KEY_QUICK_TIMER_ALARM_MINUTE = "quick_timer_alarm_minute"
        private const val KEY_CUSTOM_SENT_MS = "custom_sent_ms"
        private const val KEY_NOTIFICATION_COUNT = "notification_count"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_RINGCONN_STEPS = "ringconn_steps_enabled"
        private const val KEY_RINGCONN_ADDRESS = "ringconn_address"
        private const val KEY_ACTIVITY_ACTIVE = "activity_active"
        private const val KEY_ACTIVITY_UNIT = "activity_unit"
        private const val KEY_SAVED_AS_PRESENT = "activity_saved_as_present"
        private const val KEY_SAVED_AS_ON = "activity_saved_as_on"
        private const val KEY_SAVED_AS_PERIOD = "activity_saved_as_period"

        /** Legacy `active_face` value from when notifications was a mutually-exclusive face. */
        private const val LEGACY_NOTIFICATIONS_COMPLICATION = "NOTIFICATIONS"
        private const val KEY_UPDATE_INTERVAL = "update_interval_min"
        private const val KEY_STEPS_COUNT = "steps_last_count"
        private const val KEY_STEPS_FETCHED_MS = "steps_fetched_ms"
        private const val KEY_PS_ENABLED = "power_saving_enabled"
        private const val KEY_PS_TIMEOUT = "power_saving_timeout_sec"
        private const val KEY_QH_ENABLED = "quiet_hours_enabled"
        private const val KEY_QH_START = "quiet_hours_start_min"
        private const val KEY_QH_END = "quiet_hours_end_min"
        private const val DEFAULT_SCREEN_SLEEP_SEC = 120
        private const val DEFAULT_QH_START_HOUR = 22
        private const val DEFAULT_QH_END_HOUR = 7
        private const val KEY_LAST_SEND_MS = "last_auto_send_ms"
        private const val KEY_LAST_SEND_SUMMARY = "last_auto_send_summary"
        private const val KEY_LAST_NOTIFIED_KIND = "last_notified_kind"

        private const val MINUTES_PER_HOUR = 60
        private const val MAX_HOUR = 23
        private const val MAX_MINUTE = 59
        private const val DEFAULT_ALARM_HOUR = 7
        private const val DEFAULT_QUICK_TIMER_SECONDS = 180
        private const val KEY_BATTERY_READOUT = "battery_readout"
        private const val KEY_BATTERY_VALUE_MV = "battery_value_mv"
        private const val KEY_BATTERY_FETCHED_MS = "battery_fetched_ms"
        const val KEY_PUSH_INTERVAL_MS = "activity_push_interval_ms"
        const val KEY_AUTO_PAUSE_MPS = "activity_auto_pause_mps"
        const val DEFAULT_PUSH_INTERVAL_MS = 30_000L
        const val DEFAULT_AUTO_PAUSE_MPS = 0.1f
        val PUSH_INTERVAL_PRESETS_MS = listOf(3_000L, 15_000L, 30_000L, 60_000L, 300_000L)
        private const val KEY_WT_SLOTS = "world_time_slots"
        private const val KEY_WT_ACTIVE = "world_time_active_zone"
        private const val KEY_WT_CUSTOM_SEC = "world_time_custom_offset_sec"
        private const val KEY_WT_SWAPPED = "world_time_swapped"
        private const val KEY_WT_LAST_PUSHED_SEC = "world_time_last_pushed_offset_sec"
        private const val KEY_WT_LAST_PUSHED_CLOCK_SEC = "world_time_last_pushed_clock_offset_sec"
        private const val KEY_WT_SWAP_LAT_E3 = "world_time_swap_lat_e3"
        private const val KEY_WT_SWAP_LON_E3 = "world_time_swap_lon_e3"
    }
}
