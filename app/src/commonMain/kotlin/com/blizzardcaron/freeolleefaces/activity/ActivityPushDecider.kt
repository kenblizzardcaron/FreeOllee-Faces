package com.blizzardcaron.freeolleefaces.activity

/**
 * Pure policy for when to write the name-tag. The configured [minSpacingMs] is the minimum gap
 * between ANY two writes (battery saver); a MODE/unit change (`forced`) bypasses it, and the very
 * first write is always allowed. Once the spacing has elapsed we re-push the latest text (this also
 * serves as the heartbeat), so a value that changed mid-interval is coalesced into one write.
 */
object ActivityPushDecider {

    const val DEFAULT_MIN_SPACING_MS = 30_000L

    // newText is unused by the current coalescing policy but kept in the signature so the call site
    // stays stable and a future "changed-only" mode needs no signature churn.
    @Suppress("UnusedParameter")
    fun shouldPush(
        lastPushedText: String?,
        newText: String,
        msSinceLastPush: Long,
        forced: Boolean,
        minSpacingMs: Long = DEFAULT_MIN_SPACING_MS,
    ): Boolean = forced || lastPushedText == null || msSinceLastPush >= minSpacingMs
}
