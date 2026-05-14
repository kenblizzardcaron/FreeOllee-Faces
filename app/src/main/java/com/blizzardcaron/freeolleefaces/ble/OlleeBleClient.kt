package com.blizzardcaron.freeolleefaces.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OlleeBleClient(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private const val CONNECT_TIMEOUT_MS = 8_000L
    }

    @SuppressLint("MissingPermission")
    suspend fun send(deviceAddress: String, value: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val packet = OlleeProtocol.buildPacket(value.padEnd(OlleeProtocol.MAX_VALUE_LENGTH, ' '))

            val manager = context.getSystemService(BluetoothManager::class.java)
                ?: error("BluetoothManager unavailable")
            val device: BluetoothDevice = manager.adapter.getRemoteDevice(deviceAddress)

            withTimeout(CONNECT_TIMEOUT_MS) {
                writePacket(device, packet)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writePacket(device: BluetoothDevice, packet: ByteArray) =
        suspendCancellableCoroutine<Unit> { cont ->
            var gatt: BluetoothGatt? = null
            val callback = object : BluetoothGattCallback() {

                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        g.close()
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        cont.resumeWithException(IllegalStateException("service discovery failed: $status"))
                        g.disconnect()
                        return
                    }
                    val service = g.getService(SERVICE_UUID)
                        ?: run {
                            cont.resumeWithException(IllegalStateException("Nordic UART service not found"))
                            g.disconnect()
                            return
                        }
                    val char = service.getCharacteristic(CHAR_UUID)
                        ?: run {
                            cont.resumeWithException(IllegalStateException("RX characteristic not found"))
                            g.disconnect()
                            return
                        }
                    val ok: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(
                            char,
                            packet,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = packet
                        @Suppress("DEPRECATION")
                        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(char)
                    }
                    if (!ok) {
                        cont.resumeWithException(IllegalStateException("writeCharacteristic returned false"))
                        g.disconnect()
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (cont.isActive) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            cont.resume(Unit)
                        } else {
                            cont.resumeWithException(IllegalStateException("write failed: $status"))
                        }
                    }
                    g.disconnect()
                }
            }

            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

            cont.invokeOnCancellation {
                runCatching { gatt?.disconnect() }
                runCatching { gatt?.close() }
            }
        }
}
