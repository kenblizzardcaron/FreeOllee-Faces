package com.blizzardcaron.freeolleefaces.notifications

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.blizzardcaron.freeolleefaces.ble.AndroidBleClient
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Counts undismissed, non-persistent notifications and pushes the badge to the watch's
 * weekday slot. Requires the user to grant "Notification access" in system settings. Live
 * pushes are debounced (~2 s), capped at one BLE write per minute (trailing-edge — the
 * latest count lands at the minute boundary), and only happen while the overlay is enabled
 * ([Prefs.notificationsEnabled]) — independent of which name-tag face is active;
 * [com.blizzardcaron.freeolleefaces.auto.AutoUpdateWorker] is the periodic backstop.
 *
 * Two inherent limitations of the weekday slot (0x34), both unavoidable on this hardware:
 * - **Clock-face only:** the count renders only while the watch is on the Clock face; other
 *   firmware faces (Alarm/Timer/Stopwatch) show their own upper-panel content.
 * - **Shared register:** the official Ollee app writes the same 0x34 slot, so last-writer-wins —
 *   it can overwrite the count (and vice-versa). The worker backstop re-asserts on its cycle.
 */
class NotificationCountService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pushJob: Job? = null

    /** Monotonic time of the last BLE push; seeded so the first push waits only the debounce. */
    @Volatile
    private var lastPushMs = SystemClock.elapsedRealtime() - NotificationPushThrottle.MIN_INTERVAL_MS
    private val prefs by lazy { Prefs(appSettings(applicationContext)) }

    override fun onListenerConnected() {
        // Seed and sync on (re)bind, even if the count is unchanged.
        prefs.notificationCount = computeCount()
        schedulePush()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = recomputeAndPush()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recomputeAndPush()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun recomputeAndPush() {
        val count = computeCount()
        val changed = count != prefs.notificationCount
        prefs.notificationCount = count
        if (changed) schedulePush()
    }

    private fun computeCount(): Int {
        val active = activeNotifications ?: return prefs.notificationCount
        val mapped = active.map { sbn ->
            val flags = sbn.notification.flags
            NotificationCount.ActiveNotification(
                packageName = sbn.packageName,
                isClearable = sbn.isClearable,
                isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0,
                isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            )
        }
        return NotificationCount.countFrom(mapped, ownPackage = packageName)
    }

    private fun schedulePush() {
        // Debounce + throttle: a flurry collapses into one push, paced ≥1 min apart.
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(
                NotificationPushThrottle.delayFor(
                    nowMs = SystemClock.elapsedRealtime(),
                    lastPushMs = lastPushMs,
                    debounceMs = DEBOUNCE_MS,
                ),
            )
            if (!prefs.notificationsEnabled) return@launch
            val addr = prefs.watchAddress ?: return@launch
            AndroidBleClient(applicationContext)
                .sendPacket(addr, NotificationCount.packetFor(prefs.notificationCount))
            lastPushMs = SystemClock.elapsedRealtime()
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 2_000L
    }
}
