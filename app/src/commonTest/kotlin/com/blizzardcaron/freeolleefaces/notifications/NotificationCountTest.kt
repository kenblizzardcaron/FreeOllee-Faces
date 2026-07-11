package com.blizzardcaron.freeolleefaces.notifications

import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.worldtime.WorldTimeCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationCountTest {

    private fun n(
        pkg: String = "com.example.app",
        clearable: Boolean = true,
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
    ) = NotificationCount.ActiveNotification(pkg, clearable, ongoing, groupSummary)

    @Test fun countsOrdinaryNotifications() {
        val list = listOf(n(), n(pkg = "com.other"))
        assertEquals(2, NotificationCount.countFrom(list, ownPackage = "com.blizzardcaron.freeolleefaces"))
    }

    @Test fun excludesOngoing() {
        assertEquals(0, NotificationCount.countFrom(listOf(n(ongoing = true)), "com.me"))
    }

    @Test fun excludesNonClearable() {
        assertEquals(0, NotificationCount.countFrom(listOf(n(clearable = false)), "com.me"))
    }

    @Test fun excludesGroupSummary() {
        // A bundled app posts a summary + its children; only the children count.
        val list = listOf(n(groupSummary = true), n(), n())
        assertEquals(2, NotificationCount.countFrom(list, "com.me"))
    }

    @Test fun excludesOwnPackage() {
        val list = listOf(n(pkg = "com.blizzardcaron.freeolleefaces"), n(pkg = "com.other"))
        assertEquals(1, NotificationCount.countFrom(list, ownPackage = "com.blizzardcaron.freeolleefaces"))
    }

    @Test fun multipleFromOneAppEachCount() {
        val list = listOf(n(pkg = "com.chat"), n(pkg = "com.chat"), n(pkg = "com.chat"))
        assertEquals(3, NotificationCount.countFrom(list, "com.me"))
    }

    @Test fun formatZeroIsNull() {
        assertNull(NotificationCount.format(0))
    }

    // The weekday slot's RIGHT cell garbles several digits (2/5/9 -> 8/garbled, verified on
    // hardware); only the LEFT cell renders every digit. So single-digit counts are left-aligned
    // ("N " + blank) to keep the digit in the legible left cell.
    @Test fun formatSingleDigitIsLeftAligned() {
        assertEquals("1 ", NotificationCount.format(1))
        assertEquals("9 ", NotificationCount.format(9))
    }

    // 10 and 11 are the only two-digit counts whose units (0/1) the right cell renders cleanly.
    @Test fun formatTenAndElevenAreTwoDigit() {
        assertEquals("10", NotificationCount.format(10))
        assertEquals("11", NotificationCount.format(11))
    }

    // 12+ would land a garbled digit in the right cell, so the badge caps at "11" (= "11 or more").
    @Test fun formatCapsAtEleven() {
        assertEquals("11", NotificationCount.format(12))
        assertEquals("11", NotificationCount.format(99))
        assertEquals("11", NotificationCount.format(4321))
    }

    // Invariant: every badge is exactly two chars and its right cell only ever uses a glyph the
    // slot renders legibly (blank, 0, or 1).
    @Test fun formatRightCellIsAlwaysLegible() {
        val legibleRight = setOf(' ', '0', '1')
        for (count in 1..500) {
            val s = NotificationCount.format(count)!!
            assertEquals(2, s.length, "count $count -> '$s' is not 2 chars")
            assertTrue(s[1] in legibleRight, "count $count -> '$s' has an illegible right cell")
        }
    }

    @Test fun packetForCountFillsAllSevenSlots() {
        // 3 notifications -> left-aligned "3 " in every weekday slot.
        val header = WorldTimeCodec.encode(32_400)
        val expected = OlleeProtocol.buildWeekdayPacket(List(7) { "3 " }, header)
        assertContentEquals(expected, NotificationCount.packetFor(3, header))
    }

    @Test fun packetForZeroRestoresRealWeekdays() {
        val header = WorldTimeCodec.encode(32_400)
        val expected = OlleeProtocol.buildWeekdayPacket(NotificationCount.REAL_WEEKDAYS, header)
        assertContentEquals(expected, NotificationCount.packetFor(0, header))
    }

    @Test fun realWeekdaysAreTheCapturedDefault() {
        assertEquals(listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU"), NotificationCount.REAL_WEEKDAYS)
    }

    @Test
    fun packetForStampsTheGivenHeaderNotTheCaptureConstant() {
        val header = WorldTimeCodec.encode(-21_600)
        val packet = NotificationCount.packetFor(3, header)
        assertContentEquals(header, packet.copyOfRange(8, 12))
    }
}
