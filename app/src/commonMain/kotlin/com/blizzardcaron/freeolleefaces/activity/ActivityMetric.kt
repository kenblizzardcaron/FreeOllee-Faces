package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.format.formatDecimal
import com.blizzardcaron.freeolleefaces.format.groupThousands
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The metric currently shown on the watch name-tag. `render` is the only path that produces the
 * 6-char wire string. ORIENTATION/ALTITUDE/PRESSURE are the live-glance instruments (PRESSURE from
 * the phone barometer, network fallback); the track schema (`TrackPoint.altM`) reserves altitude.
 */
enum class ActivityMetric {
    PACE, DISTANCE, TIME, ORIENTATION, ALTITUDE, PRESSURE, AVG_PACE;

    fun next(): ActivityMetric = entries[(ordinal + 1) % entries.size]

    fun render(state: ActivityState, unit: ActivityUnit): String = when (this) {
        PACE -> renderPace(state, unit)
        DISTANCE -> renderDistance(state, unit)
        TIME -> renderTime(state)
        ORIENTATION -> renderOrientation(state.headingDeg)
        ALTITUDE -> renderAltitude(state.altitudeM, unit)
        PRESSURE -> renderPressure(state.pressureHpa, unit)
        AVG_PACE -> renderAvgPace(state, unit)
    }

    /** In-app, fully-unit'd interpretation of this metric. null when the underlying value is absent. */
    fun human(state: ActivityState, unit: ActivityUnit): String? = when (this) {
        PACE -> humanPace(state, unit)
        DISTANCE -> humanDistance(state, unit)
        TIME -> humanTime(state)
        ORIENTATION -> humanOrientation(state.headingDeg)
        ALTITUDE -> humanAltitude(state.altitudeM, unit)
        PRESSURE -> humanPressure(state.pressureHpa, unit)
        AVG_PACE -> humanAvgPace(state, unit)
    }

    @Suppress("TooManyFunctions")
    private companion object {
        const val SECONDS_PER_MINUTE = 60
        const val SECONDS_PER_HOUR = 3600
        const val MAX_PACE_SECONDS = 99 * 60 + 59
        const val MAX_HOURS = 99
        const val MAX_DISTANCE_UNITS = 9999.0
        const val NAMEPLATE_WIDTH = 6
        const val MILLIS_PER_SECOND = 1000L
        const val DISTANCE_TAG = "d" // lowercase d is legible & distinct; uppercase 'D' looks like '0'
        const val FEET_PER_METER = 3.28084
        const val FULL_CIRCLE = 360
        const val COMPASS_DIGIT_WIDTH = 3

        fun renderOrientation(headingDeg: Float?): String {
            if (headingDeg == null) return "---#"
            val deg = (headingDeg.roundToInt() % FULL_CIRCLE + FULL_CIRCLE) % FULL_CIRCLE
            return "${deg.toString().padStart(COMPASS_DIGIT_WIDTH, '0')}#${cardinal8(headingDeg)}"
        }

        fun renderAltitude(altM: Double?, unit: ActivityUnit): String {
            if (altM == null) return "---"
            val (value, suffix) =
                if (unit == ActivityUnit.IMPERIAL) (altM * FEET_PER_METER) to 'f' else altM to 'm'
            val num = value.roundToInt().toString()
            return if (num.length >= NAMEPLATE_WIDTH) num.take(NAMEPLATE_WIDTH) else "$num$suffix"
        }

        // Barometric pressure from the phone sensor (network fallback). Imperial → inHg ("29.91",
        // '.' renders as a dash on the nameplate); metric → whole hPa ("1013").
        fun renderPressure(hpa: Double?, unit: ActivityUnit): String {
            if (hpa == null) return "----"
            return DisplayFormatter.pressure(hpa, imperial = unit == ActivityUnit.IMPERIAL)
        }

        // The watch nameplate cells render ':' blank and '.' as a dash, and have no legible mi/km
        // glyphs. So min/sec separators use a space (renders identically to the blank colon),
        // distance keeps its '.' (renders as a dash separator), the unit is shown only in-app, and
        // tags use legible glyphs: 'P' pace, lowercase 'd' distance, lowercase 't' time ('h' hours).
        fun renderPace(state: ActivityState, unit: ActivityUnit): String {
            val secPerKm = state.recentPaceSecPerKm
            if (secPerKm == null || secPerKm <= 0.0) return "P --"
            val secs = unit.paceSecondsPerUnit(secPerKm).roundToInt().coerceIn(0, MAX_PACE_SECONDS)
            val mm = secs / SECONDS_PER_MINUTE
            val ss = (secs % SECONDS_PER_MINUTE).toString().padStart(2, '0')
            return "P$mm $ss"
        }

        fun renderDistance(state: ActivityState, unit: ActivityUnit): String {
            val value = unit.distance(state.distanceMeters).coerceIn(0.0, MAX_DISTANCE_UNITS)
            val avail = NAMEPLATE_WIDTH - DISTANCE_TAG.length
            val num = listOf(2, 1, 0)
                .map { formatDecimal(value, it) }
                .firstOrNull { it.length <= avail }
                ?: formatDecimal(value, 0)
            return DISTANCE_TAG + num
        }

        fun renderTime(state: ActivityState): String {
            val totalSec = state.elapsedMs / MILLIS_PER_SECOND
            if (totalSec < SECONDS_PER_HOUR) {
                val mm = (totalSec / SECONDS_PER_MINUTE).toString().padStart(2, '0')
                val ss = (totalSec % SECONDS_PER_MINUTE).toString().padStart(2, '0')
                return "t$mm $ss"
            }
            val h = (totalSec / SECONDS_PER_HOUR).coerceAtMost(MAX_HOURS.toLong())
            val m = ((totalSec % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toString().padStart(2, '0')
            return "${h}h$m"
        }

        const val INHG_PER_HPA = 0.0295299830714
        const val INHG_DECIMALS = 2
        const val DISTANCE_DECIMALS = 2
        const val COMPASS_DEGREE_WIDTH = 3

        fun humanPace(state: ActivityState, unit: ActivityUnit): String? {
            val secPerKm = state.recentPaceSecPerKm
            if (secPerKm == null || secPerKm <= 0.0) return null
            val secs = unit.paceSecondsPerUnit(secPerKm).roundToInt().coerceAtLeast(0)
            val mm = secs / SECONDS_PER_MINUTE
            val ss = (secs % SECONDS_PER_MINUTE).toString().padStart(2, '0')
            return "$mm:$ss /${unit.distanceSuffix}"
        }

        fun humanDistance(state: ActivityState, unit: ActivityUnit): String {
            val distance = unit.distance(state.distanceMeters).coerceAtLeast(0.0)
            return "${formatDecimal(distance, DISTANCE_DECIMALS)} ${unit.distanceSuffix}"
        }

        fun humanTime(state: ActivityState): String {
            val totalSec = state.elapsedMs / MILLIS_PER_SECOND
            val h = totalSec / SECONDS_PER_HOUR
            val m = (totalSec % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
            val s = totalSec % SECONDS_PER_MINUTE
            return listOf(h, m, s).joinToString(":") { it.toString().padStart(2, '0') }
        }

        fun humanOrientation(headingDeg: Float?): String? {
            if (headingDeg == null) return null
            val deg = (headingDeg.roundToInt() % FULL_CIRCLE + FULL_CIRCLE) % FULL_CIRCLE
            return "${deg.toString().padStart(COMPASS_DEGREE_WIDTH, '0')}° ${cardinal8(headingDeg).trimEnd()}"
        }

        fun humanAltitude(altM: Double?, unit: ActivityUnit): String? {
            if (altM == null) return null
            return if (unit == ActivityUnit.IMPERIAL) {
                "${groupThousands((altM * FEET_PER_METER).roundToLong())} ft"
            } else {
                "${groupThousands(altM.roundToLong())} m"
            }
        }

        fun humanPressure(hpa: Double?, unit: ActivityUnit): String? {
            if (hpa == null) return null
            return if (unit == ActivityUnit.IMPERIAL) {
                "${formatDecimal(hpa * INHG_PER_HPA, INHG_DECIMALS)} inHg"
            } else {
                "${hpa.roundToInt()} hPa"
            }
        }

        const val METERS_PER_KM = 1000.0
        const val MILLIS_PER_SEC_D = 1000.0
        const val AVG_PACE_TAG = "A"

        private fun avgPaceSecPerKm(state: ActivityState): Double? {
            val km = state.distanceMeters / METERS_PER_KM
            if (km <= 0.0 || state.movingTimeMs <= 0L) return null
            return (state.movingTimeMs / MILLIS_PER_SEC_D) / km
        }

        fun renderAvgPace(state: ActivityState, unit: ActivityUnit): String {
            val secPerKm = avgPaceSecPerKm(state) ?: return "$AVG_PACE_TAG --"
            val secs = unit.paceSecondsPerUnit(secPerKm).roundToInt().coerceIn(0, MAX_PACE_SECONDS)
            val mm = secs / SECONDS_PER_MINUTE
            val ss = (secs % SECONDS_PER_MINUTE).toString().padStart(2, '0')
            return "$AVG_PACE_TAG$mm $ss"
        }

        fun humanAvgPace(state: ActivityState, unit: ActivityUnit): String? {
            val secPerKm = avgPaceSecPerKm(state) ?: return null
            val secs = unit.paceSecondsPerUnit(secPerKm).roundToInt().coerceAtLeast(0)
            val mm = secs / SECONDS_PER_MINUTE
            val ss = (secs % SECONDS_PER_MINUTE).toString().padStart(2, '0')
            return "$mm:$ss /${unit.distanceSuffix} avg"
        }
    }
}
