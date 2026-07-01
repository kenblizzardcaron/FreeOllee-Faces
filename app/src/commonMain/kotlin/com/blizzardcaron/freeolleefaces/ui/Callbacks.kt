package com.blizzardcaron.freeolleefaces.ui

import com.blizzardcaron.freeolleefaces.activity.ActivityMetric
import com.blizzardcaron.freeolleefaces.activity.ActivityMode
import com.blizzardcaron.freeolleefaces.alarm.Alarm
import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.format.BatteryReadout
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.timer.TimerSet

data class HomeCallbacks(
    val onActivate: (ActiveComplication) -> Unit,
    val onUpdateNow: () -> Unit,
    val onTempUnitChange: (TempUnit) -> Unit,
    val onSetBatteryReadout: (BatteryReadout) -> Unit = {},
    val onCustomChange: (String) -> Unit,
    val onSendCustom: () -> Unit,
    val onGrantHealth: () -> Unit,
    val onGrantNotificationAccess: () -> Unit,
    val onToggleNotifications: (Boolean) -> Unit,
    val onNotificationsUpdateNow: () -> Unit,
    val onReconnect: () -> Unit = {},
)

data class SettingsCallbacks(
    val onBack: () -> Unit,
    val onSelectWatch: () -> Unit,
    val onDisconnect: () -> Unit,
    val onUnsetWatch: () -> Unit,
    val onIntervalChange: (Int) -> Unit,
    val onPowerSavingEnabledChange: (Boolean) -> Unit,
    val onScreenSleepTimeoutChange: (Int) -> Unit,
    val onQuietHoursEnabledChange: (Boolean) -> Unit,
    val onQuietHoursStartChange: (Int) -> Unit,
    val onQuietHoursEndChange: (Int) -> Unit,
    val onLatChange: (String) -> Unit,
    val onLngChange: (String) -> Unit,
    val onUseMyLocation: () -> Unit,
)

/** UI state for the Timer screen's quick-timer card. */
data class QuickTimerState(
    val seconds: Int,
    val startFromApp: Boolean,
    val intervalMode: Boolean,
    val alarmMode: Boolean,
    val alarmHour: Int,
    val alarmMinute: Int,
)

/** All Timer-screen callbacks, bundled to keep the composable signature small. */
data class TimerSetsCallbacks(
    val onSaveQuick: (Int) -> Unit,
    val onToggleStartFromApp: (Boolean) -> Unit,
    val onToggleIntervalMode: (Boolean) -> Unit,
    val onToggleAlarmMode: (Boolean) -> Unit,
    val onSaveAlarmTime: (Int, Int) -> Unit,
    val onSendAlarm: () -> Unit,
    val onSendQuick: () -> Unit,
    val onOpen: (TimerSet) -> Unit,
    val onNew: () -> Unit,
    val onDuplicate: (TimerSet) -> Unit,
    val onDelete: (TimerSet) -> Unit,
    val onSend: (TimerSet) -> Unit,
    val onStart: (TimerSet) -> Unit,
    val onMoveUp: (TimerSet) -> Unit,
    val onMoveDown: (TimerSet) -> Unit,
    val onBack: () -> Unit,
    val onReconnect: () -> Unit,
)

/** Per-row callbacks for a timer set in the list. */
data class TimerSetRowCallbacks(
    val onOpen: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
    val onSend: () -> Unit,
    val onStart: () -> Unit,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
)

/** Alarm-screen callbacks, bundled to keep the composable signature small. */
data class AlarmsCallbacks(
    val onAdd: () -> Unit,
    val onSave: (Alarm) -> Unit,
    val onToggle: (String, Boolean) -> Unit,
    val onDelete: (String) -> Unit,
    val onBack: () -> Unit,
    val onReconnect: () -> Unit,
)

/** Per-slot callbacks for the timer-set editor. */
data class SlotEditorCallbacks(
    val onLabelChange: (String) -> Unit,
    val onDurationChange: (Int) -> Unit,
    val onFillDown: () -> Unit,
    val onDuplicate: () -> Unit,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
)

/** Activity-screen callbacks, bundled to keep the composable signature small. */
data class ActivityCallbacks(
    val onStart: () -> Unit,
    val onShowLive: () -> Unit,
    val onStop: () -> Unit,
    val onMode: () -> Unit,
    val onToggleUnit: () -> Unit,
    val onOpenHistory: () -> Unit,
    val onConfigureMetrics: () -> Unit,
    val onSelectInterval: (Long) -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onOpenActivity: (String) -> Unit,
)

/** Activity-history list callbacks. */
data class ActivityHistoryCallbacks(
    val onOpen: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onBack: () -> Unit,
)

/** Activity-metrics-config screen callbacks (reorder + enable/disable, per mode). */
data class ActivityMetricsConfigCallbacks(
    val onMoveUp: (ActivityMode, Int) -> Unit,
    val onMoveDown: (ActivityMode, Int) -> Unit,
    val onToggle: (ActivityMode, ActivityMetric, Boolean) -> Unit,
    val onBack: () -> Unit,
)
