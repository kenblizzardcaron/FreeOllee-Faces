package com.blizzardcaron.freeolleefaces

import com.blizzardcaron.freeolleefaces.activity.ActivityMetricsRepository
import com.blizzardcaron.freeolleefaces.activity.ActivityTrack
import com.blizzardcaron.freeolleefaces.activity.ActivityTrackStore
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.activity.NoopActivityTrackStore
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.fakes.FakeActivityTrackStore
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.fakes.FakeLocationProvider
import com.blizzardcaron.freeolleefaces.fakes.FakeNotificationAccessChecker
import com.blizzardcaron.freeolleefaces.fakes.FakeScheduler
import com.blizzardcaron.freeolleefaces.fakes.FakeStepsProvider
import com.blizzardcaron.freeolleefaces.fakes.FakeWatchConnection
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository
import com.blizzardcaron.freeolleefaces.fakes.FakeAlarmScheduler
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(testScheduler)

    @BeforeTest
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private val watchAddress = "AA:BB:CC:DD:EE:FF"

    // ---------------------------------------------------------------------------
    // Startup seeding — battery card remembers the last fetch across restarts
    // ---------------------------------------------------------------------------

    @Test
    fun initialState_seedsBatteryPreviewAndReadoutFromPrefs() = runTest(testScheduler) {
        val prefs = Prefs(MapSettings())
        prefs.batteryReadout = com.blizzardcaron.freeolleefaces.format.BatteryReadout.VOLTS
        prefs.recordBatteryFetch(2850)

        val vm = vmWith(FakeWatchConnection(), prefs)

        val preview = vm.state.batteryPreview
        assertTrue(
            preview is com.blizzardcaron.freeolleefaces.ui.PreviewState.Ready,
            "battery preview should seed from the cached fetch, got $preview",
        )
        assertEquals(com.blizzardcaron.freeolleefaces.format.BatteryReadout.VOLTS, vm.state.batteryReadout)
        assertTrue(
            vm.state.batteryUpdated?.startsWith("Updated") == true,
            "batteryUpdated should seed from the cached fetch time: ${vm.state.batteryUpdated}",
        )
    }

    // ---------------------------------------------------------------------------
    // Test F — connection status lifecycle + reconnect
    // ---------------------------------------------------------------------------

    private fun vmWith(
        fake: FakeWatchConnection,
        prefs: Prefs,
        callLog: MutableList<String> = mutableListOf(),
        activityStore: ActivityTrackStore = NoopActivityTrackStore,
        clock: Clock = Clock.System,
    ): AppViewModel = AppViewModel(
        prefs = prefs,
        ble = FakeBleClient(callLog),
        steps = FakeStepsProvider(),
        location = FakeLocationProvider(),
        notificationAccess = FakeNotificationAccessChecker(),
        timerRepo = TimerSetsRepository(MapSettings()),
        metricsRepo = ActivityMetricsRepository(MapSettings()),
        scheduler = FakeScheduler(callLog),
        alarmRepo = AlarmsRepository(MapSettings()),
        alarmScheduler = FakeAlarmScheduler(callLog),
        watchConnection = fake,
        activityStore = activityStore,
        clock = clock,
    )

    @Test
    fun onStart_prunesTracksOlderThanRetentionWindow() = runTest(testScheduler) {
        val dayMs = 24L * 60 * 60 * 1000
        val now = 30 * dayMs
        val store = FakeActivityTrackStore().apply {
            save(ActivityTrack("old", startedAtMs = 0, endedAtMs = now - 8 * dayMs, unit = ActivityUnit.METRIC))
            save(ActivityTrack("fresh", startedAtMs = 0, endedAtMs = now - dayMs, unit = ActivityUnit.METRIC))
            save(ActivityTrack("running", startedAtMs = 0, endedAtMs = null, unit = ActivityUnit.METRIC))
        }
        val fixedClock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(now)
        }
        val vm = vmWith(FakeWatchConnection(), Prefs(MapSettings()), activityStore = store, clock = fixedClock)

        vm.onStart()

        assertEquals(setOf("fresh", "running"), store.list().map { it.id }.toSet())
    }

    @Test
    fun onForeground_withWatch_connectsAndReflectsConnected() = runTest(testScheduler) {
        val fake = FakeWatchConnection(connectResult = ConnectionStatus.Connected)
        val prefs = Prefs(MapSettings()).apply { watchAddress = this@AppViewModelTest.watchAddress }
        val vm = vmWith(fake, prefs)

        vm.onForeground()
        advanceUntilIdle()

        assertEquals(1, fake.connectCount, "onForeground should connect once when a watch is selected")
        assertEquals(ConnectionStatus.Connected, vm.state.connectionStatus,
            "state should reflect the link going Connected")

        vm.onBackground()   // cancel the status collector so runTest sees no lingering work
    }

    @Test
    fun onForeground_noWatch_doesNotConnect_andShowsNoWatch() = runTest(testScheduler) {
        val fake = FakeWatchConnection()
        val prefs = Prefs(MapSettings())   // no watch address
        val vm = vmWith(fake, prefs)

        vm.onForeground()
        advanceUntilIdle()

        assertEquals(0, fake.connectCount, "no connect attempt without a selected watch")
        assertEquals(ConnectionStatus.NoWatch, vm.state.connectionStatus,
            "with no watch the chip is NoWatch regardless of the link's own status")

        vm.onBackground()
    }

    @Test
    fun onReconnect_failure_reflectsNotReachable_andRetriesOnTap() = runTest(testScheduler) {
        val fake = FakeWatchConnection(connectResult = ConnectionStatus.NotReachable)
        val prefs = Prefs(MapSettings()).apply { watchAddress = this@AppViewModelTest.watchAddress }
        val vm = vmWith(fake, prefs)

        vm.onForeground()        // first connect attempt -> NotReachable
        advanceUntilIdle()
        assertEquals(1, fake.connectCount)
        assertEquals(ConnectionStatus.NotReachable, vm.state.connectionStatus)

        vm.onReconnect()         // chip-button tap -> another attempt
        advanceUntilIdle()
        assertEquals(2, fake.connectCount, "onReconnect should trigger a fresh connect attempt")
        assertEquals(ConnectionStatus.NotReachable, vm.state.connectionStatus)

        vm.onBackground()
    }

    @Test
    fun onReconnect_noWatch_isNoOp() = runTest(testScheduler) {
        val fake = FakeWatchConnection()
        val vm = vmWith(fake, Prefs(MapSettings()))   // no watch address

        vm.onReconnect()
        advanceUntilIdle()

        assertEquals(0, fake.connectCount, "reconnect is a no-op with no watch selected")
    }

    @Test
    fun onBackground_disconnects_andStopsObservingStatus() = runTest(testScheduler) {
        val fake = FakeWatchConnection(connectResult = ConnectionStatus.Connected)
        val prefs = Prefs(MapSettings()).apply { watchAddress = this@AppViewModelTest.watchAddress }
        val vm = vmWith(fake, prefs)

        vm.onForeground()
        advanceUntilIdle()
        assertEquals(ConnectionStatus.Connected, vm.state.connectionStatus)

        vm.onBackground()
        advanceUntilIdle()
        assertEquals(1, fake.disconnectCount, "onBackground should release the held link")

        // disconnect() flipped the fake's status to NotReachable, but the collector is cancelled, so
        // state must NOT have followed it — proving observation stopped on background.
        assertEquals(ConnectionStatus.Connected, vm.state.connectionStatus,
            "state should freeze once observation stops on background")
    }

    @Test
    fun onDisconnect_dropsLink_butKeepsSelectedWatch() = runTest(testScheduler) {
        val fake = FakeWatchConnection(connectResult = ConnectionStatus.Connected)
        val prefs = Prefs(MapSettings()).apply { watchAddress = this@AppViewModelTest.watchAddress }
        val vm = vmWith(fake, prefs)

        vm.onDisconnect()
        advanceUntilIdle()

        assertEquals(1, fake.disconnectCount, "onDisconnect should release the held link")
        assertEquals(watchAddress, prefs.watchAddress, "disconnect must NOT forget the selected watch")
    }

    @Test
    fun onUnsetWatch_forgetsWatch_andShowsNoWatch() = runTest(testScheduler) {
        val fake = FakeWatchConnection(connectResult = ConnectionStatus.Connected)
        val prefs = Prefs(MapSettings()).apply { watchAddress = this@AppViewModelTest.watchAddress }
        val vm = vmWith(fake, prefs)

        vm.onUnsetWatch()
        advanceUntilIdle()

        assertEquals(1, fake.disconnectCount, "unset should release the held link")
        assertEquals(null, prefs.watchAddress, "unset should clear the saved watch address")
        assertEquals(false, vm.state.watchSelected, "unset should mark no watch selected")
        assertEquals(ConnectionStatus.NoWatch, vm.state.connectionStatus, "unset should show NoWatch")
    }

    @Test
    fun onWatchPicked_connects() = runTest(testScheduler) {
        val fake = FakeWatchConnection()
        val vm = vmWith(fake, Prefs(MapSettings()))   // no watch yet

        vm.onWatchPicked(watchAddress, "Watch: $watchAddress")
        advanceUntilIdle()

        assertEquals(1, fake.connectCount, "selecting a watch should establish the link")
        assertEquals(watchAddress, fake.connectedAddresses.last())
    }

    @Test
    fun exposes_an_activity_controller() {
        val fake = FakeWatchConnection()
        val vm = vmWith(fake, Prefs(MapSettings()))

        assertEquals(false, vm.activity.state.value.running)
    }
}
