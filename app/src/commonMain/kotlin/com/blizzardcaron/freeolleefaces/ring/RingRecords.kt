package com.blizzardcaron.freeolleefaces.ring

/** One 2.5-minute activity bucket from a `4c` record frame. */
data class RingActivityRecord(
    val unixSeconds: Long,
    val steps: Int,
    val sleepFlagged: Boolean,
)

/** One parsed record frame. For `47` (wellness) frames [activityRecords] is empty. */
data class RingRecordFrame(
    val frameId: Int,
    val remaining: Int,
    val activityRecords: List<RingActivityRecord>,
)

/**
 * Pure parser for RingConn Gen2 record frames. Two record types exist, both XOR-trailed and
 * btsnoop-verified:
 *  - `0x4c` — activity records (19-byte body per record, steps at [14], sleepFlagged when
 *    body[6:11] are all 0x01)
 *  - `0x47` — wellness records (43-byte body, skipped; only frameId and remaining parsed)
 * Timestamp for both: u32 big-endian offset from 2020-01-01 00:00:00 UTC+8.
 */
object RingRecords {

    /** Frame id of the step-bearing activity record stream. */
    const val FRAME_ID_ACTIVITY = 0x4c

    private const val FRAME_ID_WELLNESS = 0x47
    private const val REQUIRED_BYTE_1 = 0x00
    private const val ACTIVITY_RECORD_LEN = 23
    private const val WELLNESS_RECORD_LEN = 47
    private const val MIN_FRAME_LEN = 5 // at least frame_id + 0x00 + remaining + 1 record + trailer
    private const val HEADER_LEN = 3 // frame_id + 0x00 + remaining
    private const val TRAILER_LEN = 1
    private const val TIMESTAMP_LEN = 4
    private const val STEPS_OFFSET_IN_BODY = 14
    private const val SLEEP_CHECK_START = 6
    private const val SLEEP_CHECK_END = 11 // exclusive
    private const val SLEEP_FLAG_VALUE = 0x01

    /** Ring timestamps are seconds since 2020-01-01 00:00:00 UTC+8; add this for unix seconds. */
    const val TIMESTAMP_OFFSET_UNIX = 1_577_808_000L

    private const val BYTE_MASK = 0xFF
    private const val BYTE_MASK_LONG = 0xFFL
    private const val BYTE_SHIFT = 8
    private const val ACK_MODE_BIT = 0x80
    private const val TIMESTAMP_OFFSET_0 = 0
    private const val TIMESTAMP_OFFSET_1 = 1
    private const val TIMESTAMP_OFFSET_2 = 2
    private const val TIMESTAMP_OFFSET_3 = 3

    /**
     * Parse a record frame, or null if invalid (bad frame id, bad length, bad XOR trailer,
     * or insufficient records). Never throws on arbitrary input.
     */
    fun parse(frame: ByteArray): RingRecordFrame? {
        if (!isValidFrameStructure(frame)) return null

        val frameId = frame[0].toInt() and BYTE_MASK
        val remaining = frame[2].toInt() and BYTE_MASK
        val recordLen = if (frameId == FRAME_ID_ACTIVITY) ACTIVITY_RECORD_LEN else WELLNESS_RECORD_LEN
        val recordCount = (frame.size - HEADER_LEN - TRAILER_LEN) / recordLen
        val activityRecords = if (frameId == FRAME_ID_ACTIVITY) {
            parseActivityRecords(frame, recordCount)
        } else {
            emptyList()
        }
        return RingRecordFrame(frameId, remaining, activityRecords)
    }

    private fun isValidFrameStructure(frame: ByteArray): Boolean {
        val sizValid = frame.size >= MIN_FRAME_LEN && xorValid(frame)
        if (!sizValid) return false

        val frameId = frame[0].toInt() and BYTE_MASK
        val frameIdValid = frameId == FRAME_ID_ACTIVITY || frameId == FRAME_ID_WELLNESS
        val byte1Valid = (frame[1].toInt() and BYTE_MASK) == REQUIRED_BYTE_1

        val recordLen = if (frameId == FRAME_ID_ACTIVITY) ACTIVITY_RECORD_LEN else WELLNESS_RECORD_LEN
        val recordCount = (frame.size - HEADER_LEN - TRAILER_LEN) / recordLen
        val lengthValid = recordCount >= 1 && HEADER_LEN + recordCount * recordLen + TRAILER_LEN == frame.size

        return frameIdValid && byte1Valid && lengthValid
    }

    /**
     * Build an ACK command for the given frame id, i.e. `<id|0x80> 00 00` (`47` -> `c7 00 00`).
     * Acking advances the ring's shared per-stream replay cursor: required on `47`/`11` to
     * unblock the session, deliberately NEVER sent for `4c` activity frames (see
     * AndroidRingStepsSource's ack policy).
     */
    fun ackCommand(frameId: Int): ByteArray {
        return byteArrayOf(
            ((frameId or ACK_MODE_BIT) and BYTE_MASK).toByte(),
            0x00,
            0x00
        )
    }

    private fun parseActivityRecords(frame: ByteArray, recordCount: Int): List<RingActivityRecord> {
        val records = mutableListOf<RingActivityRecord>()
        var offset = HEADER_LEN

        for (i in 0 until recordCount) {
            val timestamp = readU32BE(frame, offset)
            val unixSeconds = timestamp + TIMESTAMP_OFFSET_UNIX
            val bodyStart = offset + TIMESTAMP_LEN

            val steps = frame[bodyStart + STEPS_OFFSET_IN_BODY].toInt() and BYTE_MASK

            val sleepFlagged = isSleepFlagged(frame, bodyStart)

            records.add(RingActivityRecord(unixSeconds, steps, sleepFlagged))
            offset += ACTIVITY_RECORD_LEN
        }

        return records
    }

    private fun isSleepFlagged(frame: ByteArray, bodyStart: Int): Boolean {
        for (i in SLEEP_CHECK_START until SLEEP_CHECK_END) {
            if ((frame[bodyStart + i].toInt() and BYTE_MASK) != SLEEP_FLAG_VALUE) {
                return false
            }
        }
        return true
    }

    private fun readU32BE(frame: ByteArray, offset: Int): Long {
        val b0 = frame[offset + TIMESTAMP_OFFSET_0].toLong() and BYTE_MASK_LONG
        val b1 = frame[offset + TIMESTAMP_OFFSET_1].toLong() and BYTE_MASK_LONG
        val b2 = frame[offset + TIMESTAMP_OFFSET_2].toLong() and BYTE_MASK_LONG
        val b3 = frame[offset + TIMESTAMP_OFFSET_3].toLong() and BYTE_MASK_LONG
        return (((b0 shl BYTE_SHIFT) or b1) shl BYTE_SHIFT or b2) shl BYTE_SHIFT or b3
    }

    /** Trailer (last byte) is the XOR of all preceding bytes. */
    private fun xorValid(frame: ByteArray): Boolean {
        var x = 0
        for (i in 0 until frame.size - 1) {
            x = x xor (frame[i].toInt() and BYTE_MASK)
        }
        return (x and BYTE_MASK) == (frame[frame.size - 1].toInt() and BYTE_MASK)
    }
}
