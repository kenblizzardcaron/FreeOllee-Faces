package com.blizzardcaron.freeolleefaces.worldtime

import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol

/**
 * Builder for the 02 23 set-clock frame, which writes the watch clock to the wall time of
 * UTC+[offsetSec] at instant [nowMs], and carries phone coordinates for the Sun & Moon face.
 */
object SetClock {
    // Captured constants of unconfirmed meaning; replayed per on-device verification.
    private const val CONSTANT_FLAG = 0x0003
    private const val CONSTANT_WORD = 0xFFFF

    // Byte manipulation constants
    private const val BYTE_MASK = 0xFF
    private const val SHIFT_BYTE_1 = 8
    private const val SHIFT_BYTE_2 = 16
    private const val SHIFT_BYTE_3 = 24

    // Payload field offsets
    private const val NOW_SEC_OFFSET = 0
    private const val OFFSET_SEC_OFFSET = 4
    private const val LAT_E3_OFFSET = 8
    private const val LON_E3_OFFSET = 12
    private const val CONSTANT_FLAG_OFFSET = 16
    private const val CONSTANT_WORD_OFFSET = 18
    private const val PAYLOAD_SIZE = 20

    // Byte indices within each 4-byte int field
    private const val BYTE_INDEX_0 = 0
    private const val BYTE_INDEX_1 = 1
    private const val BYTE_INDEX_2 = 2
    private const val BYTE_INDEX_3 = 3

    // Time conversion
    private const val MILLIS_PER_SECOND = 1000

    /**
     * Builds a set-clock frame for the given time and coordinates.
     *
     * @param nowMs milliseconds since Unix epoch (the instant to write to the watch)
     * @param offsetSec signed offset from UTC in seconds (e.g., -21600 for UTC-6)
     * @param latE3 latitude in degrees × 1000, truncated toward zero (e.g., 40140 for 40.140°)
     * @param lonE3 longitude in degrees × 1000, truncated toward zero (e.g., -105144 for -105.144°)
     * @return the complete framed packet (00 LEN AA 55 CRC16 02 23 payload)
     */
    fun build(nowMs: Long, offsetSec: Int, latE3: Int, lonE3: Int): ByteArray {
        val nowSec = (nowMs / MILLIS_PER_SECOND).toInt() // convert to unsigned 32-bit seconds
        val payload = ByteArray(PAYLOAD_SIZE)

        // [0:4] nowMs / 1000 as LE uint32
        payload[NOW_SEC_OFFSET + BYTE_INDEX_0] = (nowSec and BYTE_MASK).toByte()
        payload[NOW_SEC_OFFSET + BYTE_INDEX_1] = ((nowSec shr SHIFT_BYTE_1) and BYTE_MASK).toByte()
        payload[NOW_SEC_OFFSET + BYTE_INDEX_2] = ((nowSec shr SHIFT_BYTE_2) and BYTE_MASK).toByte()
        payload[NOW_SEC_OFFSET + BYTE_INDEX_3] = ((nowSec shr SHIFT_BYTE_3) and BYTE_MASK).toByte()

        // [4:8] offsetSec as LE int32 (signed, two's complement via shr)
        payload[OFFSET_SEC_OFFSET + BYTE_INDEX_0] = (offsetSec and BYTE_MASK).toByte()
        payload[OFFSET_SEC_OFFSET + BYTE_INDEX_1] = ((offsetSec shr SHIFT_BYTE_1) and BYTE_MASK).toByte()
        payload[OFFSET_SEC_OFFSET + BYTE_INDEX_2] = ((offsetSec shr SHIFT_BYTE_2) and BYTE_MASK).toByte()
        payload[OFFSET_SEC_OFFSET + BYTE_INDEX_3] = ((offsetSec shr SHIFT_BYTE_3) and BYTE_MASK).toByte()

        // [8:12] latE3 as LE int32 (signed)
        payload[LAT_E3_OFFSET + BYTE_INDEX_0] = (latE3 and BYTE_MASK).toByte()
        payload[LAT_E3_OFFSET + BYTE_INDEX_1] = ((latE3 shr SHIFT_BYTE_1) and BYTE_MASK).toByte()
        payload[LAT_E3_OFFSET + BYTE_INDEX_2] = ((latE3 shr SHIFT_BYTE_2) and BYTE_MASK).toByte()
        payload[LAT_E3_OFFSET + BYTE_INDEX_3] = ((latE3 shr SHIFT_BYTE_3) and BYTE_MASK).toByte()

        // [12:16] lonE3 as LE int32 (signed)
        payload[LON_E3_OFFSET + BYTE_INDEX_0] = (lonE3 and BYTE_MASK).toByte()
        payload[LON_E3_OFFSET + BYTE_INDEX_1] = ((lonE3 shr SHIFT_BYTE_1) and BYTE_MASK).toByte()
        payload[LON_E3_OFFSET + BYTE_INDEX_2] = ((lonE3 shr SHIFT_BYTE_2) and BYTE_MASK).toByte()
        payload[LON_E3_OFFSET + BYTE_INDEX_3] = ((lonE3 shr SHIFT_BYTE_3) and BYTE_MASK).toByte()

        // [16:18] CONSTANT_FLAG (0x0003) as LE
        payload[CONSTANT_FLAG_OFFSET + BYTE_INDEX_0] = (CONSTANT_FLAG and BYTE_MASK).toByte()
        payload[CONSTANT_FLAG_OFFSET + BYTE_INDEX_1] = ((CONSTANT_FLAG shr SHIFT_BYTE_1) and BYTE_MASK).toByte()

        // [18:20] CONSTANT_WORD (0xFFFF)
        payload[CONSTANT_WORD_OFFSET + BYTE_INDEX_0] = (CONSTANT_WORD and BYTE_MASK).toByte()
        payload[CONSTANT_WORD_OFFSET + BYTE_INDEX_1] = ((CONSTANT_WORD shr SHIFT_BYTE_1) and BYTE_MASK).toByte()

        return OlleeProtocol.buildRawPacket(OlleeProtocol.TARGET_SET_CLOCK, payload)
    }
}
