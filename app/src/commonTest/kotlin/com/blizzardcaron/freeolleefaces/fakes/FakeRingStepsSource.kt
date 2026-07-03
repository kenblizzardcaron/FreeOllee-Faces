package com.blizzardcaron.freeolleefaces.fakes

import com.blizzardcaron.freeolleefaces.ring.RingStepsSource

/** Test double: returns a preset result and counts how many times it was consulted. */
class FakeRingStepsSource(
    var result: Result<Long?> = Result.success(null),
) : RingStepsSource {
    var calls = 0
        private set

    override suspend fun readSteps(): Result<Long?> {
        calls++
        return result
    }
}
