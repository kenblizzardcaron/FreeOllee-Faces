package com.blizzardcaron.freeolleefaces.ring

/**
 * Pure parser for the RingConn Gen2 status descriptor (the 19-byte frame the ring emits
 * spontaneously ~30-60 s while connected, and in reply to `d0 00 00` / `07 00 00`). Response id
 * `0x10` (spontaneous / `d0` reply) or `0x87` (reply to `07 00 00`); identical body. Step count
 * is the ring's onboard daily total at bytes [4:6], 16-bit big-endian. Byte-offsets and framing
 * are btsnoop-verified on real Gen2 hardware (see plans/2026-07-02-ringconn-steps-design.md).
 */
object RingConnDescriptor {

    private const val ID_SPONTANEOUS = 0x10
    private const val ID_FETCH_REPLY = 0x87
    private const val MIN_LENGTH = 6
    private const val STEP_HI = 4
    private const val STEP_LO = 5
    private const val BYTE_MASK = 0xFF
    private const val BYTE_SHIFT = 8

    /** The ring's onboard step count, or null if [frame] is not a valid status descriptor. */
    fun parseSteps(frame: ByteArray): Long? {
        if (frame.size < MIN_LENGTH || !isValidId(frame[0]) || !xorValid(frame)) {
            return null
        }
        val hi = frame[STEP_HI].toInt() and BYTE_MASK
        val lo = frame[STEP_LO].toInt() and BYTE_MASK
        return ((hi shl BYTE_SHIFT) or lo).toLong()
    }

    private fun isValidId(byte: Byte): Boolean {
        val id = byte.toInt() and BYTE_MASK
        return id == ID_SPONTANEOUS || id == ID_FETCH_REPLY
    }

    /** Trailer (last byte) is the XOR of all preceding bytes. */
    private fun xorValid(frame: ByteArray): Boolean {
        var x = 0
        for (i in 0 until frame.size - 1) x = x xor (frame[i].toInt() and BYTE_MASK)
        return (x and BYTE_MASK) == (frame[frame.size - 1].toInt() and BYTE_MASK)
    }
}
