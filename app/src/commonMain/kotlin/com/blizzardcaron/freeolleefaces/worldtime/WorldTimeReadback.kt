package com.blizzardcaron.freeolleefaces.worldtime

import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol

/**
 * Reads the watch's current World Time offset: request `0x35`, await the `0x55` weekday-table
 * reply, decode its 4-byte header (see [WorldTimeCodec]). Returns null on timeout / link loss
 * or a malformed payload. Mirrors [com.blizzardcaron.freeolleefaces.ble.BatteryReadback].
 */
object WorldTimeReadback {

    suspend fun read(ble: BleClient, address: String): Int? {
        val reply = ble.sendAndAwait(
            address,
            OlleeProtocol.readRequest(OlleeProtocol.TARGET_GET_WEEKDAYS),
            OlleeProtocol.TARGET_GET_WEEKDAYS + OlleeProtocol.RESPONSE_TARGET_OFFSET,
        )
        return reply.getOrNull()?.let { parseOffsetSec(it) }
    }

    /** Decodes the header offset from a `0x55` reply; null for wrong target/CRC/short payload. */
    fun parseOffsetSec(frame: OlleeProtocol.Frame): Int? {
        val expectedTarget = OlleeProtocol.TARGET_GET_WEEKDAYS + OlleeProtocol.RESPONSE_TARGET_OFFSET
        if (frame.crcOk && frame.target == expectedTarget && frame.payload.size >= WorldTimeCodec.HEADER_SIZE) {
            return WorldTimeCodec.decode(frame.payload.copyOfRange(0, WorldTimeCodec.HEADER_SIZE))
        }
        return null
    }
}
