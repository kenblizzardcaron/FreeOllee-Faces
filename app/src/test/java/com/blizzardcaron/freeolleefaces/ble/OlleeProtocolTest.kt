package com.blizzardcaron.freeolleefaces.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OlleeProtocolTest {

    // CRC-16/CCITT-FALSE reference vector: ASCII "123456789" -> 0x29B1
    @Test
    fun `crc16 matches the CCITT-FALSE reference vector for 123456789`() {
        val input = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x29B1, OlleeProtocol.crc16(input))
    }

    // The payload FreeOllee sends for a value like "Hello " is
    //   inner = 0x02 0x2f 'H' 'e' 'l' 'l' 'o' ' '
    // CRC-16/CCITT-FALSE of that byte sequence is 0xC0C2 (verified with
    // two independent computations: Python's binascii.crc_hqx(..., 0xFFFF)
    // and a hand-rolled CCITT-FALSE implementation).
    @Test
    fun `crc16 over the inner payload for value 'Hello ' is 0xC0C2`() {
        val inner = byteArrayOf(0x02, 0x2f) + "Hello ".toByteArray(Charsets.US_ASCII)
        assertEquals(0xC0C2, OlleeProtocol.crc16(inner))
    }

    // buildPacket must wrap a 6-char value with the exact framing
    // FreeOllee uses: [0x00, length, 0xaa, 0x55, crcHi, crcLo, 0x02, 0x2f, value...]
    // where length = inner.size + 4.
    @Test
    fun `buildPacket for 'Hello ' produces the exact framed bytes`() {
        val packet = OlleeProtocol.buildPacket("Hello ")

        // inner length is 8; length byte = 8 + 4 = 12 (0x0C).
        val expected = byteArrayOf(
            0x00,
            0x0C,
            0xaa.toByte(),
            0x55,
            0xC0.toByte(), 0xC2.toByte(), // CRC of inner
            0x02, 0x2f,                   // inner header
            0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x20 // "Hello "
        )
        assertArrayEquals(expected, packet)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildPacket rejects values longer than 6 characters`() {
        OlleeProtocol.buildPacket("TooLong")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildPacket rejects non-ASCII characters`() {
        OlleeProtocol.buildPacket("café  ")
    }

    @Test
    fun `buildPacket accepts a value shorter than 6 chars without padding (caller's responsibility)`() {
        val packet = OlleeProtocol.buildPacket("Hi")
        // Length byte = inner.size + 4 = (2 + 2) + 4 = 8.
        assertEquals(8, packet[1].toInt() and 0xFF)
    }
}
