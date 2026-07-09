package com.blizzardcaron.freeolleefaces.ui

/** The four top-level tabs in the bottom navigation bar, in display order. */
enum class BottomNavTab(val screen: Screen, val label: String, val glyph: String) {
    Complications(Screen.Home, "Complications", "▦"),
    Activity(Screen.Activity, "Activity", "🏃"),
    Timer(Screen.TimerSets, "Timer", "⏱"),
    Settings(Screen.Settings, "Settings", "⚙");

    companion object {
        /** The tab that should read as selected for a screen, or null if it is not a
         *  top-level tab (e.g. TimerSetEdit, a pushed child of Timer). */
        fun forScreen(screen: Screen): BottomNavTab? = entries.firstOrNull { it.screen == screen }

        /** Whether the bottom navigation bar is shown for this screen. */
        fun showsBottomBar(screen: Screen): Boolean = screen != Screen.TimerSetEdit
    }
}
