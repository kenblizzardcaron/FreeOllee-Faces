package com.blizzardcaron.freeolleefaces.ring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RingRecordsTest {

    private fun hexDecode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = hex[2 * i].digitToInt(16)
            val lo = hex[2 * i + 1].digitToInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }

    // Vector A — 6-record 4c frame, remaining=0.
    @Test fun parses_6record_activity_frame() {
        val frame = hexDecode(
            "4c00000c3b853f4f3d020012174246223b451975a52c12ea5d000c3b85d550000200120c1a161014109d404019555a17000c3b866b4f000200120b101010101000410853b33a44000c3b87014f0001855e0c101010101000000401100017340c3b8797503d0281120a101010100f05900801308c00d00c3b882d50000481120a101010100f01e2c41cf0040000f5"
        )
        val parsed = RingRecords.parse(frame)
        assertEquals(0x4c, parsed?.frameId)
        assertEquals(0, parsed?.remaining)
        assertEquals(6, parsed?.activityRecords?.size)

        // Check expected (unixSeconds, steps) pairs; all sleepFlagged=false.
        val expected = listOf(
            1783035327 to 44,
            1783035477 to 25,
            1783035627 to 83,
            1783035777 to 1,
            1783035927 to 1,
            1783036077 to 28
        )
        expected.forEachIndexed { idx, (sec, step) ->
            assertEquals(sec.toLong(), parsed?.activityRecords?.get(idx)?.unixSeconds)
            assertEquals(step, parsed?.activityRecords?.get(idx)?.steps)
            assertEquals(false, parsed?.activityRecords?.get(idx)?.sleepFlagged)
        }
    }

    // Vector B — 1-record 4c frame with sleepFlagged=true.
    @Test fun parses_1record_activity_frame_with_sleep_flag() {
        val frame = hexDecode(
            "4c00000c3be1d34d130a7f610a010101010100000000000000040c"
        )
        val parsed = RingRecords.parse(frame)
        assertEquals(0x4c, parsed?.frameId)
        assertEquals(0, parsed?.remaining)
        assertEquals(1, parsed?.activityRecords?.size)

        val rec = parsed?.activityRecords?.get(0)
        assertEquals(1783059027L, rec?.unixSeconds)
        assertEquals(0, rec?.steps)
        assertEquals(true, rec?.sleepFlagged)
    }

    // Vector C — 6-record 4c frame with remaining=43 (43 records still queued).
    @Test fun parses_6record_frame_with_remaining() {
        val frame = hexDecode(
            "4c002b0c3c075342210a7d5c0a010101010100000000000000040c3c07e9411f0a7f120a010101010100000000000000000c3c087f471f09805d0a010101010100000000000000040c3c091542170a87120a010101010100000000000000000c3c09ab45180a875e0a010101010100000000000000040c3c0a4142150a78120a01010101010000000000000000d5"
        )
        val parsed = RingRecords.parse(frame)
        assertEquals(0x4c, parsed?.frameId)
        assertEquals(43, parsed?.remaining)
        assertEquals(6, parsed?.activityRecords?.size)

        // All records have steps=0 and sleepFlagged=true.
        val first = parsed?.activityRecords?.get(0)
        assertEquals(1783068627L, first?.unixSeconds)
        parsed?.activityRecords?.forEach { rec ->
            assertEquals(0, rec.steps)
            assertEquals(true, rec.sleepFlagged)
        }
    }

    // Vector D — 1-record 47 wellness frame (no activity records).
    @Test fun parses_47_wellness_frame_empty_records() {
        val frame = hexDecode(
            "4700000c3c22240296000000a4690a4690a4690a4690a4290a428fa428fa428fa428fa428fa428fa428fa428fa428fa428f0d3"
        )
        val parsed = RingRecords.parse(frame)
        assertEquals(0x47, parsed?.frameId)
        assertEquals(0, parsed?.remaining)
        assertEquals(0, parsed?.activityRecords?.size)
    }

    // Negative tests: corrupt Vector B.
    @Test fun rejects_bad_xor_trailer() {
        val frame = hexDecode("4c00000c3be1d34d130a7f610a010101010100000000000000040c")
        val corrupt = frame.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertNull(RingRecords.parse(corrupt))
    }

    @Test fun rejects_truncated_frame() {
        val frame = hexDecode("4c00000c3be1d34d130a7f610a010101010100000000000000040c")
        val truncated = frame.copyOf(frame.size - 1)
        assertNull(RingRecords.parse(truncated))
    }

    @Test fun rejects_invalid_frame_id() {
        val frame = hexDecode("4c00000c3be1d34d130a7f610a010101010100000000000000040c")
        val corrupt = frame.copyOf().also { it[0] = 0x10 }
        // Must recalculate XOR for this to be a proper rejection test.
        var x = 0
        for (i in 0 until corrupt.size - 1) x = x xor (corrupt[i].toInt() and 0xFF)
        corrupt[corrupt.size - 1] = x.toByte()
        assertNull(RingRecords.parse(corrupt))
    }

    @Test fun rejects_invalid_byte_1() {
        val frame = hexDecode("4c00000c3be1d34d130a7f610a010101010100000000000000040c")
        val corrupt = frame.copyOf().also { it[1] = 0x01 }
        // Recalculate XOR.
        var x = 0
        for (i in 0 until corrupt.size - 1) x = x xor (corrupt[i].toInt() and 0xFF)
        corrupt[corrupt.size - 1] = x.toByte()
        assertNull(RingRecords.parse(corrupt))
    }

    @Test fun rejects_empty_frame() {
        assertNull(RingRecords.parse(byteArrayOf()))
    }

    // Ack command tests.
    @Test fun ack_command_0x4c() {
        val ack = RingRecords.ackCommand(0x4c)
        assertEquals(3, ack.size)
        assertEquals(0xcc.toByte(), ack[0])
        assertEquals(0x00.toByte(), ack[1])
        assertEquals(0x00.toByte(), ack[2])
    }

    @Test fun ack_command_0x47() {
        val ack = RingRecords.ackCommand(0x47)
        assertEquals(3, ack.size)
        assertEquals(0xc7.toByte(), ack[0])
        assertEquals(0x00.toByte(), ack[1])
        assertEquals(0x00.toByte(), ack[2])
    }
}
