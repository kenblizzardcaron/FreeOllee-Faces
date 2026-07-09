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
import android.os.Handler
import android.os.Looper
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.UUID

/**
 * Reads the ring's step total for today by draining its stored activity records over a
 * short-lived GATT connection: connect -> enable notify -> auth handshake -> replay of every
 * un-acked record -> ack each frame -> sum today's buckets. Separate from the watch's
 * [com.blizzardcaron.freeolleefaces.ble.WatchLink] singleton (different device). Never throws;
 * every fault becomes Result.failure, and a connected-but-silent cycle falls back to the
 * persisted running total (or success(null) before any record was ever seen).
 *
 * Protocol (btsnoop-verified on Gen2, see plans/2026-07-03-ringconn-records-spike.md):
 * CCCD <- `01 00`; write `01 00 00`; notify `81 00 <challenge> <xor>`; write
 * `01 01 <r0 r1 r2> 00` ([RingAuth.authCommand]); then mimic the official app's post-auth
 * sequence (`d0 00 00`, time-sync `02 00 <now> 00 01 00`, poll `07 00 00`). The ring replays
 * every record since the last acked frame as `4c`/`47` notifies ([RingRecords.parse]); the
 * buckets are persisted into [RingDailySteps], whose watermark makes re-replays idempotent.
 * A frame with remaining == 0 plus a quiet gap ends the read.
 *
 * ACK POLICY (hardware-verified 2026-07-08): the ring keeps ONE shared replay cursor per
 * record stream, not one per client — acking `4c` frames permanently consumed the day's
 * activity records before the official app could sync them. But a fully silent client stalls:
 * the ring re-sends the pending `47` wellness frame every session and never opens the `4c`
 * stream until it is acked. So we ack every stream EXCEPT `4c` (`c7`/`91`, never `cc`):
 * activity records replay to us idempotently ([RingDailySteps]'s watermark dedupes) and the
 * official app keeps receiving them; only its wellness records are consumed by us, which its
 * sleep/vitals scores demonstrably survive.
 */
class AndroidRingStepsSource(
    context: Context,
    private val addressProvider: () -> String?,
) : RingStepsSource {

    private val appContext = context.applicationContext
    private val dailySteps by lazy { RingDailySteps(appSettings(appContext)) }

    // Process-wide readMutex: the foreground refresh and the AutoUpdateWorker each construct
    // their own source, and two concurrent connectGatt() calls to the same peripheral are a
    // classic status-133 hazard. Serialize every ring read across the process.
    @SuppressLint("MissingPermission")
    override suspend fun readSteps(): Result<Long?> = withContext(Dispatchers.IO) {
        readMutex.withLock {
            val address = addressProvider()
                ?: return@withLock Result.failure(IllegalStateException("no ring selected"))
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter
                ?: return@withLock Result.failure(IllegalStateException("no bluetooth adapter"))
            val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
                ?: return@withLock Result.failure(IllegalStateException("bad ring address"))
            val mac = macBytes(address)
                ?: return@withLock Result.failure(IllegalStateException("bad ring address"))

            val result = CompletableDeferred<Long?>()
            val callback = RingGattCallback(mac, result)
            val gatt = runCatching {
                device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            }.getOrNull()
                ?: return@withLock Result.failure(IllegalStateException("connectGatt failed"))

            try {
                val value = withTimeoutOrNull(READ_TIMEOUT_MS) { result.await() }
                // A timeout mid-replay still banked every acked frame; surface the running total.
                Result.success(value ?: if (callback.sawRecords) dailySteps.today() else null)
            } finally {
                // Mirror WatchLink's teardown: disconnect before close, or short-lived connects
                // can leak GATT client registrations on some stacks.
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
        }
    }

    /** GATT state machine: enable notify -> status request -> auth -> record replay -> sum. */
    @SuppressLint("MissingPermission")
    private inner class RingGattCallback(
        private val mac: ByteArray,
        private val result: CompletableDeferred<Long?>,
    ) : BluetoothGattCallback() {

        /**
         * Android's BluetoothGatt allows only ONE outstanding write; a second
         * writeCharacteristic before the ack is rejected, not queued (see WatchLink's
         * busy-retry note). Acks can be produced faster than write-acks return during the
         * replay burst, so commands go through this queue, drained by [onCharacteristicWrite].
         */
        private val writeQueue = ArrayDeque<ByteArray>()
        private var writeInFlight = false

        @Volatile
        var sawRecords = false
            private set

        /** True once a `4c` frame reported remaining == 0: the activity stream is drained. */
        @Volatile
        private var activityDrained = false

        private val quietHandler = Handler(Looper.getMainLooper())
        private val quietRunnable = Runnable { result.complete(dailySteps.today()) }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    quietHandler.removeCallbacks(quietRunnable)
                    result.complete(if (sawRecords) dailySteps.today() else null)
                }
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
            enqueueWrite(gatt, CMD_STATUS)
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
            val id = value[0].toInt() and BYTE_MASK
            val recordFrame = RingRecords.parse(value)
            // Status frames (`10`/`87`/`81 01`) fall through unhandled: their step field is a
            // live activity-bout counter, not a daily total.
            when {
                recordFrame != null -> onRecordFrame(gatt, recordFrame, id)
                // `11` event frames: ack so the ring proceeds past them (see the class doc).
                id == EVENT_FRAME_ID -> enqueueWrite(gatt, RingRecords.ackCommand(id))
                isChallenge(id, value) -> onChallenge(gatt, value)
            }
        }

        private fun isChallenge(id: Int, value: ByteArray): Boolean =
            value.size >= CHALLENGE_MIN_LENGTH && id == CHALLENGE_ID && value[1].toInt() == 0

        /** Answer the `81 00 <challenge> <xor>` frame, then open the record tap like the app. */
        private fun onChallenge(gatt: BluetoothGatt, value: ByteArray) {
            enqueueWrite(gatt, RingAuth.authCommand(value[CHALLENGE_INDEX].toInt() and BYTE_MASK, mac))
            enqueueWrite(gatt, CMD_DESCRIPTOR)
            enqueueWrite(gatt, timeSyncCommand())
            enqueueWrite(gatt, CMD_POLL)
            armQuietTimer(REPLAY_WAIT_MS)
        }

        /**
         * Persist the frame's buckets. Ack every stream EXCEPT `4c` activity (see the class
         * doc): a `cc` ack would consume the activity records away from the official app.
         */
        private fun onRecordFrame(gatt: BluetoothGatt, frame: RingRecordFrame, id: Int) {
            sawRecords = true
            for (record in frame.activityRecords) {
                dailySteps.record(record.unixSeconds, record.steps, countable = !record.sleepFlagged)
            }
            if (id == RingRecords.FRAME_ID_ACTIVITY) {
                if (frame.remaining == 0) activityDrained = true
            } else {
                enqueueWrite(gatt, RingRecords.ackCommand(id))
            }
            armQuietTimer(if (activityDrained) DRAINED_QUIET_MS else REPLAY_WAIT_MS)
        }

        /** Completes the read once the ring has been quiet for [delayMs] (replay finished). */
        private fun armQuietTimer(delayMs: Long) {
            quietHandler.removeCallbacks(quietRunnable)
            quietHandler.postDelayed(quietRunnable, delayMs)
        }

        private fun enqueueWrite(gatt: BluetoothGatt, bytes: ByteArray) {
            val sendNow = synchronized(writeQueue) {
                if (writeInFlight) {
                    writeQueue.add(bytes)
                    null
                } else {
                    writeInFlight = true
                    bytes
                }
            }
            if (sendNow != null) write(gatt, sendNow)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val next = synchronized(writeQueue) {
                val queued = writeQueue.poll()
                writeInFlight = queued != null
                queued
            }
            if (next != null) write(gatt, next)
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

    /**
     * `02 00 <ringTs:u32-be> 00 01 00`: time-sync, exactly as the official app sends it each
     * session (ts = now in seconds since 2020-01-01 00:00:00 UTC+8). Keeps the ring clock from
     * drifting now that the official app no longer connects; the ring replies `82 00 00`.
     */
    private fun timeSyncCommand(): ByteArray {
        val ringTs = System.currentTimeMillis() / MS_PER_SECOND - RingRecords.TIMESTAMP_OFFSET_UNIX
        return byteArrayOf(
            0x02, 0x00,
            (ringTs ushr TS_SHIFT_3).toByte(),
            (ringTs ushr TS_SHIFT_2).toByte(),
            (ringTs ushr TS_SHIFT_1).toByte(),
            ringTs.toByte(),
            0x00, 0x01, 0x00,
        )
    }

    private fun macBytes(address: String): ByteArray? = runCatching {
        address.split(":").map { it.toInt(HEX_RADIX).toByte() }.toByteArray().takeIf { it.size == MAC_LENGTH }
    }.getOrNull()

    private companion object {
        /** Serializes ring reads process-wide (foreground refresh vs AutoUpdateWorker). */
        val readMutex = Mutex()

        val SERVICE: UUID = UUID.fromString("8327ad99-2d87-4a22-a8ce-6dd7971c0437")
        val WRITE_CHAR: UUID = UUID.fromString("8327ad98-2d87-4a22-a8ce-6dd7971c0437")
        val NOTIFY_CHAR: UUID = UUID.fromString("8327ad97-2d87-4a22-a8ce-6dd7971c0437")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** `01 00 00`: status request; the ring answers with the `81` challenge frame. */
        val CMD_STATUS = byteArrayOf(0x01, 0x00, 0x00)

        /** `d0 00 00`: sent by the official app right after auth; no reply observed. */
        val CMD_DESCRIPTOR = byteArrayOf(0xd0.toByte(), 0x00, 0x00)

        /** `07 00 00`: status poll (reply `87`); part of the official app's post-auth sequence. */
        val CMD_POLL = byteArrayOf(0x07, 0x00, 0x00)

        /** Bounds the whole read; a 14 h backlog replayed in ~2 s, so this is generous. */
        const val READ_TIMEOUT_MS = 20_000L

        /** Max gap to wait for (more of) the replay before concluding nothing is coming. */
        const val REPLAY_WAIT_MS = 2_500L

        /** Quiet gap after a remaining == 0 frame; replay frames arrive milliseconds apart. */
        const val DRAINED_QUIET_MS = 800L

        const val EVENT_FRAME_ID = 0x11
        const val CHALLENGE_ID = 0x81
        const val CHALLENGE_MIN_LENGTH = 4
        const val CHALLENGE_INDEX = 2
        const val BYTE_MASK = 0xFF
        const val MAC_LENGTH = 6
        const val HEX_RADIX = 16
        const val MS_PER_SECOND = 1_000L
        const val TS_SHIFT_1 = 8
        const val TS_SHIFT_2 = 16
        const val TS_SHIFT_3 = 24
    }
}
