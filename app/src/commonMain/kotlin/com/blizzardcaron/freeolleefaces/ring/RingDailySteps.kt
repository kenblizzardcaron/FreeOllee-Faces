package com.blizzardcaron.freeolleefaces.ring

import com.russhwolf.settings.Settings
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Accumulator for daily step counts from a RingConn ring. The ring replays step records only
 * since the last acknowledged frame, so the app must persist a running daily total and replay
 * watermark to avoid double-counting. [record] updates the watermark unconditionally (to unlock
 * new records from the ring on the next fetch) and only counts steps in the app's local date if
 * they are marked countable (non-sleep-flagged).
 */
class RingDailySteps(
    private val settings: Settings,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {

    /**
     * Conditionally add [steps] to today's total and unconditionally advance the replay watermark
     * to [unixSeconds]. If [countable] is true and the record's local date equals today's local
     * date (in the configured timezone), the steps are added; otherwise they are silently dropped
     * (replay duplicates, yesterday's records, sleep intervals all land here). The watermark is
     * always advanced to unlock new records from the ring on the next fetch.
     *
     * Rolls the day first (resets sum/date if the stored date is stale).
     */
    fun record(unixSeconds: Long, steps: Int, countable: Boolean) {
        // A replayed record at or below the watermark must not touch it: regressing the
        // watermark would let the records between the two values count twice on re-replay.
        if (unixSeconds <= getWatermark()) {
            return
        }
        advanceWatermark(unixSeconds)
        rollDayIfNeeded()

        if (!countable) {
            return
        }

        val recordLocalDate = Instant.fromEpochSeconds(unixSeconds).toLocalDateTime(timeZone()).date
        val todayLocalDate = clock.now().toLocalDateTime(timeZone()).date

        if (recordLocalDate == todayLocalDate) {
            val current = getSum()
            setSum(current + steps)
        }
    }

    /**
     * Return today's step sum (0 if none). Rolls the day first (resets sum/date if the stored
     * date is stale).
     */
    fun today(): Long {
        rollDayIfNeeded()
        return getSum()
    }

    private fun rollDayIfNeeded() {
        val todayLocalDate = clock.now().toLocalDateTime(timeZone()).date.toString()
        val storedDate = getStoredDate()

        if (storedDate != todayLocalDate) {
            setStoredDate(todayLocalDate)
            setSum(0)
        }
    }

    private fun getStoredDate(): String = settings.getStringOrNull(KEY_DAILY_DATE) ?: ""

    private fun setStoredDate(dateString: String) = settings.putString(KEY_DAILY_DATE, dateString)

    private fun getSum(): Long = settings.getLong(KEY_DAILY_SUM, 0L)

    private fun setSum(value: Long) = settings.putLong(KEY_DAILY_SUM, value)

    private fun getWatermark(): Long = settings.getLong(KEY_RECORD_WATERMARK, 0L)

    private fun advanceWatermark(unixSeconds: Long) = settings.putLong(KEY_RECORD_WATERMARK, unixSeconds)

    companion object {
        private const val KEY_DAILY_DATE = "ring_daily_date"
        private const val KEY_DAILY_SUM = "ring_daily_sum"
        private const val KEY_RECORD_WATERMARK = "ring_record_watermark"
    }
}
