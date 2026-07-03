package com.blizzardcaron.freeolleefaces.prefs

import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.auto.SleepWindow
import com.blizzardcaron.freeolleefaces.format.BatteryReadout
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.russhwolf.settings.MapSettings
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrefsTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun freshPrefs(vararg entries: Pair<String, Any>): Prefs {
        val map = mutableMapOf<String, Any>()
        for ((k, v) in entries) map[k] = v
        return Prefs(MapSettings(map))
    }

    private object FixedClock : Clock {
        val FIXED_MS = 1_700_000_000_000L
        override fun now(): Instant = Instant.fromEpochMilliseconds(FIXED_MS)
    }

    // ---------------------------------------------------------------------------
    // lastLat / lastLng  (Double? stored as Float)
    // ---------------------------------------------------------------------------

    @Test fun lastLat_unset_returnsNull() {
        val prefs = freshPrefs()
        assertNull(prefs.lastLat, "unset lastLat should be null")
    }

    @Test fun lastLat_writeRead_approxRoundtrip() {
        val prefs = freshPrefs()
        prefs.lastLat = 48.8566
        val readBack = prefs.lastLat
        assertTrue(readBack != null, "lastLat should not be null after set")
        assertTrue(
            abs(readBack - 48.8566) < 0.001,
            "lastLat roundtrip within Float precision: expected ~48.8566, got $readBack",
        )
    }

    @Test fun lastLat_nullAssignment_removesKey() {
        val prefs = freshPrefs()
        prefs.lastLat = 48.8566
        prefs.lastLat = null
        assertNull(prefs.lastLat, "lastLat should be null after assigning null")
    }

    @Test fun lastLng_unset_returnsNull() {
        val prefs = freshPrefs()
        assertNull(prefs.lastLng, "unset lastLng should be null")
    }

    @Test fun lastLng_writeRead_approxRoundtrip() {
        val prefs = freshPrefs()
        prefs.lastLng = 2.3522
        val readBack = prefs.lastLng
        assertTrue(readBack != null, "lastLng should not be null after set")
        assertTrue(
            abs(readBack - 2.3522) < 0.001,
            "lastLng roundtrip within Float precision: expected ~2.3522, got $readBack",
        )
    }

    @Test fun lastLng_nullAssignment_removesKey() {
        val prefs = freshPrefs()
        prefs.lastLng = 2.3522
        prefs.lastLng = null
        assertNull(prefs.lastLng, "lastLng should be null after assigning null")
    }

    // ---------------------------------------------------------------------------
    // tempUnit  (enum with default and unknown-string fallback)
    // ---------------------------------------------------------------------------

    @Test fun tempUnit_unset_defaultsFahrenheit() {
        val prefs = freshPrefs()
        assertEquals(TempUnit.FAHRENHEIT, prefs.tempUnit, "unset tempUnit should default to FAHRENHEIT")
    }

    @Test fun tempUnit_unknownStoredString_fallsBackToDefault() {
        val prefs = freshPrefs("temp_unit" to "KELVIN")
        assertEquals(TempUnit.FAHRENHEIT, prefs.tempUnit, "unknown tempUnit string should fall back to FAHRENHEIT")
    }

    @Test fun tempUnit_roundtrip_celsius() {
        val prefs = freshPrefs()
        prefs.tempUnit = TempUnit.CELSIUS
        assertEquals(TempUnit.CELSIUS, prefs.tempUnit, "tempUnit CELSIUS roundtrip")
    }

    @Test fun tempUnit_roundtrip_fahrenheit() {
        val prefs = freshPrefs()
        prefs.tempUnit = TempUnit.FAHRENHEIT
        assertEquals(TempUnit.FAHRENHEIT, prefs.tempUnit, "tempUnit FAHRENHEIT roundtrip")
    }

    // ---------------------------------------------------------------------------
    // activeComplication legacy migration
    // ---------------------------------------------------------------------------

    /** Seed "active_face" = "NOTIFICATIONS" — the migration should set notificationsEnabled=true,
     *  rewrite active_face to TEMPERATURE, and return TEMPERATURE. */
    @Test fun activeFace_legacyNotificationsMigration_setsEnabledAndFace() {
        val prefs = freshPrefs("active_face" to "NOTIFICATIONS")
        val face = prefs.activeComplication
        assertEquals(ActiveComplication.TEMPERATURE, face, "legacy NOTIFICATIONS face should migrate to TEMPERATURE")
        assertTrue(prefs.notificationsEnabled, "migration should enable notificationsEnabled")
    }

    /** After the migration the rewritten value should survive a second read without re-migrating. */
    @Test fun activeFace_legacyNotificationsMigration_persistsNewValue() {
        val settings = MapSettings(mutableMapOf<String, Any>("active_face" to "NOTIFICATIONS"))
        val prefs = Prefs(settings)
        prefs.activeComplication           // trigger migration
        val face2 = prefs.activeComplication
        assertEquals(ActiveComplication.TEMPERATURE, face2, "second read after migration should still be TEMPERATURE")
    }

    /** A stale legacy `auto_source` (incl. the retired "SUN") is ignored now and defaults to TEMPERATURE. */
    @Test fun activeFace_legacyAutoSource_sun_defaultsToTemperature() {
        val prefs = freshPrefs("auto_source" to "SUN")
        assertEquals(
            ActiveComplication.TEMPERATURE,
            prefs.activeComplication,
            "a stale auto_source=SUN should now default to TEMPERATURE",
        )
    }

    /** No active_face, no legacy key → defaults to TEMPERATURE. */
    @Test fun activeFace_noLegacy_defaultsToTemperature() {
        val prefs = freshPrefs()
        assertEquals(ActiveComplication.TEMPERATURE, prefs.activeComplication, "no stored face should default to TEMPERATURE")
    }

    @Test fun activeFace_roundtrip_steps() {
        val prefs = freshPrefs()
        prefs.activeComplication = ActiveComplication.STEPS
        assertEquals(ActiveComplication.STEPS, prefs.activeComplication, "activeComplication STEPS roundtrip")
    }

    // ---------------------------------------------------------------------------
    // tempFetchedMs  (Long?)
    // ---------------------------------------------------------------------------

    @Test fun tempFetchedMs_unset_returnsNull() {
        val prefs = freshPrefs()
        assertNull(prefs.tempFetchedMs, "unset tempFetchedMs should be null")
    }

    @Test fun tempFetchedMs_roundtrip() {
        val prefs = freshPrefs()
        prefs.tempFetchedMs = 1_699_000_000_000L
        assertEquals(1_699_000_000_000L, prefs.tempFetchedMs, "tempFetchedMs roundtrip")
    }

    @Test fun tempFetchedMs_nullAssignment_removesKey() {
        val prefs = freshPrefs()
        prefs.tempFetchedMs = 1_699_000_000_000L
        prefs.tempFetchedMs = null
        assertNull(prefs.tempFetchedMs, "tempFetchedMs should be null after assigning null")
    }

    // ---------------------------------------------------------------------------
    // watchAddress  (String?)
    // ---------------------------------------------------------------------------

    @Test fun watchAddress_unset_returnsNull() {
        val prefs = freshPrefs()
        assertNull(prefs.watchAddress, "unset watchAddress should be null")
    }

    @Test fun watchAddress_roundtrip() {
        val prefs = freshPrefs()
        prefs.watchAddress = "AA:BB:CC:DD:EE:FF"
        assertEquals("AA:BB:CC:DD:EE:FF", prefs.watchAddress, "watchAddress roundtrip")
    }

    @Test fun watchAddress_nullAssignment_removesKey() {
        val prefs = freshPrefs()
        prefs.watchAddress = "AA:BB:CC:DD:EE:FF"
        prefs.watchAddress = null
        assertNull(prefs.watchAddress, "watchAddress should be null after assigning null")
    }

    // ---------------------------------------------------------------------------
    // recordTempFetch  (fixed Clock injection)
    // ---------------------------------------------------------------------------

    @Test fun recordTempFetch_stampsFixedInstantMs() {
        val settings = MapSettings()
        val prefs = Prefs(settings, clock = FixedClock)
        prefs.recordTempFetch(72.0, TempUnit.FAHRENHEIT)
        assertEquals(FixedClock.FIXED_MS, prefs.tempFetchedMs, "recordTempFetch should store clock's epoch ms")
        assertEquals(TempUnit.FAHRENHEIT, prefs.tempCacheUnit, "recordTempFetch should store the supplied unit")
        // tempValue is stored as Float, so allow for small precision loss
        val tv = prefs.tempValue
        assertTrue(tv != null, "tempValue should not be null after recordTempFetch")
        assertTrue(abs(tv - 72.0) < 0.001, "recordTempFetch should store the supplied temperature: got $tv")
    }

    // ---------------------------------------------------------------------------
    // quickTimerSeconds  (Int with default 180 and negative clamping)
    // ---------------------------------------------------------------------------

    @Test fun `quickTimerSeconds defaults to 180 and round-trips, clamping negatives to zero`() {
        val settings = MapSettings()
        val prefs = Prefs(settings)
        assertEquals(180, prefs.quickTimerSeconds)        // default 03:00

        prefs.quickTimerSeconds = 427                      // 07:07
        assertEquals(427, Prefs(settings).quickTimerSeconds)

        prefs.quickTimerSeconds = -5                        // clamped
        assertEquals(0, prefs.quickTimerSeconds)
    }

    // ---------------------------------------------------------------------------
    // quickTimerAlarmMode / quickTimerAlarmHour / quickTimerAlarmMinute
    // ---------------------------------------------------------------------------

    @Test
    fun `quickTimerAlarmMode defaults to false and round-trips`() {
        val settings = MapSettings()
        assertEquals(false, Prefs(settings).quickTimerAlarmMode)
        Prefs(settings).quickTimerAlarmMode = true
        assertEquals(true, Prefs(settings).quickTimerAlarmMode)
    }

    @Test
    fun `quickTimerAlarmHour defaults to 7 and round-trips`() {
        val settings = MapSettings()
        assertEquals(7, Prefs(settings).quickTimerAlarmHour)
        Prefs(settings).quickTimerAlarmHour = 22
        assertEquals(22, Prefs(settings).quickTimerAlarmHour)
    }

    @Test
    fun `quickTimerAlarmMinute defaults to 0 and round-trips`() {
        val settings = MapSettings()
        assertEquals(0, Prefs(settings).quickTimerAlarmMinute)
        Prefs(settings).quickTimerAlarmMinute = 45
        assertEquals(45, Prefs(settings).quickTimerAlarmMinute)
    }

    // ---------------------------------------------------------------------------
    // unified power saving
    // ---------------------------------------------------------------------------

    @Test fun powerSaving_defaults() {
        val prefs = freshPrefs()
        assertEquals(true, prefs.powerSavingEnabled)
        assertEquals(120, prefs.screenSleepTimeoutSec)
        assertEquals(true, prefs.quietHoursEnabled)
        assertEquals(22 * 60, prefs.quietHoursStartMin)
        assertEquals(7 * 60, prefs.quietHoursEndMin)
    }

    @Test fun powerSaving_off_configDisabled_andNoPushPause() {
        val prefs = freshPrefs()
        prefs.powerSavingEnabled = false
        assertEquals(false, prefs.autoSleepWindowConfig().enabled)
        assertNull(prefs.pushPauseWindow())
    }

    @Test fun powerSaving_on_quietOff_sleeps24x7_andNoPushPause() {
        val prefs = freshPrefs()
        prefs.powerSavingEnabled = true
        prefs.quietHoursEnabled = false
        prefs.screenSleepTimeoutSec = 30
        val cfg = prefs.autoSleepWindowConfig()
        assertEquals(true, cfg.enabled)
        assertEquals(AutoSleepProfile(autoSleepOn = true, periodSec = 30), cfg.inWindow)
        assertEquals(cfg.inWindow, cfg.outWindow)
        assertNull(prefs.pushPauseWindow())
    }

    @Test fun powerSaving_on_quietOn_sleepInWindow_stayOnOutside_andPushPause() {
        val prefs = freshPrefs()
        prefs.powerSavingEnabled = true
        prefs.quietHoursEnabled = true
        prefs.quietHoursStartMin = 23 * 60
        prefs.quietHoursEndMin = 6 * 60
        prefs.screenSleepTimeoutSec = 60
        val cfg = prefs.autoSleepWindowConfig()
        assertEquals(true, cfg.enabled)
        assertEquals(23 * 60, cfg.startMin)
        assertEquals(6 * 60, cfg.endMin)
        assertEquals(AutoSleepProfile(autoSleepOn = true, periodSec = 60), cfg.inWindow)
        assertEquals(false, cfg.outWindow.autoSleepOn)
        assertEquals(SleepWindow(23 * 60, 6 * 60), prefs.pushPauseWindow())
    }

    @Test fun construction_purgesLegacyPowerKeys() {
        val settings = MapSettings()
        settings.putBoolean("sleep_enabled", false)
        settings.putInt("auto_sleep_in_period_sec", 30)
        settings.putInt("auto_sleep_window_start_min", 1)
        Prefs(settings)
        assertEquals(false, settings.hasKey("sleep_enabled"))
        assertEquals(false, settings.hasKey("auto_sleep_in_period_sec"))
        assertEquals(false, settings.hasKey("auto_sleep_window_start_min"))
    }

    // ---------------------------------------------------------------------------
    // activity-mode prefs
    // ---------------------------------------------------------------------------

    @Test fun activityActive_defaultsFalse_andRoundTrips() {
        val prefs = freshPrefs()
        assertEquals(false, prefs.activityActive)
        prefs.activityActive = true
        assertEquals(true, prefs.activityActive)
    }

    @Test fun activityUnit_defaultsImperial_andRoundTrips() {
        val prefs = freshPrefs()
        assertEquals(com.blizzardcaron.freeolleefaces.activity.ActivityUnit.IMPERIAL, prefs.activityUnit)
        prefs.activityUnit = com.blizzardcaron.freeolleefaces.activity.ActivityUnit.METRIC
        assertEquals(com.blizzardcaron.freeolleefaces.activity.ActivityUnit.METRIC, prefs.activityUnit)
    }

    @Test fun savedAutoSleepProfile_nullByDefault_andRoundTrips() {
        val prefs = freshPrefs()
        assertNull(prefs.savedAutoSleepProfile)
        prefs.savedAutoSleepProfile = AutoSleepProfile(autoSleepOn = true, periodSec = 30)
        assertEquals(AutoSleepProfile(true, 30), prefs.savedAutoSleepProfile)
        prefs.savedAutoSleepProfile = null
        assertNull(prefs.savedAutoSleepProfile)
    }

    @Test fun batteryReadout_defaultsToPercent() {
        assertEquals(BatteryReadout.PERCENT, freshPrefs().batteryReadout)
    }

    @Test fun batteryReadout_roundtrips() {
        val prefs = freshPrefs()
        prefs.batteryReadout = BatteryReadout.VOLTS
        assertEquals(BatteryReadout.VOLTS, prefs.batteryReadout)
    }

    @Test fun batteryValueMv_unset_isNull() {
        assertNull(freshPrefs().batteryValueMv)
    }

    @Test fun recordBatteryFetch_persistsValueAndTimestamp() {
        val prefs = Prefs(MapSettings(), FixedClock)
        prefs.recordBatteryFetch(2850)
        assertEquals(2850, prefs.batteryValueMv)
        assertEquals(FixedClock.FIXED_MS, prefs.batteryFetchedMs)
    }

    // ---------------------------------------------------------------------------
    // activity push interval and auto-pause threshold
    // ---------------------------------------------------------------------------

    @Test
    fun testPushIntervalDefaultsTo30s() {
        assertEquals(30_000L, freshPrefs().activityPushIntervalMs)
    }

    @Test
    fun testPushIntervalRoundTrips() {
        val prefs = freshPrefs()
        prefs.activityPushIntervalMs = 60_000L
        assertEquals(60_000L, prefs.activityPushIntervalMs)
    }

    @Test
    fun testAutoPauseThresholdDefaults() {
        assertEquals(0.1f, freshPrefs().autoPauseThresholdMps)
    }

    @Test
    fun testAutoPauseThresholdRoundTrips() {
        val prefs = freshPrefs()
        prefs.autoPauseThresholdMps = 0.25f
        assertEquals(0.25f, prefs.autoPauseThresholdMps)
    }

    @Test
    fun testPresetsAreTheFiveLockedValues() {
        assertEquals(
            listOf(3_000L, 15_000L, 30_000L, 60_000L, 300_000L),
            Prefs.PUSH_INTERVAL_PRESETS_MS,
        )
    }
}
