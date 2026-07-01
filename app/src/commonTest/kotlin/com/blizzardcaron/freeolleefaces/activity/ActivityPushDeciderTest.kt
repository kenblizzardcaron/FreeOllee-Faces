package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityPushDeciderTest {
    private val spacing = 30_000L

    @Test fun firstPushAlwaysAllowed() =
        assertTrue(ActivityPushDecider.shouldPush(null, "P5 00", 0, forced = false, minSpacingMs = spacing))

    @Test fun suppressedBeforeSpacingEvenOnChange() =
        assertFalse(ActivityPushDecider.shouldPush("P5 00", "P4 59", 15_000, forced = false, minSpacingMs = spacing))

    @Test fun allowedAfterSpacingElapses() =
        assertTrue(ActivityPushDecider.shouldPush("P5 00", "P5 00", 30_000, forced = false, minSpacingMs = spacing))

    @Test fun forcedBypassesSpacing() =
        assertTrue(ActivityPushDecider.shouldPush("P5 00", "d1-23", 1, forced = true, minSpacingMs = spacing))

    @Test fun smallIntervalHonored() =
        assertTrue(ActivityPushDecider.shouldPush("P5 00", "P4 59", 3_000, forced = false, minSpacingMs = 3_000L))
}
