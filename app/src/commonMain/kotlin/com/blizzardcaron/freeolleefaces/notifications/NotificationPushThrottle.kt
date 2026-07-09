package com.blizzardcaron.freeolleefaces.notifications

/**
 * Pure policy for pacing live notification-badge pushes to the watch. Every count change
 * costs a BLE write, and a chatty notification stream would otherwise wake the radio every
 * couple of seconds. Trailing-edge: a change during the cooldown waits until the minute
 * boundary and then sends whatever the count is at that moment, so bursts collapse into one
 * write per minute and the badge is never stale longer than [MIN_INTERVAL_MS]. Kept pure
 * (no Android deps) so the throttle is unit-testable, matching
 * [com.blizzardcaron.freeolleefaces.activity.ActivityNotificationThrottle].
 */
object NotificationPushThrottle {

    const val MIN_INTERVAL_MS = 60_000L

    /**
     * Delay before the next push may fire: at least [debounceMs] (so flurries still coalesce),
     * stretched so consecutive sends land at least [MIN_INTERVAL_MS] apart. Callers use a
     * monotonic clock for [nowMs]/[lastPushMs] and may seed
     * `lastPushMs = now - MIN_INTERVAL_MS` to make the first push immediate.
     */
    fun delayFor(nowMs: Long, lastPushMs: Long, debounceMs: Long): Long =
        maxOf(debounceMs, lastPushMs + MIN_INTERVAL_MS - nowMs)
}
