package com.blizzardcaron.freeolleefaces.worldtime

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime

/**
 * Phone-side World Time state (spec: the app owns the value — "Approach A"). [slots] are IANA
 * zone ids in display order; [activeZoneId] is the zone currently pushed to the watch;
 * [customOffsetSec] holds an on-watch value adopted at reconcile that matched no slot;
 * [swapped] means the Casio swap is in effect (home lives in the World Time face and the
 * active zone drives the main clock).
 */
data class WorldTimeState(
    val slots: List<String> = emptyList(),
    val activeZoneId: String? = null,
    val customOffsetSec: Int? = null,
    val swapped: Boolean = false,
)

/** Pure world-time logic: zone offsets, the weekday-register header, reconcile, labels. */
object WorldTime {
    const val MAX_SLOTS = 4

    private const val SECONDS_PER_HOUR = 3600
    private const val SECONDS_PER_MINUTE = 60
    private const val TWO_DIGITS = 2

    /** Current UTC offset of [zoneId] at [nowMs]; null for an unknown zone id. */
    fun offsetSecondsOf(zoneId: String, nowMs: Long): Int? = runCatching {
        TimeZone.of(zoneId).offsetAt(Instant.fromEpochMilliseconds(nowMs)).totalSeconds
    }.getOrNull()

    /**
     * The world-side offset: the active zone's current (DST-correct) offset, else the adopted
     * custom offset, else home's offset — a neutral fallback so an unconfigured install never
     * stamps a foreign zone (the +9 capture-constant bug this feature fixes).
     */
    fun worldOffsetSec(state: WorldTimeState, nowMs: Long, homeZoneId: String): Int =
        state.activeZoneId?.let { offsetSecondsOf(it, nowMs) }
            ?: state.customOffsetSec
            ?: (offsetSecondsOf(homeZoneId, nowMs) ?: 0)

    /**
     * The 4-byte header every weekday-register write must carry: home's offset while swapped
     * (home is displayed in the World Time face), else the world offset.
     */
    fun headerFor(state: WorldTimeState, nowMs: Long, homeZoneId: String): ByteArray {
        val sec = if (state.swapped) {
            offsetSecondsOf(homeZoneId, nowMs) ?: 0
        } else {
            worldOffsetSec(state, nowMs, homeZoneId)
        }
        return WorldTimeCodec.encode(sec)
    }

    /**
     * Adopts an on-watch offset that differs from our expectation (call only on mismatch): the
     * slot matching [watchOffsetSec] right now becomes active, otherwise the offset is kept as
     * custom. Either way `swapped` clears — a mismatch means the watch no longer reflects our
     * swap. The app never fights the user's on-watch change at reconcile time.
     */
    fun reconcile(state: WorldTimeState, watchOffsetSec: Int, nowMs: Long): WorldTimeState {
        val match = state.slots.firstOrNull { offsetSecondsOf(it, nowMs) == watchOffsetSec }
        return if (match != null) {
            state.copy(activeZoneId = match, customOffsetSec = null, swapped = false)
        } else {
            state.copy(activeZoneId = null, customOffsetSec = watchOffsetSec, swapped = false)
        }
    }

    /** "America/New_York" → "New York" — the picker/chip display name. */
    fun cityOf(zoneId: String): String = zoneId.substringAfterLast('/').replace('_', ' ')

    /** "+9:00", "-6:00", "+5:30" — offset chip text. */
    fun offsetLabel(sec: Int): String {
        val sign = if (sec < 0) "-" else "+"
        val abs = if (sec < 0) -sec else sec
        val h = abs / SECONDS_PER_HOUR
        val m = (abs % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        return "$sign$h:${m.toString().padStart(TWO_DIGITS, '0')}"
    }

    /** Current wall time in [zoneId] as "HH:MM"; null for an unknown zone id. */
    fun timeLabel(zoneId: String, nowMs: Long): String? = runCatching {
        val local = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(TimeZone.of(zoneId))
        "${local.hour.toString().padStart(TWO_DIGITS, '0')}:${local.minute.toString().padStart(TWO_DIGITS, '0')}"
    }.getOrNull()
}
