package com.blizzardcaron.freeolleefaces.ring

/**
 * Pure parser for the RingConn Gen2 status frames. Three step-bearing frames exist, all
 * XOR-trailed and btsnoop-verified on real Gen2 hardware (see
 * plans/2026-07-02-ringconn-steps-design.md and the Task 7 capture):
 *  - `0x10` — 19-byte descriptor the ring emits spontaneously every ~30-60 s; steps at [4:6] BE.
 *  - `0x87` — same body, in reply to `07 00 00`.
 *  - `0x81 0x01` — 38-byte full-status reply to the auth command; same payload shifted by the
 *    subtype/pad header, steps at [6:8] BE. This is the frame a short-lived connection actually
 *    receives: the ring ignores `d0 00 00` prompts from our session, but answers auth instantly.
 */
object RingConnDescriptor {

    private const val ID_SPONTANEOUS = 0x10
    private const val ID_FETCH_REPLY = 0x87
    private const val ID_AUTH_STATUS = 0x81
    private const val AUTH_STATUS_SUBTYPE = 0x01
    private const val MIN_LENGTH = 6
    private const val AUTH_MIN_LENGTH = 9
    private const val STEP_HI = 4
    private const val AUTH_STEP_OFFSET = 2
    private const val BYTE_MASK = 0xFF
    private const val BYTE_SHIFT = 8

    /** The ring's onboard step count, or null if [frame] is not a valid status frame. */
    fun parseSteps(frame: ByteArray): Long? {
        val stepHi = stepOffsetOrNull(frame)
        if (stepHi == null || !xorValid(frame)) {
            return null
        }
        val hi = frame[stepHi].toInt() and BYTE_MASK
        val lo = frame[stepHi + 1].toInt() and BYTE_MASK
        return ((hi shl BYTE_SHIFT) or lo).toLong()
    }

    /**
     * Step-count offset for [frame]'s response id, or null if it is not a step-bearing frame of
     * sufficient length. `81 01 …` is the full-status auth reply; `81 00 …` is the short
     * challenge frame, never steps.
     */
    private fun stepOffsetOrNull(frame: ByteArray): Int? {
        if (frame.size < MIN_LENGTH) return null
        return when (frame[0].toInt() and BYTE_MASK) {
            ID_SPONTANEOUS, ID_FETCH_REPLY -> STEP_HI
            ID_AUTH_STATUS ->
                (STEP_HI + AUTH_STEP_OFFSET).takeIf {
                    frame.size >= AUTH_MIN_LENGTH &&
                        (frame[1].toInt() and BYTE_MASK) == AUTH_STATUS_SUBTYPE
                }
            else -> null
        }
    }

    /** Trailer (last byte) is the XOR of all preceding bytes. */
    private fun xorValid(frame: ByteArray): Boolean {
        var x = 0
        for (i in 0 until frame.size - 1) x = x xor (frame[i].toInt() and BYTE_MASK)
        return (x and BYTE_MASK) == (frame[frame.size - 1].toInt() and BYTE_MASK)
    }
}
