package com.blizzardcaron.freeolleefaces

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.blizzardcaron.freeolleefaces.activity.ActivityMetricsRepository
import com.blizzardcaron.freeolleefaces.activity.AndroidActivitySessionLauncher
import com.blizzardcaron.freeolleefaces.activity.AndroidActivityTrackStore
import com.blizzardcaron.freeolleefaces.activity.AndroidInstrumentsProvider
import com.blizzardcaron.freeolleefaces.auto.AndroidScheduler
import com.blizzardcaron.freeolleefaces.ble.AndroidBleClient
import com.blizzardcaron.freeolleefaces.ble.AndroidWatchConnection
import com.blizzardcaron.freeolleefaces.health.AndroidStepsProvider
import com.blizzardcaron.freeolleefaces.location.AndroidLocationProvider
import com.blizzardcaron.freeolleefaces.notifications.AndroidNotificationAccess
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import com.blizzardcaron.freeolleefaces.prefs.timerSettings
import com.blizzardcaron.freeolleefaces.ring.AndroidRingStepsSource
import com.blizzardcaron.freeolleefaces.ring.RingConnDiscovery
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import com.blizzardcaron.freeolleefaces.ui.BondedDevice
import com.blizzardcaron.freeolleefaces.ui.BondedDevicesDialog
import com.blizzardcaron.freeolleefaces.ui.BottomNavTab
import com.blizzardcaron.freeolleefaces.ui.HomeCallbacks
import com.blizzardcaron.freeolleefaces.ui.HomeScreen
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.QuickTimerState
import com.blizzardcaron.freeolleefaces.ui.Screen
import com.blizzardcaron.freeolleefaces.ui.SettingsCallbacks
import com.blizzardcaron.freeolleefaces.ui.SettingsScreen
import com.blizzardcaron.freeolleefaces.ui.TimerSetEditScreen
import com.blizzardcaron.freeolleefaces.ui.TimerSetsCallbacks
import com.blizzardcaron.freeolleefaces.ui.TimerSetsScreen
import com.blizzardcaron.freeolleefaces.ui.theme.FreeOlleeFacesTheme
import com.blizzardcaron.freeolleefaces.ui.versionLabel
import kotlinx.coroutines.delay

/** Dashboard preview refresh cadence while Home is visible. */
private const val DASHBOARD_POLL_INTERVAL_MS = 60_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Scoped to the activity's ViewModelStore so screen/nav state survives rotation.
        val viewModel = ViewModelProvider(this)[AppViewModelHolder::class.java].vm
        setContent {
            FreeOlleeFacesTheme {
                AppRoot(viewModel)
            }
        }
    }
}

/** Keeps the plain-Kotlin [AppViewModel] alive across configuration changes (reflective factory needs non-private). */
internal class AppViewModelHolder(application: Application) : AndroidViewModel(application) {
    val vm: AppViewModel = createAppViewModel(application)
}

@Composable
private fun AppRoot(viewModel: AppViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val state = viewModel.state
    val screen = viewModel.screen

    var showPicker by remember { mutableStateOf(false) }

    AppEffects(viewModel, context, snackbarHostState, screen)

    val (homeCallbacks, settingsCallbacks) = rememberAppCallbacks(viewModel, context, state) { showPicker = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { AppBottomBar(screen, viewModel) },
    ) { inner ->
        AppContent(
            screen = screen,
            viewModel = viewModel,
            state = state,
            homeCallbacks = homeCallbacks,
            settingsCallbacks = settingsCallbacks,
            modifier = Modifier.padding(inner),
        )
    }

    if (showPicker) {
        val devices = bondedDevices(context)
        BondedDevicesDialog(
            devices = devices,
            onPick = { device ->
                viewModel.onWatchPicked(device.address, "Watch: ${device.name ?: device.address}")
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun AppEffects(
    viewModel: AppViewModel,
    context: Context,
    snackbarHostState: SnackbarHostState,
    screen: Screen,
) {
    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(Unit) {
        runStartupLocation(viewModel, context)
    }

    // Dashboard polling: while Home is visible, refresh all face previews on entry and every 60 s.
    // Keyed on `screen` so it cancels when you navigate away and restarts on return.
    LaunchedEffect(screen) {
        if (screen == Screen.Home) {
            while (true) {
                viewModel.complications.refreshAllPreviews()
                viewModel.worldTime.refreshPreviews()
                delay(DASHBOARD_POLL_INTERVAL_MS)
            }
        }
    }

    // Re-check notification access/count when the activity resumes — e.g. returning from the
    // system notification-access settings page. The 60 s dashboard poll alone would lag here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    viewModel.onForeground()
                    // Adopt any on-watch World Time change made while we were backgrounded (spec:
                    // reconcile at app open; the poll loop must NOT repeat this — a failed push
                    // would be silently reverted by re-adopting the watch's stale zone).
                    viewModel.worldTime.reconcileOnOpen()
                }
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                Lifecycle.Event.ON_RESUME -> viewModel.complications.onResumeNotifications()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private fun createAppViewModel(context: Context): AppViewModel {
    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()
    return AppViewModel(
        prefs = Prefs(appSettings(context)),
        ble = AndroidBleClient(context),
        watchConnection = AndroidWatchConnection(context),
        steps = AndroidStepsProvider(context),
        ringSteps = AndroidRingStepsSource(context) { Prefs(appSettings(context)).ringConnAddress },
        ringDiscovery = RingConnDiscovery(context),
        location = AndroidLocationProvider(context),
        notificationAccess = AndroidNotificationAccess(context),
        timerRepo = TimerSetsRepository(timerSettings(context)),
        metricsRepo = ActivityMetricsRepository(appSettings(context)),
        scheduler = AndroidScheduler(context),
        versionLabel = versionLabel(versionName, context.packageName),
        activityLauncher = AndroidActivitySessionLauncher(context),
        activityStore = AndroidActivityTrackStore(context),
        instrumentsProvider = AndroidInstrumentsProvider(context),
        hasLocationPermission = {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        },
    )
}

@Composable
private fun rememberAppCallbacks(
    viewModel: AppViewModel,
    context: Context,
    state: HomeState,
    onBluetoothGranted: () -> Unit,
): Pair<HomeCallbacks, SettingsCallbacks> {
    val perms = rememberPermissionsCoordinator(
        PermissionsCallbacks(
            backgroundActive = { viewModel.backgroundActive() },
            onBluetoothGranted = onBluetoothGranted,
            onBluetoothDenied = { viewModel.onBluetoothDenied() },
            setLocating = { viewModel.complications.setLocating(it) },
            onLocationFetched = { lat, lng -> viewModel.complications.onLocationFetched(lat, lng) },
            onLocationFailed = { msg -> viewModel.complications.onLocationFailed(msg) },
            onLocationDenied = { viewModel.complications.onLocationDenied() },
            fetchLocation = { viewModel.complications.fetchLocation() },
            refreshSteps = { push -> viewModel.complications.refreshSteps(push) },
            activeIsSteps = { viewModel.complications.activeIsSteps() },
            onPartialHealthGrant = { viewModel.complications.onPartialHealthGrant() },
            healthAvailability = { viewModel.complications.healthAvailability() },
            onHealthUpdateRequired = { viewModel.complications.onHealthUpdateRequired() },
            onHealthUnavailable = { viewModel.complications.onHealthUnavailable() },
        )
    )
    val homeCallbacks = HomeCallbacks(
        onActivate = { viewModel.complications.activate(it) },
        onUpdateNow = { viewModel.complications.refreshActive(force = true, push = true) },
        onTempUnitChange = { newUnit -> viewModel.complications.setTempUnit(newUnit) },
        onSetBatteryReadout = { viewModel.complications.setBatteryReadout(it) },
        onCustomChange = { text -> viewModel.complications.setCustomText(text) },
        onSendCustom = { viewModel.complications.sendCustom(state.custom) },
        onGrantHealth = { perms.requestHealth() },
        onToggleRingConnSteps = viewModel::toggleRingConnSteps,
        onGrantNotificationAccess = { openNotificationAccessSettings(context) },
        onToggleNotifications = { viewModel.complications.setNotificationsEnabled(it) },
        onNotificationsUpdateNow = { viewModel.complications.pushCountIfWatch() },
        onReconnect = { viewModel.onReconnect() },
        onWorldTimeActivate = viewModel.worldTime::activate,
        onWorldTimeAddSlot = viewModel.worldTime::addSlot,
        onWorldTimeRemoveSlot = viewModel.worldTime::removeSlot,
        onWorldTimeRefresh = viewModel.worldTime::refreshPreviews,
    )
    val settingsCallbacks = SettingsCallbacks(
        onBack = { viewModel.navigateTo(Screen.Home) },
        onSelectWatch = perms::selectWatch,
        onDisconnect = { viewModel.onDisconnect() },
        onUnsetWatch = { viewModel.onUnsetWatch() },
        onIntervalChange = { mins -> viewModel.settings.setInterval(mins) },
        onPowerSavingEnabledChange = { enabled -> viewModel.settings.setPowerSavingEnabled(enabled) },
        onScreenSleepTimeoutChange = { sec -> viewModel.settings.setScreenSleepTimeout(sec) },
        onQuietHoursEnabledChange = { on -> viewModel.settings.setQuietHoursEnabled(on) },
        onQuietHoursStartChange = { min -> viewModel.settings.setQuietHoursStart(min) },
        onQuietHoursEndChange = { min -> viewModel.settings.setQuietHoursEnd(min) },
        onLatChange = { viewModel.settings.onCoordEdit(it, state.lng) },
        onLngChange = { viewModel.settings.onCoordEdit(state.lat, it) },
        onUseMyLocation = perms::useMyLocation,
    )
    return homeCallbacks to settingsCallbacks
}

private suspend fun runStartupLocation(viewModel: AppViewModel, context: Context) {
    val hasAnyLocation =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    val haveCoords = viewModel.complications.hasSavedCoords()
    val stale = viewModel.complications.locationIsStale()

    when {
        // Fresh saved coords: use them silently, no fix.
        haveCoords && !stale -> { /* render with saved coords */ }

        // Stale saved coords + permission: render saved, silently refresh.
        haveCoords && hasAnyLocation -> {
            viewModel.complications.setLocating(true)
            viewModel.complications.fetchLocation()
                .onSuccess { coords ->
                    viewModel.complications.onLocationFetched(coords.lat, coords.lng, refresh = false)
                }
                .onFailure { viewModel.complications.onLocationRefreshFailed() }
        }

        // No saved coords, permission held: first-run fix.
        !haveCoords && hasAnyLocation -> {
            viewModel.complications.setLocating(true)
            viewModel.complications.fetchLocation()
                .onSuccess { coords ->
                    viewModel.complications.onLocationFetched(coords.lat, coords.lng, refresh = false)
                }
                .onFailure { viewModel.complications.setLocating(false) }
        }

        // No permission and no saved coords: the user sets location in Settings.
        else -> { /* nothing to fetch */ }
    }

    viewModel.complications.refreshActive(force = false, push = false)
    viewModel.onStart()

    val prefs = Prefs(appSettings(context))
    val ble = AndroidBleClient(context)
    com.blizzardcaron.freeolleefaces.activity.ActivityRecovery.recoverIfStranded(
        prefs = prefs,
        store = AndroidActivityTrackStore(context),
        autoSleep = com.blizzardcaron.freeolleefaces.activity.ActivityAutoSleepManager(ble, prefs),
        watchAddress = prefs.watchAddress,
        sessionRunning = com.blizzardcaron.freeolleefaces.activity.ActivitySessionHost.isRunning,
    )
}

@Composable
private fun AppBottomBar(screen: Screen, viewModel: AppViewModel) {
    if (BottomNavTab.showsBottomBar(screen)) {
        NavigationBar {
            BottomNavTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = BottomNavTab.forScreen(screen) == tab,
                    onClick = {
                        when (tab) {
                            BottomNavTab.Timer -> viewModel.timers.refreshTimers()
                            else -> {}
                        }
                        viewModel.navigateTo(tab.screen)
                    },
                    icon = { Text(tab.glyph, style = MaterialTheme.typography.titleLarge) },
                    label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
private fun AppTimerSetsScreen(viewModel: AppViewModel, state: HomeState, modifier: Modifier) {
    TimerSetsScreen(
        sets = viewModel.timers.timerSets,
        activeId = viewModel.timers.timerActiveId,
        sending = state.sending,
        quickTimer = QuickTimerState(
            seconds = viewModel.timers.quickTimerSeconds,
            startFromApp = viewModel.timers.quickTimerStartFromApp,
            intervalMode = viewModel.timers.quickTimerIntervalMode,
            alarmMode = viewModel.timers.quickTimerAlarmMode,
            alarmHour = viewModel.timers.quickTimerAlarmHour,
            alarmMinute = viewModel.timers.quickTimerAlarmMinute,
        ),
        callbacks = TimerSetsCallbacks(
            onSaveQuick = { viewModel.timers.saveQuickTimer(it) },
            onToggleStartFromApp = { viewModel.timers.toggleQuickTimerStartFromApp(it) },
            onToggleIntervalMode = { viewModel.timers.toggleQuickTimerIntervalMode(it) },
            onToggleAlarmMode = { viewModel.timers.toggleQuickTimerAlarmMode(it) },
            onSaveAlarmTime = { h, m -> viewModel.timers.saveQuickTimerAlarmTime(h, m) },
            onSendAlarm = { viewModel.timers.sendQuickAlarm() },
            onSendQuick = { viewModel.timers.sendQuickTimer() },
            onOpen = {
                viewModel.timers.editTimerSet(it)
                viewModel.navigateTo(Screen.TimerSetEdit)
            },
            onNew = {
                viewModel.timers.newTimerSet()
                viewModel.navigateTo(Screen.TimerSetEdit)
            },
            onDuplicate = { src -> viewModel.timers.duplicateTimerSet(src) },
            onDelete = { viewModel.timers.deleteTimerSet(it) },
            onSend = { viewModel.timers.sendTimerSet(it) },
            onStart = { viewModel.timers.startTimerSet(it) },
            onMoveUp = { viewModel.timers.moveTimerSetUp(it) },
            onMoveDown = { viewModel.timers.moveTimerSetDown(it) },
            onBack = { viewModel.navigateTo(Screen.Home) },
            onReconnect = { viewModel.onReconnect() },
        ),
        connectionStatus = state.connectionStatus,
        modifier = modifier,
    )
}

@Composable
private fun AppContent(
    screen: Screen,
    viewModel: AppViewModel,
    state: HomeState,
    homeCallbacks: HomeCallbacks,
    settingsCallbacks: SettingsCallbacks,
    modifier: Modifier,
) {
    when (screen) {
        Screen.Home -> HomeScreen(state = state, callbacks = homeCallbacks, modifier = modifier)
        Screen.Activity -> ActivityTab(viewModel, modifier)
        Screen.ActivityHistory -> ActivityHistoryTab(viewModel, modifier)
        Screen.ActivityDetail -> ActivityDetailTab(viewModel, modifier)
        Screen.ActivityMetricsConfig -> ActivityMetricsConfigTab(viewModel, modifier)
        Screen.Settings -> SettingsScreen(
            state = state,
            callbacks = settingsCallbacks,
            onReconnect = { viewModel.onReconnect() },
            modifier = modifier,
        )
        Screen.TimerSets -> AppTimerSetsScreen(viewModel = viewModel, state = state, modifier = modifier)
        Screen.TimerSetEdit -> {
            val editing = viewModel.timers.editingSet
            if (editing == null) {
                viewModel.navigateTo(Screen.TimerSets)
            } else {
                TimerSetEditScreen(
                    set = editing,
                    onSave = { s ->
                        viewModel.timers.saveTimerSet(s)
                        viewModel.navigateTo(Screen.TimerSets)
                    },
                    onSend = { s ->
                        viewModel.timers.saveTimerSet(s)
                        viewModel.timers.sendTimerSet(s)
                        viewModel.navigateTo(Screen.TimerSets)
                    },
                    onBack = { viewModel.navigateTo(Screen.TimerSets) },
                    connectionStatus = state.connectionStatus,
                    onReconnect = { viewModel.onReconnect() },
                    modifier = modifier,
                )
            }
        }
    }
}

private fun openNotificationAccessSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@SuppressLint("MissingPermission")
private fun bondedDevices(context: Context): List<BondedDevice> {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    return if (adapter?.isEnabled == true) {
        adapter.bondedDevices?.map { BondedDevice(it.name, it.address) }.orEmpty()
    } else {
        emptyList()
    }
}
