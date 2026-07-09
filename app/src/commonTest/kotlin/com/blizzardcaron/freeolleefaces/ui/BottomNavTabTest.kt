package com.blizzardcaron.freeolleefaces.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BottomNavTabTest {
    @Test
    fun forScreen_maps_top_level_screens() {
        assertEquals(BottomNavTab.Complications, BottomNavTab.forScreen(Screen.Home))
        assertEquals(BottomNavTab.Activity, BottomNavTab.forScreen(Screen.Activity))
        assertEquals(BottomNavTab.Timer, BottomNavTab.forScreen(Screen.TimerSets))
        assertEquals(BottomNavTab.Settings, BottomNavTab.forScreen(Screen.Settings))
    }

    @Test
    fun forScreen_returns_null_for_pushed_subscreen() {
        assertNull(BottomNavTab.forScreen(Screen.TimerSetEdit))
    }

    @Test
    fun bottom_bar_hidden_only_on_edit_subscreen() {
        assertTrue(BottomNavTab.showsBottomBar(Screen.Home))
        assertTrue(BottomNavTab.showsBottomBar(Screen.Settings))
        assertFalse(BottomNavTab.showsBottomBar(Screen.TimerSetEdit))
    }
}
