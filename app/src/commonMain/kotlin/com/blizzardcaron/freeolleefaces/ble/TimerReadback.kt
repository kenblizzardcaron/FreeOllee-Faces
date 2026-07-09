package com.blizzardcaron.freeolleefaces.ble

/**
 * Read-back confirmation for a timer push: read `0x2C`→`0x4C` and compare via [TimerConfirm].
 * Returns true only if the watch's active countdown value + run state match the push (a partial
 * confirmation — see [TimerConfirm]); a false return is the caller's signal to surface a "not
 * confirmed" warning.
 *
 * This does **not** auto-heal by re-sending. A `START_SINGLE`/`START_INTERVAL`
 * push already started a countdown on the watch; re-sending it would restart a likely-running timer
 * on a false-negative read. For a partial, advisory confirmation the right behaviour is to report,
 * not to disrupt the running timer — the user re-sends if they choose.
 */
object TimerReadback {
    suspend fun confirm(ble: BleClient, address: String, writePacket: ByteArray): Boolean {
        val reply = ble.sendAndAwait(
            address,
            OlleeProtocol.readRequest(OlleeProtocol.TARGET_GET_TIMER),
            OlleeProtocol.TARGET_GET_TIMER + OlleeProtocol.RESPONSE_TARGET_OFFSET,
        )
        return reply.getOrNull()?.let { TimerConfirm.matches(writePacket, it) } ?: false
    }
}
