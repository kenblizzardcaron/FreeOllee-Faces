package com.blizzardcaron.freeolleefaces.activity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the VM controller calls to drive a session. Android impl (Task 14) routes to the service. */
interface ActivitySessionLauncher {
    val state: StateFlow<ActivityState>
    fun start()
    fun stop()
    fun cycleMetric()
    fun setUnit(unit: ActivityUnit)
    fun pause()
    fun resume()
}

/** Inert launcher: idle state, control is a no-op. Default for tests and watch-less construction. */
object NoopActivitySessionLauncher : ActivitySessionLauncher {
    override val state: StateFlow<ActivityState> = MutableStateFlow(ActivityState())
    override fun start() = Unit
    override fun stop() = Unit
    override fun cycleMetric() = Unit
    override fun setUnit(unit: ActivityUnit) = Unit
    override fun pause() = Unit
    override fun resume() = Unit
}
