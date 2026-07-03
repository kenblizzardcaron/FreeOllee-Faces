package com.blizzardcaron.freeolleefaces.ring

/** A bonded RingConn ring the user can read steps from. */
data class RingDevice(val address: String, val name: String)

/** Enumerates bonded RingConn rings (no scan). Common seam so AppViewModel stays in commonMain. */
interface RingDiscovery {
    fun bondedRings(): List<RingDevice>
}

/** Default for tests and previews: no rings. */
object NoopRingDiscovery : RingDiscovery {
    override fun bondedRings(): List<RingDevice> = emptyList()
}
