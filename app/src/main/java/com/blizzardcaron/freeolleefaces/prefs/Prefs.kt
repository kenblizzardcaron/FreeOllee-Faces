package com.blizzardcaron.freeolleefaces.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var lastLat: Double?
        get() = if (sp.contains(KEY_LAT)) sp.getFloat(KEY_LAT, 0f).toDouble() else null
        set(value) = sp.edit { if (value == null) remove(KEY_LAT) else putFloat(KEY_LAT, value.toFloat()) }

    var lastLng: Double?
        get() = if (sp.contains(KEY_LNG)) sp.getFloat(KEY_LNG, 0f).toDouble() else null
        set(value) = sp.edit { if (value == null) remove(KEY_LNG) else putFloat(KEY_LNG, value.toFloat()) }

    var watchAddress: String?
        get() = sp.getString(KEY_WATCH, null)
        set(value) = sp.edit { if (value == null) remove(KEY_WATCH) else putString(KEY_WATCH, value) }

    companion object {
        private const val FILE = "freeollee_faces_prefs"
        private const val KEY_LAT = "last_lat"
        private const val KEY_LNG = "last_lng"
        private const val KEY_WATCH = "watch_address"
    }
}
