package com.blizzardcaron.freeolleefaces.auto

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.ble.AndroidBleClient
import com.blizzardcaron.freeolleefaces.ble.AutoSleepApply
import com.blizzardcaron.freeolleefaces.ble.BatteryReadback
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.health.AndroidStepsProvider
import com.blizzardcaron.freeolleefaces.notifications.AndroidNotificationAccess
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
import com.blizzardcaron.freeolleefaces.notify.ErrorNotifier
import com.blizzardcaron.freeolleefaces.notify.FailureKind
import com.blizzardcaron.freeolleefaces.notify.NotifyAction
import com.blizzardcaron.freeolleefaces.notify.NotifyDecision
import com.blizzardcaron.freeolleefaces.notify.StepsFailureClassifier
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import com.blizzardcaron.freeolleefaces.ring.AndroidRingStepsSource
import com.blizzardcaron.freeolleefaces.ring.stepsIfEnabled
import com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient
import com.blizzardcaron.freeolleefaces.weather.RetryPolicy
import com.blizzardcaron.freeolleefaces.weather.WeatherFetchError
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Performs one due send for the selected source, then enqueues the next chain run.
 * Reads all state from Prefs; uses the saved coordinates (no location fix).
 */
class AutoUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val MINUTES_PER_HOUR = 60

        const val KEY_SEND_ATTEMPT = "send_attempt"
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        JobDiagnostics.logPendingJobReasons(ctx)
        val prefs = Prefs(appSettings(ctx))

        val face = prefs.activeComplication
        val lat = prefs.lastLat
        val lng = prefs.lastLng
        val address = prefs.watchAddress

        // The notification count is an independent overlay on the weekday slot, decoupled from the
        // name-tag face. Best-effort backstop to the listener's live pushes; it piggybacks on
        // whatever schedule the active face arms and never affects that face's success/scheduling.
        maybePushNotificationCount(ctx, prefs, address)
        maybeReconcileAutoSleep(ctx, prefs, address)
        val now = nowLocal()

        return when {
            // CUSTOM has no schedule; clear any stale error notification and stop the chain.
            face == ActiveComplication.CUSTOM -> {
                applyHealth(ctx, prefs, null, inSleep = false)
                Result.success()
            }

            // STEPS and BATTERY need only a watch (no coordinates); handle before the location guard.
            (face == ActiveComplication.STEPS || face == ActiveComplication.BATTERY) && address == null -> {
                prefs.recordAutoSend("Skipped: set watch in app")
                applyHealth(ctx, prefs, FailureKind.SETUP_INCOMPLETE, inSleepNow(prefs))
                Result.success()
            }

            face == ActiveComplication.STEPS -> runSteps(ctx, prefs, address!!, now)

            face == ActiveComplication.BATTERY -> runBattery(ctx, prefs, address!!, now)

            lat == null || lng == null || address == null -> {
                prefs.recordAutoSend("Skipped: set location/watch in app")
                applyHealth(ctx, prefs, FailureKind.SETUP_INCOMPLETE, inSleepNow(prefs))
                Result.success()
            }

            face == ActiveComplication.TEMPERATURE -> runTemperature(ctx, prefs, lat, lng, address, now)
            face == ActiveComplication.PRESSURE -> runPressure(ctx, prefs, lat, lng, address, now)
            face == ActiveComplication.ALTITUDE -> runAltitude(ctx, prefs, lat, lng, address, now)

            else -> Result.success() // STEPS/CUSTOM already handled above
        }
    }

    /**
     * Best-effort re-push of the notification overlay to the weekday slot when it's enabled, the
     * watch is set, and we're awake. Fire-and-forget: it must not touch the active face's failure
     * accounting or re-arm logic (the live listener service is the primary path).
     *
     * Self-heals the orphaned-count case: if the overlay is enabled but notification access has
     * been revoked, the live service is dead and the count can no longer update — so instead of
     * re-asserting a stale number we restore the real weekday table (`packetFor(0)`).
     */
    private suspend fun maybePushNotificationCount(ctx: Context, prefs: Prefs, address: String?) {
        if (!prefs.notificationsEnabled || address == null || inSleepNow(prefs)) return
        val count = if (AndroidNotificationAccess(ctx).isGranted()) prefs.notificationCount else 0
        runCatching {
            AndroidBleClient(ctx).sendPacket(address, NotificationCount.packetFor(count))
        }
    }

    /**
     * Best-effort reconcile of the watch's auto-sleep register to the scheduled desired state.
     * Independent of the active complication; no-op when the schedule is disabled or no watch is set.
     * Fire-and-forget: never affects face scheduling or failure accounting.
     */
    private suspend fun maybeReconcileAutoSleep(ctx: Context, prefs: Prefs, address: String?) {
        if (address == null) return
        val now = nowLocal()
        val desired = AutoSleepReconciler.desiredProfile(
            prefs.autoSleepWindowConfig(), now.hour * MINUTES_PER_HOUR + now.minute,
        ) ?: return
        runCatching { AutoSleepApply.reconcile(AndroidBleClient(ctx), address, desired) }
    }

    private suspend fun runSteps(
        ctx: Context,
        prefs: Prefs,
        address: String,
        now: LocalDateTime,
    ): Result {
        val sleep = prefs.pushPauseWindow()
        val nowMinOfDay = now.hour * MINUTES_PER_HOUR + now.minute
        val inSleep = sleep != null &&
            AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, sleep.startMin, sleep.endMin)

        // Set true only inside the !inSleep block when handleSendFailure enqueues a backstop
        // retry; the asleep branch never writes it. Guards the normal re-arm below so a failed
        // run enqueues either a backstop or the normal next run, never both.
        var backstopped = false
        if (!inSleep) {
            // Same ring merge as ComplicationController.refreshSteps: without it, this scheduled
            // push would flip the watch back to the HC-only count between foreground refreshes.
            val ring = AndroidRingStepsSource(ctx) { prefs.ringConnAddress }.stepsIfEnabled(prefs)
            AndroidStepsProvider(ctx).todaySteps()
                .onSuccess { count ->
                    val display = maxOf(count, ring ?: count)
                    prefs.recordStepsFetch(display)
                    val payload = DisplayFormatter.steps(display)
                    AndroidBleClient(ctx).send(address, payload)
                        .onSuccess {
                            prefs.recordAutoSend("Sent '$payload'")
                            applyHealth(ctx, prefs, null, inSleep)
                        }
                        .onFailure {
                            backstopped = handleSendFailure(
                                ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep,
                            )
                        }
                }
                .onFailure { error ->
                    val kind = StepsFailureClassifier.kindFor(error)
                    // A live ring read rescues an HC failure as a fresh (non-stale) push,
                    // matching the foreground behavior; otherwise fall back to cached-stale.
                    if (ring != null) prefs.recordStepsFetch(ring)
                    val fallback = ring ?: prefs.lastStepCount
                    val stale = ring == null
                    if (fallback != null) {
                        // Ring value pushes fresh; a cached count is marked stale ('E').
                        val payload = DisplayFormatter.steps(fallback, stale = stale)
                        AndroidBleClient(ctx).send(address, payload)
                            .onSuccess {
                                prefs.recordAutoSend(if (stale) "Sent stale '$payload'" else "Sent '$payload'")
                            }
                            .onFailure {
                                backstopped = handleSendFailure(
                                    ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep,
                                )
                            }
                    } else {
                        prefs.recordAutoSend("Skipped: steps read failed (will retry)")
                    }
                    // A genuine access gap is still actionable — surface it regardless of the
                    // stale push above. Transient glitches (kind == null) stay quiet.
                    if (kind != null) applyHealth(ctx, prefs, kind, inSleep)
                }
        } else {
            prefs.recordAutoSend("Asleep (power saving)")
        }

        if (!backstopped) {
            val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.updateIntervalMinutes, sleep)
            val delayMs = millisBetween(now, fire)
            AutoUpdateScheduler.enqueueNext(ctx, delayMs, sendAttempt = 0)
        }
        return Result.success()
    }

    private suspend fun runBattery(
        ctx: Context,
        prefs: Prefs,
        address: String,
        now: LocalDateTime,
    ): Result {
        val sleep = prefs.pushPauseWindow()
        val nowMinOfDay = now.hour * MINUTES_PER_HOUR + now.minute
        val inSleep = sleep != null &&
            AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, sleep.startMin, sleep.endMin)
        var backstopped = false
        if (!inSleep) {
            val ble = AndroidBleClient(ctx)
            val mv = BatteryReadback.read(ble, address)
            if (mv != null) {
                prefs.recordBatteryFetch(mv)
                val payload = DisplayFormatter.battery(mv, prefs.batteryReadout)
                ble.send(address, payload)
                    .onSuccess {
                        prefs.recordAutoSend("Sent '$payload'")
                        applyHealth(ctx, prefs, null, inSleep)
                    }
                    .onFailure {
                        backstopped = handleSendFailure(ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep)
                    }
            } else {
                // Read failed (link loss / malformed). Push the last cached voltage marked stale; if
                // there's no cache, the watch link is the dependency -> treat as unreachable.
                val cached = prefs.batteryValueMv
                if (cached != null) {
                    val payload = DisplayFormatter.battery(cached, prefs.batteryReadout, stale = true)
                    ble.send(address, payload)
                        .onSuccess { prefs.recordAutoSend("Sent stale '$payload'") }
                        .onFailure {
                            backstopped = handleSendFailure(ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep)
                        }
                } else {
                    backstopped = handleSendFailure(ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep)
                }
            }
        } else {
            prefs.recordAutoSend("Asleep (power saving)")
        }
        if (!backstopped) {
            val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.updateIntervalMinutes, sleep)
            AutoUpdateScheduler.enqueueNext(ctx, millisBetween(now, fire), sendAttempt = 0)
        }
        return Result.success()
    }

    private suspend fun runTemperature(
        ctx: Context,
        prefs: Prefs,
        lat: Double,
        lng: Double,
        address: String,
        now: LocalDateTime,
    ): Result {
        val sleep = prefs.pushPauseWindow()
        val nowMinOfDay = now.hour * MINUTES_PER_HOUR + now.minute

        // Guard: if we somehow fired inside the sleep window, skip the send.
        val inSleep = sleep != null &&
            AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, sleep.startMin, sleep.endMin)
        // Set true only inside the !inSleep block when handleSendFailure enqueues a backstop
        // retry; the asleep branch never writes it. Guards the normal re-arm below so a failed
        // run enqueues either a backstop or the normal next run, never both.
        var backstopped = false
        if (!inSleep) {
            OpenMeteoClient.currentTemp(lat, lng, prefs.tempUnit, RetryPolicy.Background)
                .onSuccess { temp ->
                    prefs.recordTempFetch(temp, prefs.tempUnit)
                    val payload = DisplayFormatter.temperature(temp, prefs.tempUnit)
                    AndroidBleClient(ctx).send(address, payload)
                        .onSuccess {
                            prefs.recordAutoSend("Sent '$payload'")
                            applyHealth(ctx, prefs, null, inSleep)
                        }
                        .onFailure {
                            backstopped = handleSendFailure(
                                ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep,
                            )
                        }
                }
                .onFailure { err ->
                    val suffix = (err as? WeatherFetchError)?.statusCode?.let { " (HTTP $it)" } ?: ""
                    val cached = prefs.tempValue
                    if (cached != null && prefs.tempCacheUnit == prefs.tempUnit) {
                        // Fetch failed but we have a cached temp — push it marked stale ('E').
                        val payload = DisplayFormatter.temperature(cached, prefs.tempUnit, stale = true)
                        AndroidBleClient(ctx).send(address, payload)
                            .onSuccess {
                                prefs.recordAutoSend("Sent stale '$payload'$suffix")
                                // The BLE send succeeded, but the *fetch* failed — the watch is now
                                // showing a stale 'E' value. Surface that as WEATHER_FETCH_FAILED;
                                // reporting healthy (null) here would suppress the notice and even
                                // clear one already showing, leaving the 'E' silently unexplained.
                                applyHealth(ctx, prefs, FailureKind.WEATHER_FETCH_FAILED, inSleep)
                            }
                            .onFailure {
                                backstopped = handleSendFailure(
                                    ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep,
                                )
                            }
                    } else {
                        prefs.recordAutoSend("Skipped: weather fetch failed$suffix")
                        applyHealth(ctx, prefs, FailureKind.WEATHER_FETCH_FAILED, inSleep)
                    }
                }
        } else {
            prefs.recordAutoSend("Asleep (power saving)")
        }

        if (!backstopped) {
            val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.updateIntervalMinutes, sleep)
            val delayMs = millisBetween(now, fire)
            AutoUpdateScheduler.enqueueNext(ctx, delayMs, sendAttempt = 0)
        }
        return Result.success()
    }

    private suspend fun runPressure(
        ctx: Context,
        prefs: Prefs,
        lat: Double,
        lng: Double,
        address: String,
        now: LocalDateTime,
    ): Result {
        val sleep = prefs.pushPauseWindow()
        val nowMinOfDay = now.hour * MINUTES_PER_HOUR + now.minute
        val inSleep = sleep != null &&
            AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, sleep.startMin, sleep.endMin)
        val imperial = prefs.activityUnit == ActivityUnit.IMPERIAL
        var backstopped = false
        if (!inSleep) {
            OpenMeteoClient.currentPressureHpa(lat, lng, RetryPolicy.Background)
                .onSuccess { hpa ->
                    prefs.recordPressureFetch(hpa)
                    val payload = DisplayFormatter.pressure(hpa, imperial)
                    AndroidBleClient(ctx).send(address, payload)
                        .onSuccess {
                            prefs.recordAutoSend("Sent '$payload'")
                            applyHealth(ctx, prefs, null, inSleep)
                        }
                        .onFailure {
                            backstopped = handleSendFailure(ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep)
                        }
                }
                .onFailure { err ->
                    val suffix = (err as? WeatherFetchError)?.statusCode?.let { " (HTTP $it)" } ?: ""
                    val cached = prefs.pressureValueHpa
                    if (cached != null) {
                        val payload = DisplayFormatter.pressure(cached, imperial, stale = true)
                        AndroidBleClient(ctx).send(address, payload)
                            .onSuccess {
                                prefs.recordAutoSend("Sent stale '$payload'$suffix")
                                applyHealth(ctx, prefs, FailureKind.WEATHER_FETCH_FAILED, inSleep)
                            }
                            .onFailure {
                                backstopped = handleSendFailure(ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep)
                            }
                    } else {
                        prefs.recordAutoSend("Skipped: pressure fetch failed$suffix")
                        applyHealth(ctx, prefs, FailureKind.WEATHER_FETCH_FAILED, inSleep)
                    }
                }
        } else {
            prefs.recordAutoSend("Asleep (power saving)")
        }
        if (!backstopped) {
            val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.updateIntervalMinutes, sleep)
            AutoUpdateScheduler.enqueueNext(ctx, millisBetween(now, fire), sendAttempt = 0)
        }
        return Result.success()
    }

    private suspend fun runAltitude(
        ctx: Context,
        prefs: Prefs,
        lat: Double,
        lng: Double,
        address: String,
        now: LocalDateTime,
    ): Result {
        val sleep = prefs.pushPauseWindow()
        val nowMinOfDay = now.hour * MINUTES_PER_HOUR + now.minute
        val inSleep = sleep != null &&
            AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, sleep.startMin, sleep.endMin)
        val imperial = prefs.activityUnit == ActivityUnit.IMPERIAL
        var backstopped = false
        if (!inSleep) {
            OpenMeteoClient.currentElevationM(lat, lng, RetryPolicy.Background)
                .onSuccess { meters ->
                    prefs.recordAltitudeFetch(meters)
                    val payload = DisplayFormatter.altitude(meters, imperial)
                    AndroidBleClient(ctx).send(address, payload)
                        .onSuccess {
                            prefs.recordAutoSend("Sent '$payload'")
                            applyHealth(ctx, prefs, null, inSleep)
                        }
                        .onFailure {
                            backstopped = handleSendFailure(ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep)
                        }
                }
                .onFailure { err ->
                    val suffix = (err as? WeatherFetchError)?.statusCode?.let { " (HTTP $it)" } ?: ""
                    val cached = prefs.altitudeValueM
                    if (cached != null) {
                        val payload = DisplayFormatter.altitude(cached, imperial, stale = true)
                        AndroidBleClient(ctx).send(address, payload)
                            .onSuccess {
                                prefs.recordAutoSend("Sent stale '$payload'$suffix")
                                applyHealth(ctx, prefs, FailureKind.WEATHER_FETCH_FAILED, inSleep)
                            }
                            .onFailure {
                                backstopped = handleSendFailure(ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep)
                            }
                    } else {
                        prefs.recordAutoSend("Skipped: elevation fetch failed$suffix")
                        applyHealth(ctx, prefs, FailureKind.WEATHER_FETCH_FAILED, inSleep)
                    }
                }
        } else {
            prefs.recordAutoSend("Asleep (power saving)")
        }
        if (!backstopped) {
            val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.updateIntervalMinutes, sleep)
            AutoUpdateScheduler.enqueueNext(ctx, millisBetween(now, fire), sendAttempt = 0)
        }
        return Result.success()
    }

    /**
     * A watch send failed. While budget remains, re-enqueue a backstop run with backoff and post
     * no notification (returns true — caller must NOT also enqueue the normal next run). Once the
     * budget is exhausted, surface [kind] via the notification path and return false so the caller
     * resumes the normal chain. The attempt count is carried in the worker's input data.
     */
    private fun handleSendFailure(
        ctx: Context,
        prefs: Prefs,
        kind: FailureKind,
        inSleep: Boolean,
    ): Boolean {
        val attempt = inputData.getInt(KEY_SEND_ATTEMPT, 0)
        return if (AutoUpdateSchedule.hasBackstopBudget(attempt)) {
            prefs.recordAutoSend("Retry ${attempt + 1}: watch unreachable")
            AutoUpdateScheduler.enqueueNext(
                ctx,
                AutoUpdateSchedule.backstopDelayMs(attempt),
                sendAttempt = attempt + 1,
            )
            true
        } else {
            prefs.recordAutoSend("Skipped: watch unreachable")
            applyHealth(ctx, prefs, kind, inSleep)
            false
        }
    }

    private fun applyHealth(ctx: Context, prefs: Prefs, kind: FailureKind?, inSleep: Boolean) {
        when (val action = NotifyDecision.decide(kind, prefs.lastNotifiedKind, inSleep)) {
            is NotifyAction.Notify -> {
                // Only record it as shown if it actually posted; otherwise (permission not yet
                // granted) leave the state untouched so a later grant still surfaces the failure.
                if (ErrorNotifier.notify(ctx, action.kind)) {
                    prefs.lastNotifiedKind = action.kind
                }
            }
            NotifyAction.Clear -> {
                ErrorNotifier.clear(ctx)
                prefs.lastNotifiedKind = null
            }
            NotifyAction.Nothing -> {}
        }
    }

    private fun inSleepNow(prefs: Prefs): Boolean {
        val sleep = prefs.pushPauseWindow() ?: return false
        val now = nowLocal()
        val m = now.hour * MINUTES_PER_HOUR + now.minute
        return AutoUpdateSchedule.isInSleepWindow(m, sleep.startMin, sleep.endMin)
    }

    private fun nowLocal(): LocalDateTime =
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    /** Wall-clock millis from [from] to [to], localized to the system zone, clamped at 0. */
    private fun millisBetween(from: LocalDateTime, to: LocalDateTime): Long {
        val zone = TimeZone.currentSystemDefault()
        val diff = to.toInstant(zone).toEpochMilliseconds() - from.toInstant(zone).toEpochMilliseconds()
        return diff.coerceAtLeast(0)
    }
}
