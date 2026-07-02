package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.fakes.FakeActivityTrackStore
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.fakes.FakeSessionAutoSleep
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivitySessionEngineRecordTest {

    @Test fun stop_shows_a_stopping_state_while_the_watch_restore_is_in_flight() = runTest {
        val store = FakeActivityTrackStore()
        val autoSleep = FakeSessionAutoSleep().apply { restoreGate = CompletableDeferred() }
        val e = ActivitySessionEngine(
            ble = FakeBleClient(), store = store, prefs = Prefs(MapSettings()),
            autoSleep = autoSleep, watchAddress = { "AA:BB" }, now = { 0L }, newId = { "trk" },
        )
        e.start()

        val stopJob = launch { e.stop() }
        runCurrent() // stop() is now suspended on the BLE restore

        assertTrue(e.state.value.stopping, "state should reflect Stopping while the restore runs")
        assertTrue(e.state.value.running, "still on the running screen until stop completes")

        autoSleep.restoreGate!!.complete(Unit)
        stopJob.join()
        assertEquals(ActivityState(), e.state.value)
    }

    @Test fun second_stop_while_stopping_does_not_double_save() = runTest {
        val store = FakeActivityTrackStore()
        val autoSleep = FakeSessionAutoSleep().apply { restoreGate = CompletableDeferred() }
        val e = ActivitySessionEngine(
            ble = FakeBleClient(), store = store, prefs = Prefs(MapSettings()),
            autoSleep = autoSleep, watchAddress = { "AA:BB" }, now = { 0L }, newId = { "trk" },
        )
        e.start()

        val first = launch { e.stop() }
        runCurrent()
        val second = launch { e.stop() } // impatient re-tap while stopping
        runCurrent()

        autoSleep.restoreGate!!.complete(Unit)
        first.join()
        second.join()

        assertEquals(
            1, autoSleep.calls.count { it.startsWith("restore") },
            "a re-tap during Stopping must not re-run the stop path: ${autoSleep.calls}",
        )
    }
}
