package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.fakes.FakeLocationProvider
import com.blizzardcaron.freeolleefaces.location.Coords
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.worldtime.SetClock
import com.blizzardcaron.freeolleefaces.worldtime.WorldTimeCodec
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coroutine-test setup mirrors [TimerControllerTest]/[ComplicationControllerTest]: a shared
 * [TestCoroutineScheduler] pinned as the Main dispatcher, tests run via `runTest(testScheduler)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorldTimeControllerTest {

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

    private val julyMs = 1_783_425_600_000L // July 2026: Denver=-6h, Tokyo=+9h
    private val prefs = Prefs(MapSettings()).apply { watchAddress = "AA:BB" }
    private val ble = FakeBleClient()

    // Fixed phone fix the set-clock frame's coordinates are derived from (Task 13 addendum).
    private val LAT_E3 = 40140
    private val LON_E3 = -105144
    private val location = FakeLocationProvider(
        Result.success(Coords(lat = 40.140, lng = -105.144, accuracyM = null, provider = "fake")),
    )
    private var state = HomeState()

    /** Local fixed clock — mirrors [com.blizzardcaron.freeolleefaces.ring.RingDailyStepsTest]'s FixedClock. */
    private class FixedClock(private val epochMs: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(epochMs)
    }

    /** Builds a `0x55` weekday reply frame exactly as WorldTimeReadbackTest does (Task 6). */
    private fun weekdayReplyFrame(header: ByteArray): OlleeProtocol.Frame {
        val payload = header + "MOTUWETHFRSASU".encodeToByteArray()
        val raw = OlleeProtocol.buildRawPacket(
            OlleeProtocol.TARGET_GET_WEEKDAYS + OlleeProtocol.RESPONSE_TARGET_OFFSET,
            payload,
        )
        return OlleeProtocol.parseFrame(raw)!!
    }

    private fun controller(scope: kotlinx.coroutines.CoroutineScope) = WorldTimeController(
        prefs = prefs,
        ble = ble,
        locationProvider = location,
        scope = scope,
        showSnackbar = {},
        state = { state },
        update = { t -> state = t(state) },
        clock = FixedClock(julyMs),
        homeZoneId = { "America/Denver" },
    )

    @Test
    fun addSlotCapsAtMaxAndRejectsUnknownZones() = runTest(testScheduler) {
        val c = controller(this)
        listOf("Asia/Tokyo", "Europe/Berlin", "America/New_York", "Australia/Sydney").forEach { c.addSlot(it) }
        c.addSlot("Europe/London") // 5th: dropped
        c.addSlot("Not/AZone") // invalid: dropped
        assertEquals(4, prefs.worldTimeSlots.size)
        assertEquals(4, state.worldTime.slots.size)
    }

    @Test
    fun activatePersistsIntentThenPushesHeader() = runTest(testScheduler) {
        val c = controller(this)
        c.addSlot("Asia/Tokyo")
        c.activate("Asia/Tokyo")
        testScheduler.advanceUntilIdle()
        assertEquals("Asia/Tokyo", prefs.worldTimeActiveZone) // intent persisted even if send fails
        val expected = NotificationCount.packetFor(0, WorldTimeCodec.encode(9 * 3600))
        assertContentEquals(expected, ble.sentPackets.single())
        assertEquals(9 * 3600, prefs.worldTimeLastPushedOffsetSec)
    }

    @Test
    fun activateFailureKeepsIntentButNotLastPushed() = runTest(testScheduler) {
        ble.sendResult = Result.failure(IllegalStateException("watch asleep"))
        val c = controller(this)
        c.addSlot("Asia/Tokyo")
        c.activate("Asia/Tokyo")
        testScheduler.advanceUntilIdle()
        assertEquals("Asia/Tokyo", prefs.worldTimeActiveZone)
        assertNull(prefs.worldTimeLastPushedOffsetSec)
    }

    @Test
    fun removeActiveSlotClearsActive() = runTest(testScheduler) {
        val c = controller(this)
        c.addSlot("Asia/Tokyo")
        c.activate("Asia/Tokyo")
        testScheduler.advanceUntilIdle()
        c.removeSlot("Asia/Tokyo")
        assertNull(prefs.worldTimeActiveZone)
        assertTrue(prefs.worldTimeSlots.isEmpty())
    }

    @Test
    fun reconcileAdoptsOnWatchChange() = runTest(testScheduler) {
        prefs.worldTimeSlots = listOf("Asia/Tokyo", "Europe/Berlin")
        prefs.worldTimeActiveZone = "Asia/Tokyo"
        // Last write was Tokyo (+9h), but watch now reports Berlin (+2h) — user changed it on-watch.
        prefs.worldTimeLastPushedOffsetSec = 9 * 3600
        ble.awaitResult = Result.success(weekdayReplyFrame(WorldTimeCodec.encode(2 * 3600)))
        val c = controller(this)
        c.reconcileOnOpen()
        testScheduler.advanceUntilIdle()
        assertEquals("Europe/Berlin", prefs.worldTimeActiveZone)
    }

    @Test
    fun reconcileNoOpsWhenWatchMatches() = runTest(testScheduler) {
        prefs.worldTimeSlots = listOf("Asia/Tokyo")
        prefs.worldTimeActiveZone = "Asia/Tokyo"
        // Watch reports +9h (our last successful write); no change, no reconcile.
        prefs.worldTimeLastPushedOffsetSec = 9 * 3600
        ble.awaitResult = Result.success(weekdayReplyFrame(WorldTimeCodec.encode(9 * 3600)))
        val c = controller(this)
        c.reconcileOnOpen()
        testScheduler.advanceUntilIdle()
        assertEquals("Asia/Tokyo", prefs.worldTimeActiveZone)
        assertTrue(ble.sentPackets.isEmpty()) // reconcile never writes
    }

    @Test
    fun reconcileNoOpsWhenRegisterMatchesLastPushedDespiteDstDrift() = runTest(testScheduler) {
        // Test the DST drift bug fix: 0x35 reflects our last successful write, not a recomputed
        // offset. If DST has since changed the zone's offset, a recomputed value would differ
        // from the register, and the old code would incorrectly reconcile. The new code matches
        // against lastPushed and correctly does nothing.
        prefs.worldTimeSlots = listOf("America/Denver")
        prefs.worldTimeActiveZone = "America/Denver"
        // Last write was -6h (Denver in July: PDT, -6h from UTC).
        prefs.worldTimeLastPushedOffsetSec = -6 * 3600
        // Watch still reports -6h (our last write).
        ble.awaitResult = Result.success(weekdayReplyFrame(WorldTimeCodec.encode(-6 * 3600)))
        // Simulate DST transition: inject a clock value that Denver would have a different offset for
        // (e.g. January: MST, -7h instead of -6h). The old code would compute a recomputed offset
        // of -7h, mismatch against the watch's -6h, and incorrectly reconcile. The new code matches
        // against lastPushed (-6h) and correctly does nothing.
        val januaryMs = 1_735_689_600_000L // January 2025: Denver=-7h
        val c = WorldTimeController(
            prefs = prefs,
            ble = ble,
            locationProvider = location,
            scope = this,
            showSnackbar = {},
            state = { state },
            update = { t -> state = t(state) },
            clock = FixedClock(januaryMs),
            homeZoneId = { "America/Denver" },
        )
        c.reconcileOnOpen()
        testScheduler.advanceUntilIdle()
        assertEquals("America/Denver", prefs.worldTimeActiveZone)
        assertTrue(ble.sentPackets.isEmpty()) // no reconcile write
    }

    @Test
    fun reconcileOnOpenSkipsWhileActivatePushInFlight() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val localBle = FakeBleClient(callLog, gate = gate)
        // If reconcile were allowed to run, the watch would report Berlin's offset — proof that
        // adopting it here would revert the Tokyo tap below.
        localBle.awaitResult = Result.success(weekdayReplyFrame(WorldTimeCodec.encode(2 * 3600)))
        val localPrefs = Prefs(MapSettings()).apply {
            watchAddress = "AA:BB"
            worldTimeSlots = listOf("Asia/Tokyo", "Europe/Berlin")
        }
        val c = WorldTimeController(
            prefs = localPrefs,
            ble = localBle,
            locationProvider = location,
            scope = this,
            showSnackbar = {},
            state = { state },
            update = { t -> state = t(state) },
            clock = FixedClock(julyMs),
            homeZoneId = { "America/Denver" },
        )

        // Tap Tokyo: pushHeader sets the in-flight flag synchronously, then the launched
        // coroutine suspends at the gate before it ever reaches the BLE fake's send.
        c.activate("Asia/Tokyo")

        // ON_START fires while that push is still in flight: must be a no-op.
        c.reconcileOnOpen()
        testScheduler.runCurrent()
        assertTrue(callLog.none { it.startsWith("ble.sendAndAwait") },
            "reconcile must not read the watch while a push is in flight: $callLog")

        // Release the gate so the in-flight push completes.
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        // The tapped zone must still be active — reconcile never got a chance to revert it.
        assertEquals("Asia/Tokyo", localPrefs.worldTimeActiveZone)
        val sendPacketCalls = callLog.filter { it.startsWith("ble.sendPacket") }
        assertEquals(1, sendPacketCalls.size, "only the activate push should have written: $callLog")
    }

    @Test
    fun reconcileSkippedWhileSwapped() = runTest(testScheduler) {
        // While swap is active (transient user control), 0x35 cannot observe the clock register
        // changes, so reconciling could clear the swap flag without restoring the clock. Reconcile
        // must skip while swapped is true.
        prefs.worldTimeSlots = listOf("Asia/Tokyo")
        prefs.worldTimeActiveZone = "Asia/Tokyo"
        prefs.worldTimeSwapped = true
        prefs.worldTimeLastPushedOffsetSec = 9 * 3600
        val callLog = mutableListOf<String>()
        val localBle = FakeBleClient(callLog)
        localBle.awaitResult = Result.success(weekdayReplyFrame(WorldTimeCodec.encode(2 * 3600)))
        val c = WorldTimeController(
            prefs = prefs,
            ble = localBle,
            locationProvider = location,
            scope = this,
            showSnackbar = {},
            state = { state },
            update = { t -> state = t(state) },
            clock = FixedClock(julyMs),
            homeZoneId = { "America/Denver" },
        )
        c.reconcileOnOpen()
        testScheduler.advanceUntilIdle()
        assertTrue(prefs.worldTimeSwapped) // swap flag untouched
        assertTrue(callLog.none { it.startsWith("ble.sendAndAwait") },
            "reconcile must not read the watch while swapped: $callLog")
    }

    @Test
    fun swapWritesClockToActiveZoneAndWorldToHome() = runTest(testScheduler) {
        val c = controller(this)
        c.addSlot("Asia/Tokyo")
        c.activate("Asia/Tokyo")
        testScheduler.advanceUntilIdle()
        ble.sentPackets.clear()
        c.toggleSwap()
        testScheduler.advanceUntilIdle()
        assertTrue(prefs.worldTimeSwapped)
        // Two writes: the clock set to Tokyo time, the world register set to home (-6h).
        assertContentEquals(SetClock.build(julyMs, 9 * 3600, LAT_E3, LON_E3), ble.sentPackets[0])
        assertContentEquals(
            NotificationCount.packetFor(0, WorldTimeCodec.encode(-6 * 3600)),
            ble.sentPackets[1],
        )
        assertEquals(9 * 3600, prefs.worldTimeLastPushedClockOffsetSec)
        assertEquals(LAT_E3, prefs.worldTimeSwapLatE3)
        assertEquals(LON_E3, prefs.worldTimeSwapLonE3)
    }

    @Test
    fun unswapRestoresBoth() = runTest(testScheduler) {
        prefs.worldTimeSlots = listOf("Asia/Tokyo")
        prefs.worldTimeActiveZone = "Asia/Tokyo"
        prefs.worldTimeSwapped = true
        val c = controller(this)
        c.toggleSwap()
        testScheduler.advanceUntilIdle()
        assertEquals(false, prefs.worldTimeSwapped)
        assertContentEquals(SetClock.build(julyMs, -6 * 3600, LAT_E3, LON_E3), ble.sentPackets[0])
        assertContentEquals(
            NotificationCount.packetFor(0, WorldTimeCodec.encode(9 * 3600)),
            ble.sentPackets[1],
        )
    }

    @Test
    fun activateWhileSwappedRetargetsTheClock() = runTest(testScheduler) {
        prefs.worldTimeSlots = listOf("Asia/Tokyo", "Europe/Berlin")
        prefs.worldTimeActiveZone = "Asia/Tokyo"
        prefs.worldTimeSwapped = true
        val c = controller(this)
        c.activate("Europe/Berlin")
        testScheduler.advanceUntilIdle()
        // Living in the world zone: the clock follows the new chip (+2h), world stays home.
        assertContentEquals(SetClock.build(julyMs, 2 * 3600, LAT_E3, LON_E3), ble.sentPackets[0])
        assertContentEquals(
            NotificationCount.packetFor(0, WorldTimeCodec.encode(-6 * 3600)),
            ble.sentPackets[1],
        )
    }

    @Test
    fun swapAbortsWhenLocationUnavailable() = runTest(testScheduler) {
        location.fetchResult = Result.failure(IllegalStateException("no fix"))
        prefs.worldTimeSlots = listOf("Asia/Tokyo")
        prefs.worldTimeActiveZone = "Asia/Tokyo"
        val c = controller(this)
        c.toggleSwap()
        testScheduler.advanceUntilIdle()
        // Intent persists (toggleSwap flips it before the push ever runs); the watch write aborts.
        assertTrue(prefs.worldTimeSwapped)
        assertTrue(
            ble.sentPackets.none { it.size > 7 && it[6] == 0x02.toByte() && it[7] == 0x23.toByte() },
            "no SetClock-shaped packet should be sent when location is unavailable: ${ble.sentPackets.size} sent",
        )
        assertNull(prefs.worldTimeLastPushedClockOffsetSec)
    }
}
