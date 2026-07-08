package com.blizzardcaron.freeolleefaces.ring

import com.blizzardcaron.freeolleefaces.prefs.Prefs

/**
 * Reads the ring's step total for today (sum of its synced activity records, sleep-flagged
 * buckets excluded — the ring registers sleep movement as phantom steps).
 *  - `success(n)`    — today's total so far
 *  - `success(null)` — the ring did not contribute this cycle (unreachable and no prior records)
 *  - `failure`       — read genuinely failed; the caller falls back to Health Connect
 * Nothing thrown escapes an implementation; all faults surface as `failure`.
 */
interface RingStepsSource {
    suspend fun readSteps(): Result<Long?>
}

/** Used when the feature is off or on non-Android targets: never contributes a value. */
object NoopRingStepsSource : RingStepsSource {
    override suspend fun readSteps(): Result<Long?> = Result.success(null)
}

/**
 * The ring's daily total, or null when the opt-in is off or the ring did not contribute.
 * Single gate shared by every steps path (foreground refresh AND the scheduled auto-push
 * worker) so the two can't diverge on when the ring is consulted.
 */
suspend fun RingStepsSource.stepsIfEnabled(prefs: Prefs): Long? =
    if (!prefs.ringConnStepsEnabled) null else readSteps().getOrNull()
