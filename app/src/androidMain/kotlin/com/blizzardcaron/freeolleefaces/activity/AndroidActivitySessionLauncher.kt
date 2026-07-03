package com.blizzardcaron.freeolleefaces.activity

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** Routes VM controller calls to [ActivitySessionService]; exposes the host's shared state. */
class AndroidActivitySessionLauncher(private val context: Context) : ActivitySessionLauncher {
    override val state: StateFlow<ActivityState> = ActivitySessionHost.state
    override fun start() = ActivitySessionService.start(context)
    override fun stop() = ActivitySessionService.stop(context)
    override fun cycleMetric() = ActivitySessionService.cycle(context)
    override fun setUnit(unit: ActivityUnit) = ActivitySessionService.setUnit(context)
    override fun pause() = ActivitySessionService.pause(context)
    override fun resume() = ActivitySessionService.resume(context)
}
