package com.blizzardcaron.freeolleefaces.ring

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Reads the ring's live onboard step count with a short-lived GATT connection: connect ->
 * enable notify -> auth handshake -> prompt a status descriptor -> parse steps -> close. Separate
 * from the watch's [com.blizzardcaron.freeolleefaces.ble.WatchLink] singleton (different device).
 * Never throws; every fault becomes Result.failure, and a connected-but-silent cycle becomes
 * success(null).
 *
 * Protocol (btsnoop-verified on Gen2, see plans/2026-07-02-ringconn-steps-design.md):
 * CCCD <- `01 00`; write `01 00 00`; notify `81 00 <challenge> <xor>`; write
 * `01 01 <r0 r1 r2> 00` ([RingAuth.authCommand]); write `d0 00 00`; notify a `10`/`87` status
 * descriptor with the step count at bytes [4:6] big-endian ([RingConnDescriptor.parseSteps]).
 */
class AndroidRingStepsSource(
    context: Context,
    private val addressProvider: () -> String?,
) : RingStepsSource {

    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    override suspend fun readSteps(): Result<Long?> = withContext(Dispatchers.IO) {
        val address = addressProvider()
            ?: return@withContext Result.failure(IllegalStateException("no ring selected"))
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
            ?: return@withContext Result.failure(IllegalStateException("no bluetooth adapter"))
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("bad ring address"))
        val mac = macBytes(address)
            ?: return@withContext Result.failure(IllegalStateException("bad ring address"))

        val result = CompletableDeferred<Long?>()
        val callback = RingGattCallback(mac, result)
        val gatt = runCatching {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("connectGatt failed"))

        try {
            // null on timeout (connected but silent) or on an internal give-up.
            Result.success(withTimeoutOrNull(READ_TIMEOUT_MS) { result.await() })
        } finally {
            // Mirror WatchLink's teardown: disconnect before close, or short-lived connects can
            // leak GATT client registrations on some stacks.
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    /** GATT state machine: enable notify -> status request -> auth -> descriptor prompt -> parse. */
    @SuppressLint("MissingPermission")
    private inner class RingGattCallback(
        private val mac: ByteArray,
        private val result: CompletableDeferred<Long?>,
    ) : BluetoothGattCallback() {

        /**
         * The command to send once the in-flight write is acked. Android's BluetoothGatt allows
         * only ONE outstanding write; a second writeCharacteristic before the ack is rejected,
         * not queued (see WatchLink's busy-retry note), so the auth-response and descriptor-prompt
         * writes must be sequenced through [onCharacteristicWrite].
         */
        @Volatile
        private var nextWrite: ByteArray? = null

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> result.complete(null)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                result.complete(null)
                return
            }
            val notify = gatt.getService(SERVICE)?.getCharacteristic(NOTIFY_CHAR)
            val cccd = notify?.getDescriptor(CCCD)
            if (notify == null || cccd == null) {
                result.complete(null)
                return
            }
            gatt.setCharacteristicNotification(notify, true)
            writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // CCCD written -> request the status/challenge frame.
            write(gatt, CMD_STATUS)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handle(gatt, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handle(gatt, value)
        }

        private fun handle(gatt: BluetoothGatt, value: ByteArray?) {
            if (value == null || value.isEmpty()) return
            val steps = RingConnDescriptor.parseSteps(value)
            if (steps != null) {
                result.complete(steps)
                return
            }
            // Challenge frame `81 00 <challenge> <xor>` -> answer it, then prompt a descriptor.
            val isChallenge = value.size >= CHALLENGE_MIN_LENGTH &&
                (value[0].toInt() and BYTE_MASK) == CHALLENGE_ID &&
                value[1].toInt() == 0
            if (isChallenge) {
                nextWrite = CMD_DESCRIPTOR // sent from onCharacteristicWrite once the auth write acks
                write(gatt, RingAuth.authCommand(value[CHALLENGE_INDEX].toInt() and BYTE_MASK, mac))
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val queued = nextWrite ?: return
            nextWrite = null
            write(gatt, queued)
        }

        /** One command write, across the SDK's pre-/post-Tiramisu API split (mirrors WatchLink). */
        private fun write(gatt: BluetoothGatt, bytes: ByteArray) {
            val char = gatt.getService(SERVICE)?.getCharacteristic(WRITE_CHAR) ?: return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        char.value = bytes
                        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        gatt.writeCharacteristic(char)
                    }
                }
            }
        }

        private fun writeDescriptor(gatt: BluetoothGatt, cccd: BluetoothGattDescriptor, bytes: ByteArray) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, bytes)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = bytes
                        gatt.writeDescriptor(cccd)
                    }
                }
            }
        }
    }

    private fun macBytes(address: String): ByteArray? = runCatching {
        address.split(":").map { it.toInt(HEX_RADIX).toByte() }.toByteArray().takeIf { it.size == MAC_LENGTH }
    }.getOrNull()

    private companion object {
        val SERVICE: UUID = UUID.fromString("8327ad99-2d87-4a22-a8ce-6dd7971c0437")
        val WRITE_CHAR: UUID = UUID.fromString("8327ad98-2d87-4a22-a8ce-6dd7971c0437")
        val NOTIFY_CHAR: UUID = UUID.fromString("8327ad97-2d87-4a22-a8ce-6dd7971c0437")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** `01 00 00`: status request; the ring answers with the `81` challenge frame. */
        val CMD_STATUS = byteArrayOf(0x01, 0x00, 0x00)

        /** `d0 00 00`: prompt an immediate `10` status descriptor (instead of waiting ~30-60 s). */
        val CMD_DESCRIPTOR = byteArrayOf(0xd0.toByte(), 0x00, 0x00)

        const val READ_TIMEOUT_MS = 4_000L
        const val CHALLENGE_ID = 0x81
        const val CHALLENGE_MIN_LENGTH = 4
        const val CHALLENGE_INDEX = 2
        const val BYTE_MASK = 0xFF
        const val MAC_LENGTH = 6
        const val HEX_RADIX = 16
    }
}
