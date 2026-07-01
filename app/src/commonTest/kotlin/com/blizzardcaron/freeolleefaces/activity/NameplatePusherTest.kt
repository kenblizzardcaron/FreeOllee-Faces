package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingBle(var failNext: Boolean = false) : BleClient {
    val sent = mutableListOf<Triple<String, String, Int>>()
    override suspend fun send(deviceAddress: String, value: String) = send(deviceAddress, value, OlleeProtocol.TARGET_NAMEPLATE)
    override suspend fun send(deviceAddress: String, value: String, target: Int): Result<Unit> {
        sent += Triple(deviceAddress, value, target)
        return if (failNext) Result.failure(RuntimeException("link")) else Result.success(Unit)
    }
    override suspend fun sendPacket(deviceAddress: String, packet: ByteArray) = Result.success(Unit)
    override suspend fun sendAndAwait(deviceAddress: String, requestPacket: ByteArray, expectedTarget: Int, timeoutMs: Long) =
        Result.failure<OlleeProtocol.Frame>(RuntimeException("n/a"))
}

class NameplatePusherTest {
    @Test fun pushesFirstValueToNameplateTarget() = runTest {
        val ble = RecordingBle()
        val pusher = NameplatePusher(ble)
        val reachable = pusher.maybePush("AA:BB", "P5 30", nowMs = 0, currentlyReachable = true)
        assertTrue(reachable)
        assertEquals(1, ble.sent.size)
        assertEquals(OlleeProtocol.TARGET_NAMEPLATE, ble.sent[0].third)
        assertEquals("P5 30", pusher.lastPushText)
    }

    @Test fun skipsRepeatWithinSpacing() = runTest {
        val ble = RecordingBle()
        val pusher = NameplatePusher(ble)
        pusher.maybePush("AA:BB", "P5 30", nowMs = 0, currentlyReachable = true, minSpacingMs = 30_000L)
        pusher.maybePush("AA:BB", "P5 30", nowMs = 100, currentlyReachable = true, minSpacingMs = 30_000L)
        assertEquals(1, ble.sent.size)
    }

    @Test fun forceNextRepushesIdenticalText() = runTest {
        val ble = RecordingBle()
        val pusher = NameplatePusher(ble)
        pusher.maybePush("AA:BB", "P5 30", nowMs = 0, currentlyReachable = true)
        pusher.forceNext()
        pusher.maybePush("AA:BB", "P5 30", nowMs = 1_000, currentlyReachable = true)
        assertEquals(2, ble.sent.size)
    }

    @Test fun forcedFiresImmediatelyRegardlessOfSpacing() = runTest {
        val ble = RecordingBle()
        val pusher = NameplatePusher(ble)
        pusher.maybePush("AA:BB", "P5 30", nowMs = 0, currentlyReachable = true, minSpacingMs = 30_000L)
        pusher.forceNext()
        pusher.maybePush("AA:BB", "P5 30", nowMs = 200, currentlyReachable = true, minSpacingMs = 30_000L)
        assertEquals(2, ble.sent.size)
    }

    @Test fun honorsConfiguredSpacingForChangedText() = runTest {
        val ble = RecordingBle()
        val pusher = NameplatePusher(ble)
        pusher.maybePush("AA:BB", "P5 30", nowMs = 0, currentlyReachable = true, minSpacingMs = 30_000L)
        // Changed value, but only 5s later and spacing is 30s -> suppressed (battery saver).
        pusher.maybePush("AA:BB", "P5 25", nowMs = 5_000, currentlyReachable = true, minSpacingMs = 30_000L)
        assertEquals(1, ble.sent.size)
        // At 30s the spacing has elapsed -> the latest value is written.
        pusher.maybePush("AA:BB", "P5 10", nowMs = 30_000, currentlyReachable = true, minSpacingMs = 30_000L)
        assertEquals(2, ble.sent.size)
        assertEquals("P5 10", pusher.sent(ble).last())
    }

    @Test fun nullAddressSkipsAndKeepsReachable() = runTest {
        val ble = RecordingBle()
        val pusher = NameplatePusher(ble)
        val reachable = pusher.maybePush(null, "P5 30", nowMs = 0, currentlyReachable = true)
        assertTrue(reachable)
        assertEquals(0, ble.sent.size)
    }

    @Test fun sendFailureMarksUnreachable() = runTest {
        val ble = RecordingBle(failNext = true)
        val pusher = NameplatePusher(ble)
        val reachable = pusher.maybePush("AA:BB", "P5 30", nowMs = 0, currentlyReachable = true)
        assertTrue(!reachable)
    }

    private fun NameplatePusher.sent(ble: RecordingBle) = ble.sent.map { it.second }
}
