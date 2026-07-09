package com.blizzardcaron.freeolleefaces.ring

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

class RingDailyStepsTest {

    private class FixedClock(private var instant: Instant) : Clock {
        override fun now(): Instant = instant
        fun advanceTo(newInstant: Instant) {
            instant = newInstant
        }
    }

    private val denverTimeZone = TimeZone.of("America/Denver")

    @Test
    fun `countable records dated today add up`() {
        val clock = FixedClock(Instant.fromEpochSeconds(1783068700))
        val accumulator = RingDailySteps(MapSettings(), clock) { denverTimeZone }

        accumulator.record(1783068627, 40, true)
        accumulator.record(1783068777, 2, true)

        assertEquals(42, accumulator.today())
    }

    @Test
    fun `non-countable records advance watermark but add nothing`() {
        val clock = FixedClock(Instant.fromEpochSeconds(1783068700))
        val accumulator = RingDailySteps(MapSettings(), clock) { denverTimeZone }

        accumulator.record(1783068627, 40, false)
        assertEquals(0, accumulator.today())

        // Same timestamp replayed with countable=true still yields 0 because watermark was advanced
        accumulator.record(1783068627, 40, true)
        assertEquals(0, accumulator.today())
    }

    @Test
    fun `duplicate replay ignored`() {
        val clock = FixedClock(Instant.fromEpochSeconds(1783068700))
        val accumulator = RingDailySteps(MapSettings(), clock) { denverTimeZone }

        accumulator.record(1783068627, 40, true)
        accumulator.record(1783068627, 40, true)

        assertEquals(40, accumulator.today())
    }

    @Test
    fun `yesterday's record adds nothing but advances watermark`() {
        val clock = FixedClock(Instant.fromEpochSeconds(1783068700)) // July 3 Denver
        val accumulator = RingDailySteps(MapSettings(), clock) { denverTimeZone }

        // July 2 Denver (yesterday)
        accumulator.record(1783035327, 100, true)
        assertEquals(0, accumulator.today())
    }

    @Test
    fun `day rollover resets sum`() {
        val clock = FixedClock(Instant.fromEpochSeconds(1783068700))
        val accumulator = RingDailySteps(MapSettings(), clock) { denverTimeZone }

        accumulator.record(1783068627, 40, true)
        assertEquals(40, accumulator.today())

        // Advance by 2 days
        clock.advanceTo(Instant.fromEpochSeconds(1783068700 + 86400 * 2))
        assertEquals(0, accumulator.today())
    }

    @Test
    fun `stale replay does not regress the watermark`() {
        val clock = FixedClock(Instant.fromEpochSeconds(1783068700))
        val accumulator = RingDailySteps(MapSettings(), clock) { denverTimeZone }

        accumulator.record(1783068627, 40, true)
        // A crashed session re-replays older records first, then the counted one again; neither
        // may count (an early build regressed the watermark here and double-counted the 40).
        accumulator.record(1783068477, 10, true)
        accumulator.record(1783068627, 40, true)

        assertEquals(40, accumulator.today())
    }

    @Test
    fun `persists across instances`() {
        val settings = MapSettings()
        val clock = FixedClock(Instant.fromEpochSeconds(1783068700))

        val accumulator1 = RingDailySteps(settings, clock) { denverTimeZone }
        accumulator1.record(1783068627, 40, true)
        accumulator1.record(1783068777, 2, true)

        val accumulator2 = RingDailySteps(settings, clock) { denverTimeZone }
        assertEquals(42, accumulator2.today())
    }
}
