package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.ble.BatteryReadback
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.fakes.FakeLocationProvider
import com.blizzardcaron.freeolleefaces.fakes.FakeNotificationAccessChecker
import com.blizzardcaron.freeolleefaces.fakes.FakeScheduler
import com.blizzardcaron.freeolleefaces.fakes.FakeStepsProvider
import com.blizzardcaron.freeolleefaces.format.BatteryReadout
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.health.StepsProvider
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.PreviewState
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Moved from AppViewModelTest's complication-cluster test (Test A: activate ordering) — the
 * weather/temperature/location/steps/notification/custom-text/active-complication cluster now
 * lives in [ComplicationController], constructed directly against fakes from `fakes/Fakes.kt`.
 * The shared `sending` flag is modeled with a local `HomeState` holder, exactly mirroring how
 * AppViewModel wires `state`/`update` into the controller (single shared flag, not a private
 * copy) — the same pattern [TimerControllerTest] uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComplicationControllerTest {

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

    private val watchAddress = "AA:BB:CC:DD:EE:FF"

    /** Local HomeState holder mirroring AppViewModel's state/update wiring for the controller. */
    private class StateHolder(initial: HomeState = HomeState()) {
        var st = initial
        val state: () -> HomeState = { st }
        val update: ((HomeState) -> HomeState) -> Unit = { t -> st = t(st) }
    }

    private fun controller(
        prefs: Prefs,
        ble: FakeBleClient,
        scheduler: FakeScheduler,
        scope: kotlinx.coroutines.CoroutineScope,
        steps: FakeStepsProvider = FakeStepsProvider(),
        location: FakeLocationProvider = FakeLocationProvider(),
        notificationAccess: FakeNotificationAccessChecker = FakeNotificationAccessChecker(),
        holder: StateHolder = StateHolder(),
        showSnackbar: (String) -> Unit = {},
        clock: Clock = Clock.System,
    ) = ComplicationController(
        prefs = prefs,
        ble = ble,
        steps = steps,
        location = location,
        notificationAccess = notificationAccess,
        scheduler = scheduler,
        scope = scope,
        showSnackbar = showSnackbar,
        state = holder.state,
        update = holder.update,
        clock = clock,
    )

    // ---------------------------------------------------------------------------
    // Test A — activate(TEMPERATURE) ordering
    // ---------------------------------------------------------------------------

    /**
     * Verifies that activate(TEMPERATURE) with a fresh temp cache:
     *   1. Persists activeComplication to prefs (verified via a second Prefs backed by same MapSettings)
     *   2. Updates the shared HomeState.activeComplication
     *   3. Calls scheduler.reschedule() BEFORE any BLE send (ordering in shared callLog)
     *   4. Fires the BLE send after coroutines drain
     *
     * Also verifies the synchronous / async split:
     *   - scheduler.reschedule IS already in the log before advanceUntilIdle()
     *   - BLE send is NOT in the log before advanceUntilIdle()
     */
    @Test
    fun activate_temperature_orderingAndPersistence() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val scheduler = FakeScheduler(callLog)

        val settings = MapSettings()
        val prefs = Prefs(settings)

        // Set valid coords so validCoords() returns non-null and the HomeState holder has lat/lng.
        prefs.lastLat = 40.7128
        prefs.lastLng = -74.0060

        // Seed a fresh temp cache: fetchedMs = now, so (now - fetchedMs) = 0 < 15*60000.
        val nowMs = Clock.System.now().toEpochMilliseconds()
        prefs.tempFetchedMs = nowMs
        prefs.tempValue = 72.0
        prefs.tempCacheUnit = TempUnit.FAHRENHEIT

        // Set watch address so pushIfWatch fires and a BLE send is recorded.
        prefs.watchAddress = watchAddress

        val holder = StateHolder(HomeState(lat = "40.7128", lng = "-74.0060"))
        val c = controller(prefs, ble, scheduler, this, holder = holder)

        // Pre-condition: log is empty.
        assertTrue(callLog.isEmpty(), "callLog should be empty before activate()")

        c.activate(ActiveComplication.TEMPERATURE)

        // --- Synchronous assertions (before draining coroutines) ---

        // 1. prefs.activeComplication persisted synchronously (read back via same settings).
        val prefsReader = Prefs(settings)
        assertEquals(ActiveComplication.TEMPERATURE, prefsReader.activeComplication,
            "prefs.activeComplication should be persisted synchronously by activate()")

        // 2. shared state.activeComplication updated synchronously.
        assertEquals(ActiveComplication.TEMPERATURE, holder.st.activeComplication,
            "state.activeComplication should be set synchronously by activate()")

        // scheduler.reschedule() is synchronous — already in the log.
        assertTrue(callLog.contains("scheduler.reschedule"),
            "scheduler.reschedule should be in callLog before advanceUntilIdle: $callLog")

        // BLE send has NOT yet fired (it's inside a coroutine launched by pushIfWatch).
        val bleBeforeIdle = callLog.any { it.startsWith("ble.") }
        assertTrue(!bleBeforeIdle,
            "BLE send should NOT appear in callLog before advanceUntilIdle: $callLog")

        advanceUntilIdle()

        // --- Post-drain assertions ---

        // 3. scheduler.reschedule precedes the BLE send in the ordered log.
        val rescheduleIdx = callLog.indexOfFirst { it == "scheduler.reschedule" }
        val bleIdx = callLog.indexOfFirst { it.startsWith("ble.") }
        assertTrue(rescheduleIdx >= 0, "scheduler.reschedule should be present after idle")
        assertTrue(bleIdx >= 0,
            "a BLE send should be recorded after advanceUntilIdle: $callLog")
        assertTrue(rescheduleIdx < bleIdx,
            "scheduler.reschedule (index $rescheduleIdx) must precede BLE send " +
                "(index $bleIdx) in callLog: $callLog")
    }

    // ---------------------------------------------------------------------------
    // refreshTemp — no coords -> Error preview
    // ---------------------------------------------------------------------------

    @Test
    fun refreshTemp_noCoords_setsErrorPreview() {
        val prefs = Prefs(MapSettings())
        val holder = StateHolder()
        val c = controller(prefs, FakeBleClient(), FakeScheduler(), kotlinx.coroutines.test.TestScope(), holder = holder)

        c.refreshTemp(force = false, push = false)

        assertEquals(
            PreviewState.Error("Location not set — open Settings (⚙)"),
            holder.st.tempPreview,
        )
    }

    // ---------------------------------------------------------------------------
    // refreshSteps — health permission denied -> Error preview, granted flag false
    // ---------------------------------------------------------------------------

    @Test
    fun refreshSteps_noPermission_setsErrorPreviewAndGrantedFalse() = runTest(testScheduler) {
        val prefs = Prefs(MapSettings())
        val holder = StateHolder()
        val steps = FakeStepsProvider(readPermission = false)
        val c = controller(prefs, FakeBleClient(), FakeScheduler(), this, steps = steps, holder = holder)

        c.refreshSteps(push = false)
        advanceUntilIdle()

        assertEquals(false, holder.st.stepsHealthGranted)
        assertEquals(
            PreviewState.Error("Grant Health access to read steps"),
            holder.st.stepsPreview,
        )
    }

    @Test
    fun refreshSteps_success_updatesPreviewAndPersists() = runTest(testScheduler) {
        val settings = MapSettings()
        val prefs = Prefs(settings)
        val holder = StateHolder()
        val steps = FakeStepsProvider(stepsResult = Result.success(1234L))
        val c = controller(prefs, FakeBleClient(), FakeScheduler(), this, steps = steps, holder = holder)

        c.refreshSteps(push = false)
        advanceUntilIdle()

        assertTrue(holder.st.stepsPreview is PreviewState.Ready, "expected Ready preview")
        assertEquals(1234L, prefs.lastStepCount, "step count should be persisted via prefs.recordStepsFetch")
    }

    // ---------------------------------------------------------------------------
    // refreshBattery — success caches voltage + sets Ready; no watch -> Error preview
    // ---------------------------------------------------------------------------

    @Test
    fun refreshBattery_success_setsReadyAndCaches() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val scheduler = FakeScheduler(callLog)
        val prefs = Prefs(MapSettings())
        prefs.watchAddress = watchAddress

        val payload = ByteArray(BatteryReadback.VOLTAGE_OFFSET + 2)
        payload[BatteryReadback.VOLTAGE_OFFSET] = 0x0B
        payload[BatteryReadback.VOLTAGE_OFFSET + 1] = 0x22 // 0x0B22 = 2850 mV
        ble.awaitResult = Result.success(
            OlleeProtocol.Frame(cmd = 0x02, target = 0x4a, payload = payload, crcOk = true),
        )

        val holder = StateHolder(HomeState(batteryReadout = BatteryReadout.PERCENT))
        val comp = controller(prefs, ble, scheduler, this, holder = holder)

        comp.refreshBattery(push = false)
        advanceUntilIdle()

        val preview = holder.st.batteryPreview
        assertTrue(preview is PreviewState.Ready, "expected Ready, got $preview")
        assertEquals("   75P", (preview as PreviewState.Ready).payload)
        assertEquals(2850, prefs.batteryValueMv)
    }

    @Test
    fun refreshBattery_freshCache_rendersFromCacheWithoutBle() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val prefs = Prefs(MapSettings())
        prefs.watchAddress = watchAddress
        prefs.recordBatteryFetch(2850) // fetchedMs = now -> fresh within the default 15-min interval
        val holder = StateHolder(HomeState(batteryReadout = BatteryReadout.PERCENT))
        val comp = controller(prefs, ble, FakeScheduler(), this, holder = holder)

        comp.refreshBattery(push = false)
        advanceUntilIdle()

        val preview = holder.st.batteryPreview
        assertTrue(preview is PreviewState.Ready, "expected Ready from cache, got $preview")
        assertEquals("   75P", (preview as PreviewState.Ready).payload)
        assertTrue(callLog.none { it.startsWith("ble.") }, "fresh cache must not re-read BLE: $callLog")
    }

    @Test
    fun refreshBattery_expiredCache_keepsLastValueDuringRead_thenUpdates() = runTest(testScheduler) {
        val ble = FakeBleClient(mutableListOf())
        val prefs = Prefs(MapSettings())
        prefs.watchAddress = watchAddress
        prefs.batteryValueMv = 2700 // 50P
        prefs.batteryFetchedMs = Clock.System.now().toEpochMilliseconds() - 16 * 60_000L // expired

        val payload = ByteArray(BatteryReadback.VOLTAGE_OFFSET + 2)
        payload[BatteryReadback.VOLTAGE_OFFSET] = 0x0B
        payload[BatteryReadback.VOLTAGE_OFFSET + 1] = 0x22 // 2850 mV -> 75P
        ble.awaitResult = Result.success(
            OlleeProtocol.Frame(cmd = 0x02, target = 0x4a, payload = payload, crcOk = true),
        )
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        ble.gate = gate

        // The card is already rendering the cached value (seeded at startup / previous refresh).
        val seeded = PreviewState.Ready("   50P", "Battery: 50%")
        val holder = StateHolder(
            HomeState(batteryReadout = BatteryReadout.PERCENT, batteryPreview = seeded),
        )
        val comp = controller(prefs, ble, FakeScheduler(), this, holder = holder)

        comp.refreshBattery(push = false)
        testScheduler.runCurrent() // read now suspended on the gate

        assertEquals(
            seeded, holder.st.batteryPreview,
            "the shown value must survive a re-read; no flip to Loading",
        )

        gate.complete(Unit)
        advanceUntilIdle()

        val preview = holder.st.batteryPreview
        assertTrue(preview is PreviewState.Ready, "expected Ready, got $preview")
        assertEquals("   75P", (preview as PreviewState.Ready).payload)
        assertEquals(2850, prefs.batteryValueMv)
    }

    @Test
    fun refreshActive_forceBattery_reReadsDespiteFreshCache() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val prefs = Prefs(MapSettings())
        prefs.watchAddress = watchAddress
        prefs.recordBatteryFetch(2700) // fresh cache

        val payload = ByteArray(BatteryReadback.VOLTAGE_OFFSET + 2)
        payload[BatteryReadback.VOLTAGE_OFFSET] = 0x0B
        payload[BatteryReadback.VOLTAGE_OFFSET + 1] = 0x22 // 2850 mV
        ble.awaitResult = Result.success(
            OlleeProtocol.Frame(cmd = 0x02, target = 0x4a, payload = payload, crcOk = true),
        )

        val holder = StateHolder(
            HomeState(
                batteryReadout = BatteryReadout.PERCENT,
                activeComplication = ActiveComplication.BATTERY,
            ),
        )
        val comp = controller(prefs, ble, FakeScheduler(), this, holder = holder)

        comp.refreshActive(force = true, push = false)
        advanceUntilIdle()

        assertTrue(callLog.any { it.startsWith("ble.") }, "force must re-read BLE: $callLog")
        assertEquals(2850, prefs.batteryValueMv)
    }

    @Test
    fun refreshBattery_noWatch_setsError() = runTest(testScheduler) {
        val ble = FakeBleClient(mutableListOf())
        val scheduler = FakeScheduler(mutableListOf())
        val prefs = Prefs(MapSettings()) // no watchAddress
        val holder = StateHolder()
        val comp = controller(prefs, ble, scheduler, this, holder = holder)

        comp.refreshBattery(push = false)
        advanceUntilIdle()

        assertTrue(holder.st.batteryPreview is PreviewState.Error, "expected Error")
    }

    // ---------------------------------------------------------------------------
    // sendCustom — no watch -> early return (no BLE call, no persist)
    // ---------------------------------------------------------------------------

    @Test
    fun sendCustom_noWatchAddress_doesNotCallBle() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val prefs = Prefs(MapSettings())
        val c = controller(prefs, ble, FakeScheduler(), this)

        c.sendCustom("ABC123")
        advanceUntilIdle()

        assertTrue(callLog.none { it.startsWith("ble.") }, "no BLE call without a watch")
        assertEquals("", prefs.customText, "no watch address means an early return before persisting")
    }

    @Test
    fun sendCustom_success_updatesSentTimestampInState() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val prefs = Prefs(MapSettings())
        prefs.watchAddress = watchAddress
        val holder = StateHolder()
        val c = controller(prefs, ble, FakeScheduler(), this, holder = holder)

        c.sendCustom("HELLO")
        advanceUntilIdle()

        assertTrue(callLog.any { it.startsWith("ble.send(") }, "expected a BLE send for custom text")
        assertTrue(holder.st.customSent?.startsWith("Sent 'HELLO' at") == true,
            "customSent should reflect the sent text and time: ${holder.st.customSent}")
    }

    // ---------------------------------------------------------------------------
    // setNotificationsEnabled — toggles state + prefs, pushes packet when watch selected
    // ---------------------------------------------------------------------------

    @Test
    fun setNotificationsEnabled_noWatch_updatesStateAndPrefsOnly() {
        val prefs = Prefs(MapSettings())
        val holder = StateHolder()
        val c = controller(prefs, FakeBleClient(), FakeScheduler(), kotlinx.coroutines.test.TestScope(), holder = holder)

        c.setNotificationsEnabled(true)

        assertEquals(true, holder.st.notificationsEnabled)
        assertEquals(true, prefs.notificationsEnabled)
    }

    // ---------------------------------------------------------------------------
    // pushCountIfWatch — no watch -> no-op
    // ---------------------------------------------------------------------------

    @Test
    fun pushCountIfWatch_noWatchAddress_doesNotCallBle() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val prefs = Prefs(MapSettings())
        val c = controller(prefs, ble, FakeScheduler(), this)

        c.pushCountIfWatch()
        advanceUntilIdle()

        assertTrue(callLog.none { it.startsWith("ble.") }, "no BLE call without a watch")
    }

    // ---------------------------------------------------------------------------
    // setTempUnit — persists + updates state + triggers refreshTemp push only if TEMPERATURE active
    // ---------------------------------------------------------------------------

    @Test
    fun setTempUnit_persistsAndUpdatesState() {
        val prefs = Prefs(MapSettings())
        prefs.lastLat = 40.0
        prefs.lastLng = -74.0
        val holder = StateHolder(HomeState(lat = "40.0", lng = "-74.0"))
        val c = controller(prefs, FakeBleClient(), FakeScheduler(), kotlinx.coroutines.test.TestScope(), holder = holder)

        c.setTempUnit(TempUnit.CELSIUS)

        assertEquals(TempUnit.CELSIUS, prefs.tempUnit)
        assertEquals(TempUnit.CELSIUS, holder.st.tempUnit)
    }

    // ---------------------------------------------------------------------------
    // setCustomText — caps at 6 chars, persists, updates state
    // ---------------------------------------------------------------------------

    @Test
    fun setCustomText_capsAtSixChars() {
        val prefs = Prefs(MapSettings())
        val holder = StateHolder()
        val c = controller(prefs, FakeBleClient(), FakeScheduler(), kotlinx.coroutines.test.TestScope(), holder = holder)

        c.setCustomText("ABCDEFGH")

        assertEquals("ABCDEF", prefs.customText)
        assertEquals("ABCDEF", holder.st.custom)
    }

    // ---------------------------------------------------------------------------
    // healthAvailability / onPartialHealthGrant / activeIsSteps / hasSavedCoords / locationIsStale
    // ---------------------------------------------------------------------------

    @Test
    fun healthAvailability_delegatesToStepsProvider() {
        val prefs = Prefs(MapSettings())
        val steps = FakeStepsProvider(availability = StepsProvider.Availability.UNAVAILABLE)
        val c = controller(prefs, FakeBleClient(), FakeScheduler(), kotlinx.coroutines.test.TestScope(), steps = steps)

        assertEquals(StepsProvider.Availability.UNAVAILABLE, c.healthAvailability())
    }

    @Test
    fun activeIsSteps_reflectsState() {
        val prefs = Prefs(MapSettings())
        val holder = StateHolder(HomeState(activeComplication = ActiveComplication.STEPS))
        val c = controller(prefs, FakeBleClient(), FakeScheduler(), kotlinx.coroutines.test.TestScope(), holder = holder)

        assertTrue(c.activeIsSteps())
    }

    @Test
    fun hasSavedCoords_reflectsPrefs() {
        val prefs = Prefs(MapSettings())
        val c = controller(prefs, FakeBleClient(), FakeScheduler(), kotlinx.coroutines.test.TestScope())
        assertTrue(!c.hasSavedCoords())

        prefs.lastLat = 1.0; prefs.lastLng = 2.0
        assertTrue(c.hasSavedCoords())
    }

    @Test
    fun onPartialHealthGrant_showsSnackbar() {
        var snackbar: String? = null
        val prefs = Prefs(MapSettings())
        val c = controller(
            prefs, FakeBleClient(), FakeScheduler(), kotlinx.coroutines.test.TestScope(),
            showSnackbar = { snackbar = it },
        )

        c.onPartialHealthGrant()

        assertEquals("Allow background read too, so steps keep syncing when the app is closed.", snackbar)
    }

    @Test
    fun onResumeNotifications_refreshesNotificationFieldsFromPrefs() {
        val prefs = Prefs(MapSettings())
        prefs.notificationCount = 5
        prefs.notificationsEnabled = true
        val notificationAccess = FakeNotificationAccessChecker(granted = true)
        val holder = StateHolder()
        val c = controller(
            prefs, FakeBleClient(), FakeScheduler(), kotlinx.coroutines.test.TestScope(),
            notificationAccess = notificationAccess, holder = holder,
        )

        c.onResumeNotifications()

        assertEquals(5, holder.st.notificationCount)
        assertEquals(true, holder.st.notificationAccessGranted)
        assertEquals(true, holder.st.notificationsEnabled)
    }
}
