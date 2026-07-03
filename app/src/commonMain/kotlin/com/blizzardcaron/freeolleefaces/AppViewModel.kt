package com.blizzardcaron.freeolleefaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blizzardcaron.freeolleefaces.activity.ActivityMetricsRepository
import com.blizzardcaron.freeolleefaces.activity.ActivityRetention
import com.blizzardcaron.freeolleefaces.activity.ActivitySessionLauncher
import com.blizzardcaron.freeolleefaces.activity.ActivityTrack
import com.blizzardcaron.freeolleefaces.activity.ActivityTrackStore
import com.blizzardcaron.freeolleefaces.activity.IdleInstruments
import com.blizzardcaron.freeolleefaces.activity.InstrumentsProvider
import com.blizzardcaron.freeolleefaces.activity.NoopActivitySessionLauncher
import com.blizzardcaron.freeolleefaces.activity.NoopActivityTrackStore
import com.blizzardcaron.freeolleefaces.activity.NoopInstrumentsProvider
import com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository
import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.auto.AlarmScheduler
import com.blizzardcaron.freeolleefaces.auto.AutoSleepReconciler
import com.blizzardcaron.freeolleefaces.auto.Scheduler
import com.blizzardcaron.freeolleefaces.ble.AutoSleepApply
import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.ble.NoopWatchConnection
import com.blizzardcaron.freeolleefaces.ble.WatchConnection
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.health.StepsProvider
import com.blizzardcaron.freeolleefaces.location.LocationProvider
import com.blizzardcaron.freeolleefaces.location.freshnessLabel
import com.blizzardcaron.freeolleefaces.notifications.NotificationAccessChecker
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.ring.NoopRingDiscovery
import com.blizzardcaron.freeolleefaces.ring.NoopRingStepsSource
import com.blizzardcaron.freeolleefaces.ring.RingDevice
import com.blizzardcaron.freeolleefaces.ring.RingDiscovery
import com.blizzardcaron.freeolleefaces.ring.RingStepsSource
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.PreviewState
import com.blizzardcaron.freeolleefaces.ui.Screen
import com.blizzardcaron.freeolleefaces.vm.ActivityController
import com.blizzardcaron.freeolleefaces.vm.AlarmController
import com.blizzardcaron.freeolleefaces.vm.ComplicationController
import com.blizzardcaron.freeolleefaces.vm.SettingsController
import com.blizzardcaron.freeolleefaces.vm.TimerController
import com.blizzardcaron.freeolleefaces.vm.clockTime
import com.blizzardcaron.freeolleefaces.vm.locLabel
import com.blizzardcaron.freeolleefaces.vm.stepsHuman
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

private const val MINUTES_PER_HOUR = 60

// 18 injected dependencies, defaulted for tests where a noop exists — standard constructor DI; bundling them
// into a holder type would only obscure the wiring and worsen test ergonomics
@Suppress("LongParameterList")
class AppViewModel(
    private val prefs: Prefs,
    private val ble: BleClient,
    private val steps: StepsProvider,
    private val location: LocationProvider,
    private val notificationAccess: NotificationAccessChecker,
    private val timerRepo: TimerSetsRepository,
    private val metricsRepo: ActivityMetricsRepository,
    private val scheduler: Scheduler,
    private val alarmRepo: AlarmsRepository,
    private val alarmScheduler: AlarmScheduler,
    private val versionLabel: String = "",
    private val watchConnection: WatchConnection = NoopWatchConnection,
    private val ringSteps: RingStepsSource = NoopRingStepsSource,
    private val ringDiscovery: RingDiscovery = NoopRingDiscovery,
    private val clock: Clock = Clock.System,
    private val activityLauncher: ActivitySessionLauncher = NoopActivitySessionLauncher,
    private val instrumentsProvider: InstrumentsProvider = NoopInstrumentsProvider,
    private val activityStore: ActivityTrackStore = NoopActivityTrackStore,
    private val hasLocationPermission: () -> Boolean = { true },
) : ViewModel() {

    var state by mutableStateOf(initialState())
        private set
    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var selectedActivityId by mutableStateOf<String?>(null)
        private set

    /** Bumped on delete so the history list recomposes off the file-backed store. */
    var historyRevision by mutableStateOf(0)
        private set

    val alarms = AlarmController(
        alarmRepo = alarmRepo,
        alarmScheduler = alarmScheduler,
        clock = clock,
    )

    val timers = TimerController(
        timerRepo = timerRepo,
        ble = ble,
        prefs = prefs,
        scope = viewModelScope,
        showSnackbar = ::emitEvent,
        state = { state },
        update = { t -> state = t(state) },
        clock = clock,
    )

    val complications = ComplicationController(
        prefs = prefs,
        ble = ble,
        steps = steps,
        location = location,
        notificationAccess = notificationAccess,
        scheduler = scheduler,
        scope = viewModelScope,
        showSnackbar = ::emitEvent,
        state = { state },
        update = { t -> state = t(state) },
        ringSteps = ringSteps,
        clock = clock,
    )

    val settings = SettingsController(
        prefs = prefs,
        scheduler = scheduler,
        scope = viewModelScope,
        update = { t -> state = t(state) },
        tempNextText = { complications.tempNextText() },
        refreshActive = { force, push -> complications.refreshActive(force, push) },
        clock = clock,
    )

    val activity = ActivityController(
        launcher = activityLauncher,
        prefs = prefs,
        hasLocationPermission = hasLocationPermission,
        showSnackbar = ::emitEvent,
        metricsRepo = metricsRepo,
    )

    private val _events = Channel<String>(Channel.BUFFERED) // snackbar messages
    val events = _events.receiveAsFlow()

    private var statusJob: Job? = null

    private fun update(transform: (HomeState) -> HomeState) { state = transform(state) }
    private fun showSnackbar(message: String) = emitEvent(message)

    /** Launches a snackbar send on [viewModelScope]; shared with controllers via injection. */
    internal fun emitEvent(message: String) { viewModelScope.launch { _events.send(message) } }

    private fun initialState(): HomeState = HomeState(
        activeComplication = prefs.activeComplication,
        lat = prefs.lastLat?.toString() ?: "",
        lng = prefs.lastLng?.toString() ?: "",
        watchLabel = prefs.watchAddress?.let { "Watch: $it" } ?: "Watch: none selected",
        watchSelected = prefs.watchAddress != null,
        connectionStatus = if (prefs.watchAddress != null) {
            ConnectionStatus.Connecting
        } else {
            ConnectionStatus.NoWatch
        },
        tempUnit = prefs.tempUnit,
        updateIntervalMinutes = prefs.updateIntervalMinutes,
        powerSavingEnabled = prefs.powerSavingEnabled,
        screenSleepTimeoutSec = prefs.screenSleepTimeoutSec,
        quietHoursEnabled = prefs.quietHoursEnabled,
        quietHoursStartMin = prefs.quietHoursStartMin,
        quietHoursEndMin = prefs.quietHoursEndMin,
        custom = prefs.customText,
        customSent = prefs.customSentMs?.let { "Sent '${prefs.customText}' at ${clockTime(it)}" },
        stepsPreview = prefs.lastStepCount?.let {
            PreviewState.Ready(DisplayFormatter.steps(it), stepsHuman(it))
        } ?: PreviewState.Loading,
        stepsUpdated = prefs.stepsFetchedMs?.let { "Updated ${clockTime(it)}" },
        batteryReadout = prefs.batteryReadout,
        batteryPreview = prefs.batteryValueMv?.let {
            PreviewState.Ready(
                DisplayFormatter.battery(it, prefs.batteryReadout),
                DisplayFormatter.batteryHuman(it, prefs.batteryReadout),
            )
        } ?: PreviewState.Loading,
        batteryUpdated = prefs.batteryFetchedMs?.let { "Updated ${clockTime(it)}" },
        ringConnStepsEnabled = prefs.ringConnStepsEnabled,
        ringConnName = ringNameFor(prefs.ringConnAddress, ringDiscovery.bondedRings()),
        locationLabel = locLabel(prefs.lastLat, prefs.lastLng),
        locationFreshness = freshnessLabel(prefs.lastLocationFetchedMs, nowMs()),
        notificationCount = prefs.notificationCount,
        notificationAccessGranted = notificationAccess.isGranted(),
        notificationsEnabled = prefs.notificationsEnabled,
        versionLabel = versionLabel,
    )

    fun navigateTo(s: Screen) { screen = s }

    /** All recorded activity tracks, newest first (for the history list). */
    fun activityHistory(): List<ActivityTrack> = activityStore.list()

    /** Sensors-only idle instruments (compass + barometer) for the Activity home. */
    val instruments: StateFlow<IdleInstruments> get() = instrumentsProvider.instruments
    fun startInstruments() = instrumentsProvider.start()
    fun stopInstruments() = instrumentsProvider.stop()

    /** Open a recorded activity's detail screen by [id]. */
    fun openActivity(id: String) {
        selectedActivityId = id
        navigateTo(Screen.ActivityDetail)
    }

    /** Hard-delete a recorded activity by [id]. */
    fun deleteActivity(id: String) {
        activityStore.delete(id)
        historyRevision++
    }

    fun onWatchPicked(address: String, label: String) {
        prefs.watchAddress = address
        // Show Connecting immediately: WatchLink may already sit at Connecting, so the connect below
        // can be a no-op StateFlow transition the mirror collector never re-emits — set it here.
        update { it.copy(watchLabel = label, watchSelected = true, connectionStatus = ConnectionStatus.Connecting) }
        viewModelScope.launch { watchConnection.connect(address) }
        when (state.activeComplication) {
            ActiveComplication.CUSTOM ->
                prefs.customText.takeIf { it.isNotEmpty() }?.let { complications.sendCustom(it) }
            else -> complications.refreshActive(force = false, push = true)
        }
    }

    fun onBluetoothDenied() { showSnackbar("Bluetooth permission denied — can't list paired watches.") }

    /** Whether a background chain is active at startup — drives the POST_NOTIFICATIONS prompt. */
    fun backgroundActive(): Boolean =
        prefs.activeComplication != ActiveComplication.CUSTOM && prefs.watchAddress != null

    /** Initial re-arm of the auto-update chain; called once from a LaunchedEffect on start. */
    fun onStart() {
        scheduler.reschedule()
        alarmScheduler.rearm()
        pruneOldActivities()
    }

    /** Hard-delete recorded tracks older than the [ActivityRetention] window. */
    private fun pruneOldActivities() {
        activityStore.prune(ActivityRetention.cutoffMs(clock.now().toEpochMilliseconds()))
    }

    /**
     * UI entered the foreground: start mirroring link status into state and connect if a watch is
     * selected. The collector is scoped to the foreground window (cancelled in [onBackground]); while
     * no watch is selected the chip reads NoWatch regardless of the link's own status.
     */
    fun onForeground() {
        if (statusJob != null) return // already foregrounded — don't start a second collector or connect
        statusJob = viewModelScope.launch {
            watchConnection.status.collect { s ->
                val effective = if (prefs.watchAddress == null) ConnectionStatus.NoWatch else s
                update { it.copy(connectionStatus = effective) }
                if (s == ConnectionStatus.Connected) reconcileAutoSleepOnConnect()
            }
        }
        prefs.watchAddress?.let { addr -> viewModelScope.launch { watchConnection.connect(addr) } }
    }

    /** On a fresh link, converge the watch's auto-sleep register to the scheduled desired state. */
    private fun reconcileAutoSleepOnConnect() {
        val addr = prefs.watchAddress ?: return
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val desired = AutoSleepReconciler.desiredProfile(
            prefs.autoSleepWindowConfig(), now.hour * MINUTES_PER_HOUR + now.minute,
        ) ?: return
        viewModelScope.launch { runCatching { AutoSleepApply.reconcile(ble, addr, desired) } }
    }

    /** UI left the foreground: stop mirroring status and release the held link. */
    fun onBackground() {
        statusJob?.cancel()
        statusJob = null
        watchConnection.disconnect()
    }

    /** Reconnect button: re-establish the held link to the selected watch (no-op without one). */
    fun onReconnect() {
        val addr = prefs.watchAddress ?: return
        viewModelScope.launch { watchConnection.connect(addr) }
    }

    /** Disconnect button: drop the held link but keep the selected watch; a send or Reconnect re-links. */
    fun onDisconnect() {
        watchConnection.disconnect()
    }

    /** Unset button: forget the selected watch entirely — drop the link, clear the address, show NoWatch. */
    fun onUnsetWatch() {
        watchConnection.disconnect()
        prefs.watchAddress = null
        update {
            it.copy(
                watchLabel = "Watch: none selected",
                watchSelected = false,
                connectionStatus = ConnectionStatus.NoWatch,
            )
        }
    }

    /**
     * Steps-card toggle: opt in/out of reading live steps from a bonded RingConn ring. Turning on
     * with no ring chosen yet auto-selects it when exactly one bonded ring is found; the saved
     * address is kept on toggle-off so a later re-enable doesn't need to re-select.
     */
    fun toggleRingConnSteps(enabled: Boolean) {
        prefs.ringConnStepsEnabled = enabled
        val rings = ringDiscovery.bondedRings()
        if (enabled && prefs.ringConnAddress == null) {
            rings.singleOrNull()?.let { prefs.ringConnAddress = it.address }
        }
        state = state.copy(ringConnStepsEnabled = enabled, ringConnName = ringNameFor(prefs.ringConnAddress, rings))
    }

    /** Display name of the selected ring, or null when none is selected or it is no longer bonded. */
    private fun ringNameFor(address: String?, rings: List<RingDevice>): String? =
        address?.let { addr -> rings.firstOrNull { it.address == addr }?.name }
}
