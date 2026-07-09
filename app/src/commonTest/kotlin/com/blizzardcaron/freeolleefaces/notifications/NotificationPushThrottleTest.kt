package com.blizzardcaron.freeolleefaces.notifications

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationPushThrottleTest {

    private val debounce = 2_000L

    @Test fun idleFallsBackToDebounceFloor() {
        // Last push a full interval ago: only the debounce applies.
        assertEquals(
            debounce,
            NotificationPushThrottle.delayFor(
                nowMs = NotificationPushThrottle.MIN_INTERVAL_MS,
                lastPushMs = 0L,
                debounceMs = debounce,
            ),
        )
    }

    @Test fun midCooldownStretchesToTheMinuteBoundary() {
        // 10 s after a push, the next send must wait the remaining 50 s.
        assertEquals(
            50_000L,
            NotificationPushThrottle.delayFor(nowMs = 10_000L, lastPushMs = 0L, debounceMs = debounce),
        )
    }

    @Test fun justInsideCooldownStillBeatsTheDebounceFloor() {
        // 59 s after a push the remaining 1 s is under the debounce; the floor wins.
        assertEquals(
            debounce,
            NotificationPushThrottle.delayFor(nowMs = 59_000L, lastPushMs = 0L, debounceMs = debounce),
        )
    }

    @Test fun firstPushSentinelIsImmediateUpToTheDebounce() {
        // Service seeds lastPushMs = now - MIN_INTERVAL_MS so the first push waits only the debounce.
        val now = 5_000L
        assertEquals(
            debounce,
            NotificationPushThrottle.delayFor(
                nowMs = now,
                lastPushMs = now - NotificationPushThrottle.MIN_INTERVAL_MS,
                debounceMs = debounce,
            ),
        )
    }
}
