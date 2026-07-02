package com.blizzardcaron.freeolleefaces.activity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Live phone-sensor readouts for the idle Activity home. Null = sensor absent or no reading yet. */
data class IdleInstruments(
    val headingDeg: Float? = null,
    val pressureHpa: Double? = null,
)

/**
 * Sensors-only instruments for the idle Activity home (compass azimuth + barometric pressure).
 * No GPS, no watch push, nothing recorded. Mirrors [com.blizzardcaron.freeolleefaces.location.LocationProvider]:
 * platform impls own the plumbing; [start]/[stop] bracket sensor registration.
 */
interface InstrumentsProvider {
    val instruments: StateFlow<IdleInstruments>
    fun start()
    fun stop()
}

/** Inert provider: empty readouts, control is a no-op. Default for tests and headless construction. */
object NoopInstrumentsProvider : InstrumentsProvider {
    override val instruments: StateFlow<IdleInstruments> = MutableStateFlow(IdleInstruments())
    override fun start() = Unit
    override fun stop() = Unit
}
