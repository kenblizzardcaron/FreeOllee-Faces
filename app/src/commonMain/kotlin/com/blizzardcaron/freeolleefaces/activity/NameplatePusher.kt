package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.glyph.NameplateSanitizer

/**
 * The shared name-tag push core: sanitize → decide (via [ActivityPushDecider]) → send to
 * [OlleeProtocol.TARGET_NAMEPLATE]. Owns the push bookkeeping (last text, last push time, the
 * force-next latch) so both the activity and instruments engines reuse one tested path.
 */
class NameplatePusher(private val ble: BleClient) {
    private var lastPushedText: String? = null
    private var lastPushMs: Long = 0L
    private var force: Boolean = false

    val lastPushText: String? get() = lastPushedText

    /** Force the next [maybePush] to write even if the text is unchanged (e.g. MODE/unit change). */
    fun forceNext() { force = true }

    suspend fun maybePush(
        address: String?,
        rawText: String,
        nowMs: Long,
        currentlyReachable: Boolean,
        minSpacingMs: Long = ActivityPushDecider.DEFAULT_MIN_SPACING_MS,
    ): Boolean {
        val text = NameplateSanitizer.sanitize(rawText)
        var reachable = currentlyReachable
        val approved = address != null &&
            ActivityPushDecider.shouldPush(lastPushedText, text, nowMs - lastPushMs, force, minSpacingMs)
        if (approved) {
            ble.send(address, text, OlleeProtocol.TARGET_NAMEPLATE)
                .onSuccess {
                    lastPushedText = text
                    lastPushMs = nowMs
                    reachable = true
                }
                .onFailure { reachable = false }
            force = false // clear only once an approved write was attempted
        }
        return reachable
    }
}
