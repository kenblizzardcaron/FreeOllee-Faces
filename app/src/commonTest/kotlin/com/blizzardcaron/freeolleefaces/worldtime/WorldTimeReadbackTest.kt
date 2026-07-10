package com.blizzardcaron.freeolleefaces.worldtime

import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorldTimeReadbackTest {

    // Build a reply frame the way BatteryReadbackTest does (real framing via buildRawPacket +
    // OlleeProtocol's frame parser) — payload = 4-byte header + "MOTUWETHFRSASU".
    private fun replyFrame(header: ByteArray): OlleeProtocol.Frame {
        val payload = header + "MOTUWETHFRSASU".encodeToByteArray()
        val raw = OlleeProtocol.buildRawPacket(
            OlleeProtocol.TARGET_GET_WEEKDAYS + OlleeProtocol.RESPONSE_TARGET_OFFSET,
            payload,
        )
        // Reuse whatever parse entry point BatteryReadbackTest uses to turn raw bytes into a Frame.
        return OlleeProtocol.parseFrame(raw)!!
    }

    @Test
    fun parsesTheHeaderOffset() {
        assertEquals(32_400, WorldTimeReadback.parseOffsetSec(replyFrame(WorldTimeCodec.encode(32_400))))
        assertEquals(-21_600, WorldTimeReadback.parseOffsetSec(replyFrame(WorldTimeCodec.encode(-21_600))))
    }

    @Test
    fun rejectsShortPayloadAndWrongTarget() {
        val short = replyFrame(WorldTimeCodec.encode(0)).let {
            OlleeProtocol.parseFrame(
                OlleeProtocol.buildRawPacket(
                    OlleeProtocol.TARGET_GET_WEEKDAYS + OlleeProtocol.RESPONSE_TARGET_OFFSET,
                    ByteArray(2),
                ),
            )!!
        }
        assertNull(WorldTimeReadback.parseOffsetSec(short))
        val wrongTarget = OlleeProtocol.parseFrame(
            OlleeProtocol.buildRawPacket(0x4A, WorldTimeCodec.encode(0) + ByteArray(14)),
        )!!
        assertNull(WorldTimeReadback.parseOffsetSec(wrongTarget))
    }
}
