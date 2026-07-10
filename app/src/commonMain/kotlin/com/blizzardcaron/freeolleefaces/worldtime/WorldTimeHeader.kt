package com.blizzardcaron.freeolleefaces.worldtime

import com.blizzardcaron.freeolleefaces.prefs.Prefs
import kotlinx.datetime.TimeZone

/**
 * The one place weekday-write call sites get their 4-byte register header from: the
 * app-computed World Time offset (spec Approach A — the app owns the value).
 */
object WorldTimeHeader {
    fun fromPrefs(
        prefs: Prefs,
        nowMs: Long,
        homeZoneId: String = TimeZone.currentSystemDefault().id,
    ): ByteArray = WorldTime.headerFor(prefs.worldTimeState(), nowMs, homeZoneId)
}
