package com.blizzardcaron.freeolleefaces.sun

import com.blizzardcaron.freeolleefaces.format.SunEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs

class SunCalcTest {

    /** Assert two ZonedDateTimes are within `toleranceMinutes` of each other. */
    private fun assertCloseTime(expected: ZonedDateTime, actual: ZonedDateTime, toleranceMinutes: Long = 2) {
        val diff = abs(expected.toInstant().epochSecond - actual.toInstant().epochSecond)
        assertTrue(
            "expected ~$expected but got $actual (diff = ${diff}s)",
            diff <= toleranceMinutes * 60
        )
    }

    @Test
    fun `Greenwich on 2026-03-20 reports sunrise around 06-02 UTC`() {
        // NOAA solar calculator: Greenwich (51.4779, -0.0015) on 2026-03-20.
        // Sunrise ~ 06:02 UTC, sunset ~ 18:13 UTC. (Equinox-ish.)
        val now = ZonedDateTime.of(2026, 3, 20, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
        val result = SunCalc.nextEvent(now, lat = 51.4779, lng = -0.0015, zone = ZoneId.of("UTC"))
        assertNotNull(result)
        assertEquals(SunEventKind.SUNRISE, result!!.kind)
        val expected = ZonedDateTime.of(2026, 3, 20, 6, 2, 0, 0, ZoneOffset.UTC)
        assertCloseTime(expected, result.time)
    }

    @Test
    fun `Greenwich on 2026-03-20 after sunrise returns sunset`() {
        val now = ZonedDateTime.of(2026, 3, 20, 12, 0, 0, 0, ZoneOffset.UTC).toInstant()
        val result = SunCalc.nextEvent(now, lat = 51.4779, lng = -0.0015, zone = ZoneId.of("UTC"))
        assertNotNull(result)
        assertEquals(SunEventKind.SUNSET, result!!.kind)
        val expected = ZonedDateTime.of(2026, 3, 20, 18, 13, 0, 0, ZoneOffset.UTC)
        assertCloseTime(expected, result.time)
    }

    @Test
    fun `Denver on 2026-07-04 around noon returns sunset`() {
        // NOAA: Denver (39.7392, -104.9903) on 2026-07-04.
        // Sunset ~ 02:33 UTC on 2026-07-05 (20:33 MDT, UTC-6).
        val now = ZonedDateTime.of(2026, 7, 4, 18, 0, 0, 0, ZoneOffset.UTC).toInstant()
        val result = SunCalc.nextEvent(now, lat = 39.7392, lng = -104.9903, zone = ZoneId.of("UTC"))
        assertNotNull(result)
        assertEquals(SunEventKind.SUNSET, result!!.kind)
        val expected = ZonedDateTime.of(2026, 7, 5, 2, 33, 0, 0, ZoneOffset.UTC)
        assertCloseTime(expected, result.time, toleranceMinutes = 3)
    }

    @Test
    fun `Tromso on 2026-06-21 has no sunrise or sunset within 24 hours`() {
        // Tromsø, Norway (69.6492, 18.9553) at summer solstice — midnight sun.
        val now = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, ZoneOffset.UTC).toInstant()
        val result = SunCalc.nextEvent(now, lat = 69.6492, lng = 18.9553, zone = ZoneId.of("UTC"))
        assertNull(result)
    }

    @Test
    fun `nextEvent localizes to requested zone`() {
        val now = ZonedDateTime.of(2026, 3, 20, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
        val result = SunCalc.nextEvent(now, lat = 51.4779, lng = -0.0015, zone = ZoneId.of("America/New_York"))
        assertNotNull(result)
        assertEquals(ZoneId.of("America/New_York"), result!!.time.zone)
    }
}
