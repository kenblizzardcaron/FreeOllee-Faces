package com.blizzardcaron.freeolleefaces.ring

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context

/** A bonded RingConn ring the user can read steps from. */
data class RingDevice(val address: String, val name: String)

/**
 * Finds bonded RingConn rings by name prefix. Bonded-enumeration + connect-by-address needs only
 * BLUETOOTH_CONNECT — no scan, no location permission. Never throws.
 */
class RingConnDiscovery(context: Context) {

    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    fun bondedRings(): List<RingDevice> = runCatching {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return emptyList()
        adapter.bondedDevices.orEmpty()
            .filter { it.name?.startsWith(RING_NAME_PREFIX) == true }
            .map { RingDevice(it.address, it.name.orEmpty()) }
    }.getOrDefault(emptyList())

    private companion object {
        const val RING_NAME_PREFIX = "RingConn"
    }
}
