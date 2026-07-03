package com.blizzardcaron.freeolleefaces.ring

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context

/**
 * Finds bonded RingConn rings by name prefix. Bonded-enumeration + connect-by-address needs only
 * BLUETOOTH_CONNECT — no scan, no location permission. Never throws.
 */
class RingConnDiscovery(context: Context) : RingDiscovery {

    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun bondedRings(): List<RingDevice> = runCatching {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        // isEnabled guard mirrors MainActivity's bondedDevices(): with the radio off, don't
        // trust the bonded list.
        val adapter = manager?.adapter?.takeIf { it.isEnabled } ?: return emptyList()
        adapter.bondedDevices.orEmpty()
            .filter { it.name?.startsWith(RING_NAME_PREFIX) == true }
            .map { RingDevice(it.address, it.name.orEmpty()) }
    }.getOrDefault(emptyList())

    private companion object {
        const val RING_NAME_PREFIX = "RingConn"
    }
}
