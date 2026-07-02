package com.blizzardcaron.freeolleefaces.ring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RingConnDescriptorTest {

    // Real captured descriptors (btsnoop, 2026-07-02, user's Gen2 ring). 19 bytes, XOR-valid.
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val fetch15 = bytes(0x87, 0x4f, 0x03, 0x00, 0x00, 0x0f, 0x01, 0x3b, 0x01, 0x45,
        0x00, 0x00, 0x00, 0x00, 0x10, 0x26, 0x00, 0xff, 0x73)
    private val spont90 = bytes(0x10, 0x4f, 0x03, 0x00, 0x00, 0x5a, 0x01, 0x37, 0x01, 0x40,
        0x00, 0x00, 0x00, 0x00, 0x10, 0x26, 0x00, 0xff, 0xb8)
    private val spont304 = bytes(0x10, 0x4f, 0x02, 0x00, 0x01, 0x30, 0x01, 0x2a, 0x01, 0x38,
        0x00, 0x00, 0x00, 0x00, 0x10, 0x28, 0x00, 0xff, 0xb9)

    @Test fun parses_fetch_descriptor_0x87() = assertEquals(15L, RingConnDescriptor.parseSteps(fetch15))
    @Test fun parses_spontaneous_descriptor_0x10() = assertEquals(90L, RingConnDescriptor.parseSteps(spont90))
    @Test fun parses_high_step_count() = assertEquals(304L, RingConnDescriptor.parseSteps(spont304))

    @Test fun rejects_wrong_response_id() =
        assertNull(RingConnDescriptor.parseSteps(bytes(0x81, 0x00, 0x11, 0x90)))

    @Test fun rejects_truncated_frame() =
        assertNull(RingConnDescriptor.parseSteps(bytes(0x10, 0x4f, 0x03, 0x00, 0x00)))

    @Test fun rejects_bad_xor_trailer() {
        val corrupt = spont90.copyOf().also { it[it.size - 1] = 0x00 }
        assertNull(RingConnDescriptor.parseSteps(corrupt))
    }

    @Test fun parses_zero_steps() {
        // 0x10 with steps [4:6]=00 00; recompute a valid XOR trailer over [0..17].
        val body = bytes(0x10, 0x4f, 0x03, 0x00, 0x00, 0x00, 0x01, 0x37, 0x01, 0x40,
            0x00, 0x00, 0x00, 0x00, 0x10, 0x26, 0x00, 0xff, 0x00)
        var x = 0
        for (i in 0 until body.size - 1) x = x xor (body[i].toInt() and 0xff)
        body[body.size - 1] = x.toByte()
        assertEquals(0L, RingConnDescriptor.parseSteps(body))
    }
}
