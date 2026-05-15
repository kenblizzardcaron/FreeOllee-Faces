package com.blizzardcaron.freeolleefaces.sun

import com.blizzardcaron.freeolleefaces.format.SunEventKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class NextEvent(val kind: SunEventKind, val time: ZonedDateTime)

object SunCalc {

    // Standard "official" zenith for sunrise/sunset: 90.833° (includes refraction + solar disc).
    private const val ZENITH_DEG = 90.833

    /**
     * Returns the next sunrise OR sunset (whichever is soonest) strictly after [now], in the
     * requested [zone], or `null` if neither event occurs within the next 24 hours
     * (polar day/night).
     */
    fun nextEvent(now: Instant, lat: Double, lng: Double, zone: ZoneId): NextEvent? {
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        // Compute today's events and tomorrow's events in UTC.
        val candidates = buildList {
            for (offset in 0..1L) {
                val date = today.plusDays(offset)
                val rise = computeEventUtc(date, lat, lng, isSunrise = true)
                val set = computeEventUtc(date, lat, lng, isSunrise = false)
                if (rise != null) add(NextEvent(SunEventKind.SUNRISE, rise.atZone(zone)))
                if (set != null) add(NextEvent(SunEventKind.SUNSET, set.atZone(zone)))
            }
        }

        val horizon = now.plusSeconds(24 * 60 * 60)
        return candidates
            .filter { it.time.toInstant().isAfter(now) && it.time.toInstant().isBefore(horizon) || it.time.toInstant() == horizon }
            .minByOrNull { it.time.toInstant() }
    }

    /**
     * Compute one event (sunrise or sunset) on [date] for ([lat], [lng]) and return its UTC
     * instant, or null if no event occurs that day at that latitude (polar).
     *
     * Algorithm: NOAA general solar position calculation
     * (https://gml.noaa.gov/grad/solcalc/calcdetails.html).
     */
    private fun computeEventUtc(
        date: LocalDate,
        lat: Double,
        lng: Double,
        isSunrise: Boolean,
    ): Instant? {
        // Day of year, 1-based.
        val n = date.dayOfYear.toDouble()

        // Approximate time of event in fractional days (sunrise uses 6, sunset uses 18).
        val lngHour = lng / 15.0
        val approxTime = n + ((if (isSunrise) 6.0 else 18.0) - lngHour) / 24.0

        // Solar mean anomaly.
        val M = (0.9856 * approxTime) - 3.289

        // True longitude.
        var L = M + (1.916 * sinDeg(M)) + (0.020 * sinDeg(2 * M)) + 282.634
        L = mod(L, 360.0)

        // Right ascension.
        var RA = atanDeg(0.91764 * tanDeg(L))
        RA = mod(RA, 360.0)

        // Adjust RA into same quadrant as L.
        val LQuadrant = (L.toInt() / 90) * 90.0
        val RAQuadrant = (RA.toInt() / 90) * 90.0
        RA = RA + (LQuadrant - RAQuadrant)
        RA /= 15.0 // -> hours

        // Solar declination.
        val sinDec = 0.39782 * sinDeg(L)
        val cosDec = cosDeg(asinDeg(sinDec))

        // Local hour angle.
        val cosH = (cosDeg(ZENITH_DEG) - (sinDec * sinDeg(lat))) / (cosDec * cosDeg(lat))
        if (cosH > 1.0 || cosH < -1.0) return null // polar — sun never reaches this zenith

        val Hdeg = if (isSunrise) 360.0 - acosDeg(cosH) else acosDeg(cosH)
        val H = Hdeg / 15.0 // -> hours

        // Local mean time of event.
        val T = H + RA - (0.06571 * approxTime) - 6.622

        // Convert to UTC.
        var UT = T - lngHour
        UT = mod(UT, 24.0)

        // Build the resulting UTC instant.
        val totalSeconds = (UT * 3600.0).toLong()
        val hours = (totalSeconds / 3600).toInt()
        val minutes = ((totalSeconds % 3600) / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        return date.atTime(hours, minutes, seconds).toInstant(ZoneOffset.UTC)
    }

    // ===== Degree-based trig helpers =====
    private fun sinDeg(d: Double) = sin(Math.toRadians(d))
    private fun cosDeg(d: Double) = cos(Math.toRadians(d))
    private fun tanDeg(d: Double) = tan(Math.toRadians(d))
    private fun asinDeg(x: Double) = Math.toDegrees(kotlin.math.asin(x))
    private fun acosDeg(x: Double) = Math.toDegrees(acos(x))
    private fun atanDeg(x: Double) = Math.toDegrees(kotlin.math.atan(x))

    private fun mod(x: Double, m: Double): Double {
        val r = x % m
        return if (r < 0) r + m else r
    }
}
