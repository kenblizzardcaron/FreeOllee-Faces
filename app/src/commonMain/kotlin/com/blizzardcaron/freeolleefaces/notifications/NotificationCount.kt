package com.blizzardcaron.freeolleefaces.notifications

import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol

/**
 * Pure logic for the notification badge shown in the watch's weekday slot. Framework-free
 * so it unit-tests without Android objects; the service maps live notifications onto
 * [ActiveNotification] before calling in.
 */
object NotificationCount {

    /** Highest count renderable as a single digit in the legible left cell. */
    private const val MAX_SINGLE_DIGIT_COUNT = 9

    /** Highest count renderable as two clean digits (`10`/`11`) before collapsing to "11 or more". */
    private const val MAX_TWO_DIGIT_COUNT = 11

    /** Number of weekday slots on the watch (Mon..Sun). */
    private const val WEEKDAY_SLOT_COUNT = 7

    /** A framework-free view of one active (shade) notification. */
    data class ActiveNotification(
        val packageName: String,
        val isClearable: Boolean,
        val isOngoing: Boolean,
        val isGroupSummary: Boolean,
    )

    /**
     * Counts undismissed, non-persistent notifications: clearable, not ongoing, not a group
     * summary row, and not posted by us ([ownPackage]). Each surviving entry counts once, so
     * multiple notifications from one app all count.
     */
    fun countFrom(notifications: List<ActiveNotification>, ownPackage: String): Int =
        notifications.count {
            it.isClearable &&
                !it.isOngoing &&
                !it.isGroupSummary &&
                it.packageName != ownPackage
        }

    /**
     * Formats a count for the 2-cell weekday slot. The slot's **right cell garbles several digits**
     * (2/5/9 render as 8/garbled — verified on hardware 2026-06-04); only the **left cell** renders
     * every digit. So:
     * - `0` → null (caller restores the real weekday table).
     * - `1..9` → left-aligned `"N "` (the digit in the legible left cell, blank right cell).
     * - `10`, `11` → `"10"`/`"11"` — the only two-digit counts whose units (`0`/`1`) the right cell
     *   renders cleanly.
     * - `12+` → `"11"`, i.e. "11 or more" — any higher count would land a garbled digit in the
     *   right cell.
     *
     * Every result is exactly two ASCII chars whose right cell is one of blank/`0`/`1`.
     */
    fun format(n: Int): String? = when {
        n <= 0 -> null
        n <= MAX_SINGLE_DIGIT_COUNT -> "$n "
        n <= MAX_TWO_DIGIT_COUNT -> n.toString()
        else -> "11"
    }

    /** The captured default weekday table (Mon..Sun), restored when the count is zero. */
    val REAL_WEEKDAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    /**
     * The weekday-table BLE packet for [n]: the formatted count in all 7 slots (so it shows
     * regardless of the current day), or the real weekday table when [n] is zero. [header] is
     * the register's World Time offset prefix — always pass the app-computed value
     * (WorldTimeHeader.fromPrefs); see issue #34.
     */
    fun packetFor(n: Int, header: ByteArray): ByteArray {
        val label = format(n)
        val slots = if (label == null) REAL_WEEKDAYS else List(WEEKDAY_SLOT_COUNT) { label }
        return OlleeProtocol.buildWeekdayPacket(slots, header)
    }
}
