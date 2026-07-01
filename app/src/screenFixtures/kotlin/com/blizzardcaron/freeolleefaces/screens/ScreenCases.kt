package com.blizzardcaron.freeolleefaces.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.ui.ActivityDetailScreen
import com.blizzardcaron.freeolleefaces.ui.ActivityHistoryScreen
import com.blizzardcaron.freeolleefaces.ui.ActivityMetricsConfigScreen
import com.blizzardcaron.freeolleefaces.ui.ActivityScreen
import com.blizzardcaron.freeolleefaces.ui.AlarmsScreen
import com.blizzardcaron.freeolleefaces.ui.HomeScreen
import com.blizzardcaron.freeolleefaces.ui.Screen
import com.blizzardcaron.freeolleefaces.ui.SettingsScreen
import com.blizzardcaron.freeolleefaces.ui.TimerSetEditScreen
import com.blizzardcaron.freeolleefaces.ui.TimerSetsScreen
import com.blizzardcaron.freeolleefaces.ui.theme.FreeOlleeFacesTheme

/** Stable filename slug per screen (used for screenshot names). */
fun Screen.slug(): String = when (this) {
    Screen.Home -> "home"
    Screen.Settings -> "settings"
    Screen.TimerSets -> "timers"
    Screen.TimerSetEdit -> "timer-edit"
    Screen.Alarms -> "alarms"
    Screen.Activity -> "activity"
    Screen.ActivityHistory -> "activity-history"
    Screen.ActivityDetail -> "activity-detail"
    Screen.ActivityMetricsConfig -> "activity-metrics-config"
}

/**
 * Renders [screen] with representative fakes, wrapped in the real [FreeOlleeFacesTheme] and a
 * [Surface] painting `colorScheme.background` (as the app's Scaffold does). This gives
 * accessibility contrast checks the real dark background — not the bare window's white — and
 * makes screenshots match the shipped look.
 */
fun renderFor(screen: Screen): @Composable () -> Unit = {
    FreeOlleeFacesTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            screenContent(screen)()
        }
    }
}

/**
 * Maps [screen] to its composable content with representative fakes. The `when` is exhaustive
 * with no `else`: adding a new Screen subtype makes this file fail to compile until a case is
 * added, which is the build-breaking coverage gate.
 */
private fun screenContent(screen: Screen): @Composable () -> Unit = when (screen) {
    Screen.Home -> {
        { HomeScreen(state = ScreenFakes.homeState, callbacks = ScreenFakes.homeCallbacks) }
    }
    Screen.Settings -> {
        { SettingsScreen(state = ScreenFakes.homeState, callbacks = ScreenFakes.settingsCallbacks, onReconnect = {}) }
    }
    Screen.TimerSets -> {
        {
            TimerSetsScreen(
                sets = ScreenFakes.timerSets,
                activeId = "a",
                sending = false,
                quickTimer = ScreenFakes.quickTimer,
                callbacks = ScreenFakes.timerSetsCallbacks,
                connectionStatus = ConnectionStatus.Connected,
            )
        }
    }
    Screen.TimerSetEdit -> {
        {
            TimerSetEditScreen(
                set = ScreenFakes.timerSet,
                onSave = {},
                onSend = {},
                onBack = {},
                connectionStatus = ConnectionStatus.Connected,
                onReconnect = {},
            )
        }
    }
    Screen.Alarms -> {
        {
            AlarmsScreen(
                alarms = ScreenFakes.alarms,
                nextSummary = "Next: 7:00 AM",
                callbacks = ScreenFakes.alarmsCallbacks,
                connectionStatus = ConnectionStatus.Connected,
            )
        }
    }
    Screen.Activity -> {
        {
            ActivityScreen(
                state = ScreenFakes.activityState,
                unit = ScreenFakes.unit,
                watchSelected = true,
                recent = listOf(ScreenFakes.activityTrack),
                config = ScreenFakes.metricsConfig,
                callbacks = ScreenFakes.activityCallbacks,
                pushIntervalMs = 30_000L,
                intervalPresetsMs = com.blizzardcaron.freeolleefaces.prefs.Prefs.PUSH_INTERVAL_PRESETS_MS,
            )
        }
    }
    Screen.ActivityHistory -> {
        {
            ActivityHistoryScreen(
                tracks = listOf(ScreenFakes.activityTrack),
                unit = ScreenFakes.unit,
                callbacks = ScreenFakes.activityHistoryCallbacks,
            )
        }
    }
    Screen.ActivityDetail -> {
        { ActivityDetailScreen(track = ScreenFakes.activityTrack, unit = ScreenFakes.unit, onBack = {}) }
    }
    Screen.ActivityMetricsConfig -> {
        {
            ActivityMetricsConfigScreen(
                config = ScreenFakes.metricsConfig,
                unit = ScreenFakes.unit,
                callbacks = ScreenFakes.activityMetricsConfigCallbacks,
            )
        }
    }
}

/** Canonical iteration list. Kept in sync with the sealed hierarchy by ScreenCoverageTest. */
val allScreens: List<Screen> = listOf(
    Screen.Home,
    Screen.Settings,
    Screen.TimerSets,
    Screen.TimerSetEdit,
    Screen.Alarms,
    Screen.Activity,
    Screen.ActivityHistory,
    Screen.ActivityDetail,
    Screen.ActivityMetricsConfig,
)
