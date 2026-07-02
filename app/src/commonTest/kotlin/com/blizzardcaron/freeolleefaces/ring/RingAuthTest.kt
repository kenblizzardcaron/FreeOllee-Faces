package com.blizzardcaron.freeolleefaces.ring

import kotlin.test.Test
import kotlin.test.assertEquals

class RingAuthTest {

    private fun hex(b: ByteArray) = b.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    @Test fun sm3_abc_known_answer() {
        // GB/T 32905 KAT: SM3("abc")
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            hex(Sm3.digest("abc".encodeToByteArray())),
        )
    }

    @Test fun sm3_64byte_known_answer() {
        // GB/T 32905 KAT: SM3 of "abcd" repeated 16 times (64 bytes)
        val msg = "abcd".repeat(16).encodeToByteArray()
        assertEquals(
            "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732",
            hex(Sm3.digest(msg)),
        )
    }

    @Test fun authCommand_matches_captured_on_device_pair() {
        // btsnoop 2026-07-02: challenge 0x11 -> response e1 b9 23, unique V=0x4a
        // (user's ring MAC ends ..:F6:96:2A; V = 0xf6 xor 0x96 xor 0x2a = 0x4a).
        val mac = byteArrayOf(
            0x00, 0x00, 0x00, 0xF6.toByte(), 0x96.toByte(), 0x2A,
        )
        val cmd = RingAuth.authCommand(challenge = 0x11, mac = mac)
        assertEquals(
            "01 01 e1 b9 23 00",
            cmd.joinToString(" ") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) },
        )
    }
}
