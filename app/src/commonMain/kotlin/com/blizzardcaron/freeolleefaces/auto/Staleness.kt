package com.blizzardcaron.freeolleefaces.auto

import com.blizzardcaron.freeolleefaces.format.TempUnit

private const val MILLIS_PER_MINUTE = 60_000L

/**
 * True when a cached temperature can be pushed without re-fetching: it exists, was fetched in the
 * current unit, and is younger than the auto-update interval.
 */
fun isTempCacheFresh(
    fetchedMs: Long?,
    cacheUnit: TempUnit?,
    currentUnit: TempUnit,
    intervalMin: Int,
    nowMs: Long,
): Boolean {
    return fetchedMs != null && cacheUnit != null && cacheUnit == currentUnit &&
        nowMs - fetchedMs < intervalMin * MILLIS_PER_MINUTE
}

/** True when the cached battery voltage is younger than the auto-update interval. */
fun isBatteryCacheFresh(fetchedMs: Long?, intervalMin: Int, nowMs: Long): Boolean =
    fetchedMs != null && nowMs - fetchedMs < intervalMin * MILLIS_PER_MINUTE
