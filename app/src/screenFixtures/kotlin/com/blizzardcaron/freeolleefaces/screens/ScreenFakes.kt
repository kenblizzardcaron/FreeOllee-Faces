package com.blizzardcaron.freeolleefaces.screens

import com.blizzardcaron.freeolleefaces.activity.ActivityMetricsConfig
import com.blizzardcaron.freeolleefaces.activity.ActivityState
import com.blizzardcaron.freeolleefaces.activity.ActivitySummary
import com.blizzardcaron.freeolleefaces.activity.ActivityTrack
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.activity.TrackPoint
import com.blizzardcaron.freeolleefaces.alarm.Alarm
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.format.BatteryReadout
import com.blizzardcaron.freeolleefaces.timer.TimerSet
import com.blizzardcaron.freeolleefaces.timer.TimerSlot
import com.blizzardcaron.freeolleefaces.ui.ActivityCallbacks
import com.blizzardcaron.freeolleefaces.ui.ActivityHistoryCallbacks
import com.blizzardcaron.freeolleefaces.ui.ActivityMetricsConfigCallbacks
import com.blizzardcaron.freeolleefaces.ui.AlarmsCallbacks
import com.blizzardcaron.freeolleefaces.ui.HomeCallbacks
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.PreviewState
import com.blizzardcaron.freeolleefaces.ui.QuickTimerState
import com.blizzardcaron.freeolleefaces.ui.SettingsCallbacks
import com.blizzardcaron.freeolleefaces.ui.TimerSetsCallbacks

/** Representative fake state + no-op callbacks for rendering every screen in isolation. */
object ScreenFakes {
    val unit = ActivityUnit.METRIC

    // Every face populated with a representative reading so the README hero shot shows real
    // values (no "Off" / "Waiting for coordinates" placeholders). Payloads are the exact 6-cell
    // nameplate strings DisplayFormatter would emit; human strings match the controller wording.
    val homeState = HomeState(
        watchLabel = "Watch: Ollee",
        watchSelected = true,
        connectionStatus = ConnectionStatus.Connected,
        versionLabel = "v0.31.0",

        locationLabel = "Location: 40.015, -105.270",
        locationFreshness = "just now",
        lat = "40.015",
        lng = "-105.270",

        tempPreview = PreviewState.Ready("  72#F", "Currently: 72.0°F"),
        tempUpdated = "Updated 2:45 PM",
        tempNext = "Next update 3:00 PM",

        batteryReadout = BatteryReadout.PERCENT,
        batteryPreview = PreviewState.Ready("   85P", "Battery: 85%"),
        batteryUpdated = "Updated 2:45 PM",
        batteryNext = "Next update 3:00 PM",

        pressurePreview = PreviewState.Ready("  1013", "Currently: 1013.0 hPa"),
        pressureUpdated = "Updated 2:45 PM",
        pressureNext = "Next update 3:00 PM",

        altitudePreview = PreviewState.Ready(" 1609m", "Elevation: 1609 m"),
        altitudeUpdated = "Updated 2:45 PM",
        altitudeNext = "Next update 3:00 PM",

        stepsPreview = PreviewState.Ready("  8432", "Today: 8,432 steps"),
        stepsUpdated = "Updated 2:45 PM",
        stepsHealthGranted = true,

        notificationCount = 3,
        notificationAccessGranted = true,
        notificationsEnabled = true,
    )
    val homeCallbacks = HomeCallbacks(
        onActivate = {},
        onUpdateNow = {},
        onTempUnitChange = {},
        onSetBatteryReadout = {},
        onCustomChange = {},
        onSendCustom = {},
        onGrantHealth = {},
        onGrantNotificationAccess = {},
        onToggleNotifications = {},
        onNotificationsUpdateNow = {},
    )
    val settingsCallbacks = SettingsCallbacks(
        onBack = {},
        onSelectWatch = {},
        onDisconnect = {},
        onUnsetWatch = {},
        onIntervalChange = {},
        onPowerSavingEnabledChange = {},
        onScreenSleepTimeoutChange = {},
        onQuietHoursEnabledChange = {},
        onQuietHoursStartChange = {},
        onQuietHoursEndChange = {},
        onLatChange = {},
        onLngChange = {},
        onUseMyLocation = {},
    )

    val timerSets = listOf(
        TimerSet("a", "Tea", List(TimerSet.SLOT_COUNT) { TimerSlot() }),
        TimerSet("b", "Workout", List(TimerSet.SLOT_COUNT) { TimerSlot() }),
    )
    val timerSet = timerSets.first()
    val quickTimer = QuickTimerState(
        seconds = 60,
        startFromApp = true,
        intervalMode = false,
        alarmMode = false,
        alarmHour = 7,
        alarmMinute = 30,
    )
    val timerSetsCallbacks = TimerSetsCallbacks(
        onSaveQuick = {},
        onToggleStartFromApp = {},
        onToggleIntervalMode = {},
        onToggleAlarmMode = {},
        onSaveAlarmTime = { _, _ -> },
        onSendAlarm = {},
        onSendQuick = {},
        onOpen = {},
        onNew = {},
        onDuplicate = {},
        onDelete = {},
        onSend = {},
        onStart = {},
        onMoveUp = {},
        onMoveDown = {},
        onBack = {},
        onReconnect = {},
    )

    val alarms = listOf(
        Alarm(id = "1", hour = 7, minute = 0),
        Alarm(id = "2", hour = 22, minute = 30, enabled = false),
    )
    val alarmsCallbacks = AlarmsCallbacks(
        onAdd = {},
        onSave = {},
        onToggle = { _, _ -> },
        onDelete = {},
        onBack = {},
        onReconnect = {},
    )

    val activityState = ActivityState(
        running = true,
        recording = true,
        distanceMeters = 1234.0,
        recentPaceSecPerKm = 300.0,
        elapsedMs = 600_000L,
        headingDeg = 90f,
        altitudeM = 100.0,
        pressureHpa = 1013.0,
        hasFix = true,
    )
    val activitySummary = ActivitySummary(
        distanceM = 5000.0,
        movingTimeMs = 1_500_000L,
        elapsedTimeMs = 1_600_000L,
        avgPaceSecPerKm = 300.0,
    )
    val activityCallbacks = ActivityCallbacks(
        onStart = {},
        onShowLive = {},
        onStop = {},
        onMode = {},
        onToggleUnit = {},
        onOpenHistory = {},
        onConfigureMetrics = {},
        onSelectInterval = {},
        onPause = {},
        onResume = {},
        onOpenActivity = {},
    )
    val activityTrack = ActivityTrack(
        id = "t1",
        startedAtMs = 1_000L,
        endedAtMs = 1_600_000L,
        unit = ActivityUnit.METRIC,
        points = listOf(
            TrackPoint(tMs = 0L, lat = 40.000, lng = -105.000),
            TrackPoint(tMs = 1_000L, lat = 40.001, lng = -105.001),
        ),
        summary = activitySummary,
    )
    val activityHistoryCallbacks = ActivityHistoryCallbacks(
        onOpen = {},
        onDelete = {},
        onBack = {},
    )
    val metricsConfig = ActivityMetricsConfig.DEFAULT
    val activityMetricsConfigCallbacks = ActivityMetricsConfigCallbacks(
        onMoveUp = { _, _ -> },
        onMoveDown = { _, _ -> },
        onToggle = { _, _, _ -> },
        onBack = {},
    )
}
