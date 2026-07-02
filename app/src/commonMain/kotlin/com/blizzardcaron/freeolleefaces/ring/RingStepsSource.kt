package com.blizzardcaron.freeolleefaces.ring

/**
 * Reads the ring's live onboard step count.
 *  - `success(n)`    — the ring's onboard count
 *  - `success(null)` — connected but no step frame arrived this cycle
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
