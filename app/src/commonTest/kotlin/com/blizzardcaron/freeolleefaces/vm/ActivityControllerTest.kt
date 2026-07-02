package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.activity.ActivityMetricsRepository
import com.blizzardcaron.freeolleefaces.activity.ActivitySessionLauncher
import com.blizzardcaron.freeolleefaces.activity.ActivityState
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityControllerTest {

    private class FakeLauncher : ActivitySessionLauncher {
        val stateFlow = MutableStateFlow(ActivityState())
        override val state: StateFlow<ActivityState> = stateFlow
        val calls = mutableListOf<String>()
        override fun start() { calls += "start" }
        override fun startLive() { calls += "startLive" }
        override fun stop() { calls += "stop" }
        override fun cycleMetric() { calls += "cycle" }
        override fun setUnit(unit: ActivityUnit) { calls += "setUnit($unit)" }
        override fun pause() { calls += "pause" }
        override fun resume() { calls += "resume" }
    }

    private fun controller(
        launcher: FakeLauncher,
        prefs: Prefs,
        permission: Boolean,
        snackbars: MutableList<String>,
    ) = ActivityController(
        launcher = launcher,
        prefs = prefs,
        hasLocationPermission = { permission },
        showSnackbar = { snackbars += it },
        metricsRepo = ActivityMetricsRepository(MapSettings()),
    )

    @Test fun onStart_without_permission_does_not_launch_and_warns() {
        val launcher = FakeLauncher()
        val snackbars = mutableListOf<String>()
        controller(launcher, Prefs(MapSettings()), permission = false, snackbars).onStart()
        assertTrue(launcher.calls.isEmpty())
        assertEquals(1, snackbars.size)
    }

    @Test fun onStart_with_permission_launches_even_without_a_watch() {
        val launcher = FakeLauncher()
        controller(launcher, Prefs(MapSettings()), permission = true, mutableListOf()).onStart()
        assertEquals(listOf("start"), launcher.calls)
    }

    @Test fun onShowLive_without_permission_does_not_launch_and_warns() {
        val launcher = FakeLauncher()
        val snackbars = mutableListOf<String>()
        controller(launcher, Prefs(MapSettings()), permission = false, snackbars).onShowLive()
        assertTrue(launcher.calls.isEmpty())
        assertEquals(1, snackbars.size)
    }

    @Test fun onShowLive_with_permission_starts_live_glance() {
        val launcher = FakeLauncher()
        controller(launcher, Prefs(MapSettings()), permission = true, mutableListOf()).onShowLive()
        assertEquals(listOf("startLive"), launcher.calls)
    }

    @Test fun toggleUnit_flips_pref_and_pushes_to_launcher() {
        val launcher = FakeLauncher()
        val prefs = Prefs(MapSettings()) // defaults IMPERIAL
        val c = controller(launcher, prefs, permission = true, mutableListOf())
        c.toggleUnit()
        assertEquals(ActivityUnit.METRIC, prefs.activityUnit)
        assertEquals(listOf("setUnit(METRIC)"), launcher.calls)
    }

    @Test fun onMode_and_onStop_delegate() {
        val launcher = FakeLauncher()
        val c = controller(launcher, Prefs(MapSettings()), permission = true, mutableListOf())
        c.onMode(); c.onStop()
        assertEquals(listOf("cycle", "stop"), launcher.calls)
    }

    @Test fun onPause_and_onResume_delegate_to_launcher() {
        val l = FakeLauncher()
        val c = controller(l, Prefs(MapSettings()), permission = true, mutableListOf())
        c.onPause(); c.onResume()
        assertEquals(listOf("pause", "resume"), l.calls)
    }

    @Test fun setPushInterval_while_idle_persists_a_preset() {
        val prefs = Prefs(MapSettings())
        controller(FakeLauncher(), prefs, permission = true, mutableListOf()).setPushInterval(15_000L)
        assertEquals(15_000L, prefs.activityPushIntervalMs)
    }

    @Test fun setPushInterval_ignores_non_preset_values() {
        val prefs = Prefs(MapSettings())
        controller(FakeLauncher(), prefs, permission = true, mutableListOf()).setPushInterval(25_000L)
        assertEquals(30_000L, prefs.activityPushIntervalMs) // unchanged default
    }

    @Test fun setPushInterval_updates_the_observable_flow() {
        val prefs = Prefs(MapSettings())
        val c = controller(FakeLauncher(), prefs, permission = true, mutableListOf())
        c.setPushInterval(15_000L)
        assertEquals(15_000L, c.pushIntervalMs.value)
    }

    @Test fun setPushInterval_while_running_leaves_the_flow_unchanged() {
        val launcher = FakeLauncher().apply { stateFlow.value = ActivityState(running = true) }
        val c = controller(launcher, Prefs(MapSettings()), permission = true, mutableListOf())
        c.setPushInterval(3_000L)
        assertEquals(30_000L, c.pushIntervalMs.value)
    }

    @Test fun setPushInterval_while_running_is_rejected_with_snackbar() {
        val launcher = FakeLauncher().apply { stateFlow.value = ActivityState(running = true) }
        val prefs = Prefs(MapSettings())
        val snackbars = mutableListOf<String>()
        controller(launcher, prefs, permission = true, snackbars).setPushInterval(3_000L)
        assertEquals(30_000L, prefs.activityPushIntervalMs)
        assertEquals(1, snackbars.size)
    }
}
