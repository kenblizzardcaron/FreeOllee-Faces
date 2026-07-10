package com.blizzardcaron.freeolleefaces.worldtime

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorldTimeCodecTest {

    @Test
    fun encodesCapturedTokyoOffset() {
        // The 2026-05-31 capture constant: +9 h = 32_400 s = 0x00007E90.
        assertContentEquals(
            byteArrayOf(0x00, 0x00, 0x7E, 0x90.toByte()),
            WorldTimeCodec.encode(32_400),
        )
    }

    @Test
    fun encodesNegativeOffsetAsTwosComplement() {
        // MDT (UTC-6): -21_600 s = 0xFFFFABA0 big-endian two's complement.
        assertContentEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xAB.toByte(), 0xA0.toByte()),
            WorldTimeCodec.encode(-21_600),
        )
    }

    @Test
    fun encodesZeroAndHalfHourZones() {
        assertContentEquals(byteArrayOf(0, 0, 0, 0), WorldTimeCodec.encode(0))
        // IST (+5:30) = 19_800 s = 0x00004D58.
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x4D, 0x58), WorldTimeCodec.encode(19_800))
    }

    @Test
    fun roundTripsAcrossTheFullRange() {
        for (sec in listOf(-64_800, -21_600, -3_600, 0, 19_800, 32_400, 64_800)) {
            assertEquals(sec, WorldTimeCodec.decode(WorldTimeCodec.encode(sec)))
        }
    }

    @Test
    fun rejectsOutOfRangeEncode() {
        assertFailsWith<IllegalArgumentException> { WorldTimeCodec.encode(64_801) }
        assertFailsWith<IllegalArgumentException> { WorldTimeCodec.encode(-64_801) }
    }

    @Test
    fun decodeRejectsWrongSizeAndGarbage() {
        assertNull(WorldTimeCodec.decode(ByteArray(0)))
        assertNull(WorldTimeCodec.decode(ByteArray(3)))
        assertNull(WorldTimeCodec.decode(ByteArray(5)))
        // A value far outside ±18 h is not a plausible offset — treat as unknown layout.
        assertNull(WorldTimeCodec.decode(byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
    }
}
