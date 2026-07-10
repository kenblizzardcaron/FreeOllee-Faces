package com.blizzardcaron.freeolleefaces.worldtime

/**
 * Encodes/decodes the 4-byte header of the weekday-table register (`0x34` write / `0x35` read),
 * hypothesized to be the World Time face's UTC offset in big-endian two's-complement seconds
 * (pending on-device confirmation — plans/2026-07-09-world-time-plan.md Task 10). The 2026-05-31
 * capture constant `00 00 7E 90` decodes to +32,400 s = +9:00; big-endian matches the config
 * register (`0x32`/`0x33`) convention. This is the only file that knows the encoding.
 */
object WorldTimeCodec {
    const val HEADER_SIZE = 4

    private const val SECONDS_PER_HOUR = 3600
    private const val MAX_OFFSET_HOURS = 18 // ISO 8601 offset bound

    /** Largest plausible UTC offset magnitude; anything beyond it is not an offset. */
    const val MAX_OFFSET_SEC = MAX_OFFSET_HOURS * SECONDS_PER_HOUR

    private const val BYTE_MASK = 0xFF
    private const val SHIFT_24 = 24
    private const val SHIFT_16 = 16
    private const val SHIFT_8 = 8
    private const val BYTE_0 = 0
    private const val BYTE_1 = 1
    private const val BYTE_2 = 2
    private const val BYTE_3 = 3

    /** The 4-byte big-endian two's-complement encoding of [offsetSeconds]. */
    fun encode(offsetSeconds: Int): ByteArray {
        require(offsetSeconds in -MAX_OFFSET_SEC..MAX_OFFSET_SEC) {
            "offset must be within ±$MAX_OFFSET_SEC seconds (got $offsetSeconds)"
        }
        return byteArrayOf(
            ((offsetSeconds shr SHIFT_24) and BYTE_MASK).toByte(),
            ((offsetSeconds shr SHIFT_16) and BYTE_MASK).toByte(),
            ((offsetSeconds shr SHIFT_8) and BYTE_MASK).toByte(),
            (offsetSeconds and BYTE_MASK).toByte(),
        )
    }

    /** Decodes [header] to signed offset seconds; null on wrong size or implausible value. */
    fun decode(header: ByteArray): Int? {
        if (header.size != HEADER_SIZE) return null
        val value = (header[BYTE_0].toInt() shl SHIFT_24) or
            ((header[BYTE_1].toInt() and BYTE_MASK) shl SHIFT_16) or
            ((header[BYTE_2].toInt() and BYTE_MASK) shl SHIFT_8) or
            (header[BYTE_3].toInt() and BYTE_MASK)
        return value.takeIf { it in -MAX_OFFSET_SEC..MAX_OFFSET_SEC }
    }
}
