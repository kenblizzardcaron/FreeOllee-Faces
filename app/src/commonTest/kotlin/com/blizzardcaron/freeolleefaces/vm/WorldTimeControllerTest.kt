package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.ui.HomeState
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
        // Watch reports +2h (Berlin in July) — user changed it on-watch.
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
        ble.awaitResult = Result.success(weekdayReplyFrame(WorldTimeCodec.encode(9 * 3600)))
        val c = controller(this)
        c.reconcileOnOpen()
        testScheduler.advanceUntilIdle()
        assertEquals("Asia/Tokyo", prefs.worldTimeActiveZone)
        assertTrue(ble.sentPackets.isEmpty()) // reconcile never writes
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
}
