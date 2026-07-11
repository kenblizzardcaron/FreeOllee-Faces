package com.blizzardcaron.freeolleefaces.devtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.blizzardcaron.freeolleefaces.ble.AndroidBleClient
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Developer bench tool: writes one framed packet to the paired watch over the normal
 * [AndroidBleClient] path (which already connects to the bonded device — the same reason on-watch
 * faces work from the phone but not from an unbonded host). Kept in the tree as a reusable BLE
 * probe for reverse-engineering watch targets; **inert unless the build is debuggable**, so it is
 * a no-op in release.
 *
 * Driven from adb (action + extras). The watch address comes from `--es watch <MAC>` if given,
 * else [Prefs.watchAddress]; results and the exact TX bytes are logged under tag `OLLEE_DEV`.
 *
 *   # raw, fully-framed bytes (CRC already correct):
 *   adb shell am broadcast -a com.blizzardcaron.freeolleefaces.DEV_SEND \
 *       -f 0x01000000 --es watch 00:80:E1:26:DC:86 \
 *       --es frame 0013AA55525D02250000000D1E00050501C0FF0FFF
 *
 *   # target + payload hex — CRC/LEN computed here via buildRawPacket:
 *   adb shell am broadcast -a com.blizzardcaron.freeolleefaces.DEV_SEND \
 *       -f 0x01000000 --es target 25 --es payload 0000000D1E00050501C0FF0FFF
 *
 *   # read a register: send an empty-payload request and await the target+0x20 notify reply
 *   # (frame is logged on OLLEE_BLE by WatchLink; the parsed payload on OLLEE_DEV):
 *   adb shell am broadcast -a com.blizzardcaron.freeolleefaces.DEV_SEND \
 *       -f 0x01000000 --es watch 00:80:E1:26:DC:86 --es read 35
 */
class DevToolsReceiver : BroadcastReceiver() {

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    // ReturnCount: debug-only adb probe with sequential input-validation guards, each with a
    // distinct diagnostic — early returns are the clearest form for a dev probe.
    // TooGenericExceptionCaught: buildPacket parses arbitrary adb-supplied hex/int extras, so it
    // must treat ANY malformed input (NumberFormatException, IllegalArgumentException from
    // require(), NPE from !!, etc.) as "bad request"; narrowing the catch would let some
    // malformed input crash the receiver.
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        if ((ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return

        // Prefer an explicit `--es watch <MAC>` so the probe works without configuring the app;
        // fall back to the app's saved watch.
        val address = intent.getStringExtra("watch") ?: Prefs(appSettings(ctx)).watchAddress
        if (address == null) {
            Log.w(TAG, "no watch address — pass --es watch <MAC> or set the watch in the app first")
            return
        }

        if (intent.hasExtra("read")) {
            val target = try {
                intent.getStringExtra("read")!!.toInt(HEX_RADIX)
            } catch (e: Exception) {
                Log.e(TAG, "bad read target: ${e.message}")
                return
            }
            readRegister(ctx, address, target)
            return
        }

        val packet = try {
            buildPacket(intent)
        } catch (e: Exception) {
            Log.e(TAG, "bad request: ${e.message}")
            return
        }
        if (packet == null) {
            Log.w(TAG, "nothing to send — provide --es frame or --es payload")
            return
        }

        Log.i(TAG, "TX ${packet.size} ${packet.toHex()} -> $address")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = AndroidBleClient(ctx).sendPacket(address, packet)
                Log.i(
                    TAG,
                    if (result.isSuccess) {
                        "result: OK"
                    } else {
                        "result: FAIL ${result.exceptionOrNull()?.message}"
                    },
                )
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Sends an empty-payload read request for [target] and awaits the `target + 0x20` notify
     * reply. The raw frame is logged on OLLEE_BLE by WatchLink; the parsed payload here.
     */
    private fun readRegister(ctx: Context, address: String, target: Int) {
        val request = OlleeProtocol.readRequest(target)
        Log.i(TAG, "READ 0x${target.toString(HEX_RADIX)} ${request.toHex()} -> $address")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = AndroidBleClient(ctx).sendAndAwait(
                    address,
                    request,
                    target + OlleeProtocol.RESPONSE_TARGET_OFFSET,
                )
                result.onSuccess { f ->
                    Log.i(
                        TAG,
                        "read 0x${target.toString(HEX_RADIX)}: " +
                            "payload=${f.payload.toHex()} crcOk=${f.crcOk}",
                    )
                }.onFailure { Log.e(TAG, "read 0x${target.toString(HEX_RADIX)} failed: ${it.message}") }
            } finally {
                pending.finish()
            }
        }
    }

    /** Returns the framed bytes for whichever extras were supplied, or null if none were. */
    private fun buildPacket(intent: Intent): ByteArray? = when {
        intent.hasExtra("frame") ->
            intent.getStringExtra("frame")!!.hexToBytes()

        intent.hasExtra("payload") -> {
            val target = (intent.getStringExtra("target") ?: "25").toInt(HEX_RADIX)
            OlleeProtocol.buildRawPacket(target, intent.getStringExtra("payload")!!.hexToBytes())
        }

        else -> null
    }

    private fun String.hexToBytes(): ByteArray {
        val clean = filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "hex must have an even length" }
        return clean.chunked(2).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        const val TAG = "OLLEE_DEV"
        const val ACTION = "com.blizzardcaron.freeolleefaces.DEV_SEND"
        private const val HEX_RADIX = 16
    }
}
