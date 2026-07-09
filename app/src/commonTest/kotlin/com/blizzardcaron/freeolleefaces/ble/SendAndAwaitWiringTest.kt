package com.blizzardcaron.freeolleefaces.ble

import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendAndAwaitWiringTest {
    @Test fun fake_reply_is_returned_by_sendAndAwait() = runTest {
        val fake = FakeBleClient().apply {
            awaitResult = Result.success(
                OlleeProtocol.Frame(
                    cmd = 0x02, target = 0x4C, crcOk = true,
                    payload = byteArrayOf(0x00),
                ),
            )
        }
        val reply = fake.sendAndAwait("AA:BB", OlleeProtocol.readRequest(OlleeProtocol.TARGET_GET_TIMER), 0x4C)
        assertTrue(reply.isSuccess)
        assertEquals(0x4C, reply.getOrThrow().target)
    }
}
