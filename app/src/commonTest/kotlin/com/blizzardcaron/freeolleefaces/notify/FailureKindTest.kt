package com.blizzardcaron.freeolleefaces.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailureKindTest {

    @Test fun transientFailuresAreRetryable() {
        assertTrue(FailureKind.WATCH_UNREACHABLE.retryable)
        assertTrue(FailureKind.WEATHER_FETCH_FAILED.retryable)
    }

    @Test fun setupFailuresAreNotRetryable() {
        assertFalse(FailureKind.SETUP_INCOMPLETE.retryable)
        assertFalse(FailureKind.HEALTH_UNAVAILABLE.retryable)
    }

    @Test fun everyKindHasAnExplicitRetryableValue() {
        // Guard against a future kind silently defaulting; exactly two are retryable today.
        assertEquals(2, FailureKind.entries.count { it.retryable })
    }
}
