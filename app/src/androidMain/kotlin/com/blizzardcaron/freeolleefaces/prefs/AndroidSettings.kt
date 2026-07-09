package com.blizzardcaron.freeolleefaces.prefs

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * [Settings] backed by the app's main SharedPreferences file. The file name must stay
 * `"freeollee_faces_prefs"` forever — existing users' on-disk data lives there.
 */
fun appSettings(context: Context): Settings {
    val sp = context.applicationContext.getSharedPreferences("freeollee_faces_prefs", Context.MODE_PRIVATE)
    return SharedPreferencesSettings(sp)
}

/**
 * [Settings] backed by the dedicated timer-sets SharedPreferences file. The file name must stay
 * `"timer_sets"` forever — existing users' saved timer sets live there.
 */
fun timerSettings(context: Context): Settings =
    SharedPreferencesSettings(context.applicationContext.getSharedPreferences("timer_sets", Context.MODE_PRIVATE))
