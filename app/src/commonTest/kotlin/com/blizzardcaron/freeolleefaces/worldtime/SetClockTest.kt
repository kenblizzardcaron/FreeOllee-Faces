package com.blizzardcaron.freeolleefaces.worldtime

import kotlin.test.Test
import kotlin.test.assertEquals

class SetClockTest {
    @Test
    fun testGoldenVector() {
        // Golden vector from on-device verification (Task 12):
        // build(nowMs = 1_783_729_820_000, offsetSec = -21600, latE3 = 40140, lonE3 = -105144)
        // → 001aaa55fb5302239c8e516aa0abffffcc9c00004865feff0300ffff
        val result = SetClock.build(
            nowMs = 1_783_729_820_000,
            offsetSec = -21600,
            latE3 = 40140,
            lonE3 = -105144
        )
        val expected = "001aaa55fb5302239c8e516aa0abffffcc9c00004865feff0300ffff".hexToByteArray()
        assertEquals(expected.toList(), result.toList())
    }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
