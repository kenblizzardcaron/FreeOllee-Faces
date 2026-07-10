package com.blizzardcaron.freeolleefaces.ble

import com.blizzardcaron.freeolleefaces.worldtime.WorldTimeCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OlleeProtocolTest {

    // Encodes to the 2026-05-31 capture constant `00 00 7E 90` (+9:00 = 32,400 s); reused below
    // so the pre-existing byte-level assertions still exercise the exact captured bytes.
    private val captureHeader = WorldTimeCodec.encode(32_400)

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
        assertContentEquals(expected, packet)
    }

    @Test
    fun `buildPacket rejects values longer than 6 characters`() {
        assertFailsWith<IllegalArgumentException> {
            OlleeProtocol.buildPacket("TooLong")
        }
    }

    @Test
    fun `buildPacket rejects non-ASCII characters`() {
        assertFailsWith<IllegalArgumentException> {
            OlleeProtocol.buildPacket("café  ")
        }
    }

    @Test
    fun `buildPacket accepts a value shorter than 6 chars without padding (caller's responsibility)`() {
        val packet = OlleeProtocol.buildPacket("Hi")
        // Length byte = inner.size + 4 = (2 + 2) + 4 = 8.
        assertEquals(8, packet[1].toInt() and 0xFF)
    }

    // --- arbitrary-target support (temperature-field experiment) ---

    @Test
    fun `default buildPacket still targets the nameplate 0x2F`() {
        val packet = OlleeProtocol.buildPacket("Hi")
        assertEquals(0x2f.toByte(), packet[7]) // inner header byte 2 = target
    }

    @Test
    fun `buildPacket can target the temperature field 0x2E with correct framing`() {
        val value = "  72 F"
        val packet = OlleeProtocol.buildPacket(OlleeProtocol.TARGET_TEMPERATURE, value)

        val inner = byteArrayOf(0x02, 0x2e) + value.toByteArray(Charsets.US_ASCII)
        val crc = OlleeProtocol.crc16(inner)
        val expected = byteArrayOf(
            0x00,
            (inner.size + 4).toByte(),
            0xaa.toByte(),
            0x55,
            (crc shr 8).toByte(),
            (crc and 0xFF).toByte()
        ) + inner

        assertContentEquals(expected, packet)
        assertEquals(0x2e.toByte(), packet[7])
    }

    @Test
    fun `buildPacket rejects a target outside one byte`() {
        assertFailsWith<IllegalArgumentException> {
            OlleeProtocol.buildPacket(0x123, "Hi")
        }
    }

    @Test
    fun `formatTemperatureF matches the watch read format and stays 6 chars`() {
        assertEquals("  72 F", OlleeProtocol.formatTemperatureF(72))
        assertEquals(" 105 F", OlleeProtocol.formatTemperatureF(105))
        assertEquals(6, OlleeProtocol.formatTemperatureF(72).length)
        assertEquals(6, OlleeProtocol.formatTemperatureF(105).length)
    }

    // --- frame parsing ---

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `parseFrame round-trips buildPacket`() {
        val packet = OlleeProtocol.buildPacket(OlleeProtocol.TARGET_TEMPERATURE, "  72 F")
        val f = OlleeProtocol.parseFrame(packet)!!
        assertEquals(0x02, f.cmd)
        assertEquals(0x2e, f.target)
        assertEquals("  72 F", String(f.payload, Charsets.US_ASCII))
        assertTrue(f.crcOk)
    }

    @Test
    fun `parseFrame decodes a real captured temperature read-back (target 0x4E)`() {
        // Captured from the watch: cmd 02, target 4E, payload "  54 F", CRC 0xCB6D.
        // crcOk == true validates our CRC-16 against the watch's actual bytes.
        val f = OlleeProtocol.parseFrame(hex("000CAA55CB6D024E202035342046"))!!
        assertEquals(0x02, f.cmd)
        assertEquals(0x4e, f.target)
        assertEquals("  54 F", String(f.payload, Charsets.US_ASCII))
        assertTrue(f.crcOk)
    }

    @Test
    fun `parseFrame returns null for too-short or wrong-magic input`() {
        assertNull(OlleeProtocol.parseFrame(byteArrayOf(0x00, 0x06)))
        assertNull(OlleeProtocol.parseFrame(hex("00060000022A"))) // no AA 55 magic
    }

    // --- raw / weekday-table packets (upper-left panel foundation) ---
    //
    // The watch's upper-left letter pair renders the current day's 2-char slot from a 7-entry
    // weekday table written at target 0x34, preceded by a 4-byte 00 00 7E 90 prefix. Captured
    // from the official app: inner = 02 34 | 00 00 7E 90 | "MOTUWETHFRSASU", CRC 0x7EAB.

    @Test
    fun `buildRawPacket reproduces the captured 0x34 weekday write byte-for-byte`() {
        val payload = hex("00007E90") + "MOTUWETHFRSASU".toByteArray(Charsets.US_ASCII)
        val packet = OlleeProtocol.buildRawPacket(0x34, payload)
        assertContentEquals(hex("0018AA557EAB023400007E904D4F545557455448465253415355"), packet)
    }

    @Test
    fun `buildWeekdayPacket from the standard abbreviations matches the captured frame`() {
        val packet = OlleeProtocol.buildWeekdayPacket(
            listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU"), captureHeader,
        )
        assertContentEquals(hex("0018AA557EAB023400007E904D4F545557455448465253415355"), packet)
    }

    @Test
    fun `buildWeekdayPacket with all TE slots produces a valid frame the watch will accept`() {
        val packet = OlleeProtocol.buildWeekdayPacket(List(7) { "TE" }, captureHeader)
        val f = OlleeProtocol.parseFrame(packet)!!
        assertEquals(0x34, f.target)
        assertTrue(f.crcOk)
        assertContentEquals(hex("00007E90") + "TETETETETETETE".toByteArray(Charsets.US_ASCII), f.payload)
    }

    @Test
    fun `buildWeekdayPacket rejects a table that is not 7 slots`() {
        assertFailsWith<IllegalArgumentException> {
            OlleeProtocol.buildWeekdayPacket(listOf("MO", "TU"), captureHeader)
        }
    }

    @Test
    fun `buildWeekdayPacket rejects a slot that is not exactly 2 chars`() {
        assertFailsWith<IllegalArgumentException> {
            OlleeProtocol.buildWeekdayPacket(List(7) { "X" }, captureHeader)
        }
    }

    @Test
    fun `buildWeekdayPacket rejects a non-ASCII slot`() {
        assertFailsWith<IllegalArgumentException> {
            OlleeProtocol.buildWeekdayPacket(List(7) { "é2" }, captureHeader) // length 2 but non-ASCII
        }
    }

    @Test
    fun `buildRawPacket rejects a target outside one byte`() {
        assertFailsWith<IllegalArgumentException> {
            OlleeProtocol.buildRawPacket(0x123, byteArrayOf(0x00))
        }
    }

    @Test
    fun weekdayPacketCarriesExplicitHeader() {
        val header = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xAB.toByte(), 0xA0.toByte())
        val packet = OlleeProtocol.buildWeekdayPacket(
            listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU"), header,
        )
        // payload starts after the 8-byte frame preamble (00 LEN AA 55 CRC CRC 02 34)
        assertContentEquals(header, packet.copyOfRange(8, 12))
    }

    @Test
    fun weekdayPacketRejectsWrongSizeHeader() {
        assertFailsWith<IllegalArgumentException> {
            OlleeProtocol.buildWeekdayPacket(List(7) { "MO" }, ByteArray(3))
        }
    }

    // --- Timer slots (0x26) ---

    @Test
    fun `buildTimerPacket with all-zero durations equals a zero-header raw 0x26 packet`() {
        val packet = OlleeProtocol.buildTimerPacket(List(10) { 0 }, headerSeconds = 0)
        val expected = OlleeProtocol.buildRawPacket(OlleeProtocol.TARGET_TIMERS, ByteArray(44))
        assertContentEquals(expected, packet)
    }

    @Test
    fun `buildTimerPacket encodes each slot as a little-endian uint32 of seconds`() {
        val packet = OlleeProtocol.buildTimerPacket(listOf(83, 0, 0, 0, 0, 0, 0, 0, 0, 0), headerSeconds = 0)
        assertEquals(0x02.toByte(), packet[6])
        assertEquals(0x26.toByte(), packet[7])
        assertEquals(0x00.toByte(), packet[8])  // header byte 0
        assertEquals(0x53.toByte(), packet[12]) // 83 low byte
        assertEquals(0x00.toByte(), packet[13])
        assertEquals(0x00.toByte(), packet[14])
        assertEquals(0x00.toByte(), packet[15])
    }

    @Test
    fun `buildTimerPacket derives the header minutes and seconds from headerSeconds not slot 1`() {
        // headerSeconds = 100 s (00:01:40); slot 1 = 83 s — deliberately different.
        val packet = OlleeProtocol.buildTimerPacket(listOf(83, 0, 0, 0, 0, 0, 0, 0, 0, 0), headerSeconds = 100)
        assertEquals(0x00.toByte(), packet[8])  // header byte 0
        assertEquals(1.toByte(), packet[9])     // minutes from headerSeconds
        assertEquals(40.toByte(), packet[10])   // seconds from headerSeconds
        assertEquals(0x00.toByte(), packet[11]) // header byte 3 = SAVE
        assertEquals(0x53.toByte(), packet[12]) // slot 1 word still 83, untouched by the header
    }

    @Test
    fun `buildTimerPacket encodes the header as hours, minutes, seconds in bytes 0-2`() {
        // 20h05m00s = 72300 s -> header 14 05 00 (the 2026-06-15 captured value).
        val packet = OlleeProtocol.buildTimerPacket(List(10) { 0 }, headerSeconds = 72_300)
        assertEquals(20.toByte(), packet[8])  // hours
        assertEquals(5.toByte(), packet[9])   // minutes
        assertEquals(0.toByte(), packet[10])  // seconds
    }

    @Test
    fun `buildTimerPacket carries minutes over 59 into the hours byte`() {
        // 75 min = 4500 s -> 1h15m -> header 01 0F 00 (was 00 4B 00 under the old model).
        val packet = OlleeProtocol.buildTimerPacket(List(10) { 0 }, headerSeconds = 4_500)
        assertEquals(1.toByte(), packet[8])
        assertEquals(15.toByte(), packet[9])
        assertEquals(0.toByte(), packet[10])
    }

    @Test
    fun `buildTimerPacket encodes the 23h59m59s ceiling exactly`() {
        val packet = OlleeProtocol.buildTimerPacket(List(10) { 0 }, headerSeconds = 86_399)
        assertEquals(23.toByte(), packet[8])
        assertEquals(59.toByte(), packet[9])
        assertEquals(59.toByte(), packet[10])
    }

    @Test
    fun `buildTimerPacket clamps the hours byte at 0xFF for absurd values`() {
        // 256 h = 921_600 s -> hours byte would be 256; clamp to 0xFF. Seconds/minutes still encode.
        val packet = OlleeProtocol.buildTimerPacket(List(10) { 0 }, headerSeconds = 921_600)
        assertEquals(0xFF.toByte(), packet[8])
    }

    @Test
    fun `buildTimerPacket round-trips through parseFrame to target 0x26 with valid CRC`() {
        val packet = OlleeProtocol.buildTimerPacket(
            listOf(83, 100, 100, 100, 100, 100, 0, 100_000, 900, 1800), headerSeconds = 0)
        val f = OlleeProtocol.parseFrame(packet)!!
        assertEquals(0x26, f.target)
        assertTrue(f.crcOk)
        val base = 4 + 7 * 4
        val slot8 = (f.payload[base].toInt() and 0xFF) or
            ((f.payload[base + 1].toInt() and 0xFF) shl 8) or
            ((f.payload[base + 2].toInt() and 0xFF) shl 16) or
            ((f.payload[base + 3].toInt() and 0xFF) shl 24)
        assertEquals(100_000, slot8)
    }

    @Test
    fun `buildTimerPacket rejects a list that is not exactly 10 slots`() {
        assertFailsWith<IllegalArgumentException> {
            OlleeProtocol.buildTimerPacket(listOf(1, 2, 3), headerSeconds = 0)
        }
    }

    @Test
    fun `buildTimerPacket rejects an out-of-range duration`() {
        assertFailsWith<IllegalArgumentException> {
            OlleeProtocol.buildTimerPacket(listOf(360_000, 0, 0, 0, 0, 0, 0, 0, 0, 0), headerSeconds = 0)
        }
    }

    @Test
    fun `buildTimerPacket rejects a negative headerSeconds`() {
        assertFailsWith<IllegalArgumentException> {
            OlleeProtocol.buildTimerPacket(List(10) { 0 }, headerSeconds = -1)
        }
    }

    @Test
    fun `buildTimerPacket writes header byte3 per start mode`() {
        val slots = List(10) { 0 }
        assertEquals(0x00.toByte(),
            OlleeProtocol.buildTimerPacket(slots, headerSeconds = 0, startMode = OlleeProtocol.TimerStartMode.SAVE)[11])
        assertEquals(0x01.toByte(),
            OlleeProtocol.buildTimerPacket(slots, headerSeconds = 0, startMode = OlleeProtocol.TimerStartMode.START_SINGLE)[11])
        assertEquals(0x02.toByte(),
            OlleeProtocol.buildTimerPacket(slots, headerSeconds = 0, startMode = OlleeProtocol.TimerStartMode.START_INTERVAL)[11])
    }

    @Test
    fun `TimerStartMode of decodes the official app's two independent toggles`() {
        val mode = OlleeProtocol.TimerStartMode
        assertEquals(OlleeProtocol.TimerStartMode.SAVE, mode.of(startFromApp = false, intervalMode = false))
        assertEquals(OlleeProtocol.TimerStartMode.SAVE, mode.of(startFromApp = false, intervalMode = true)) // start off wins
        assertEquals(OlleeProtocol.TimerStartMode.START_SINGLE, mode.of(startFromApp = true, intervalMode = false))
        assertEquals(OlleeProtocol.TimerStartMode.START_INTERVAL, mode.of(startFromApp = true, intervalMode = true))
    }

    @Test
    fun `buildTimerPacket reproduces the captured save and start-single frames`() {
        // byte3=01 (the second frame, header 07:07) is a SINGLE start: verified on hardware
        // 2026-06-13 that 01 counts down the header value, not the slot sequence.
        val slots = listOf(180, 30, 180, 30, 0, 60, 120, 600, 900, 1800)
        fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
        assertEquals(
            "0032AA5577CA022600030000B40000001E000000B40000001E000000000000003C00000078000000580200008403000008070000",
            hex(OlleeProtocol.buildTimerPacket(slots, headerSeconds = 180, startMode = OlleeProtocol.TimerStartMode.SAVE)))
        assertEquals(
            "0032AA550558022600070701B40000001E000000B40000001E000000000000003C00000078000000580200008403000008070000",
            hex(OlleeProtocol.buildTimerPacket(slots, headerSeconds = 427, startMode = OlleeProtocol.TimerStartMode.START_SINGLE)))
    }

    @Test
    fun `buildTimerPacket reproduces the captured 4-44 single-start frame from hardware`() {
        // Captured 2026-06-13 from FreeOllee, confirmed on watch: 4:44 single counts down.
        // header 00 04 2C 01 (MM=4, SS=44, byte3=01 SINGLE) + the standard slot table.
        val slots = listOf(180, 30, 180, 30, 0, 60, 120, 600, 900, 1800)
        fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
        assertEquals(
            "0032AA55FE95022600042C01B40000001E000000B40000001E000000000000003C00000078000000580200008403000008070000",
            hex(OlleeProtocol.buildTimerPacket(slots, headerSeconds = 284, startMode = OlleeProtocol.TimerStartMode.START_SINGLE)))
    }

}
