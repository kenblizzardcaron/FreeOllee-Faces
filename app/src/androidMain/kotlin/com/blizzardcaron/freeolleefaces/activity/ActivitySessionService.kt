package com.blizzardcaron.freeolleefaces.activity

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blizzardcaron.freeolleefaces.R
import com.blizzardcaron.freeolleefaces.ble.AndroidBleClient
import com.blizzardcaron.freeolleefaces.location.AndroidLocationStream
import com.blizzardcaron.freeolleefaces.location.Coords
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient
import com.blizzardcaron.freeolleefaces.weather.RetryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts an [ActivitySessionEngine] for the lifetime of an activity. Owns
 * the location-collect loop and the 1s push/flush ticker; routes control via start intents.
 */
class ActivitySessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var engine: ActivitySessionEngine
    private var loops: Job? = null

    // Latest GPS fix, used by the network-pressure fallback when the phone has no barometer.
    @Volatile
    private var lastCoords: Coords? = null

    // Throttle state for the foreground notification. The engine emits faster than 1 Hz (sensor
    // samples), and posting every emission trips Android 17's NotificationManager rate limiter,
    // which sheds the excess and makes the promoted Live Update chip flicker / show stale values.
    private var lastNotifyAtMs = 0L
    private var lastNotifiedText: String? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(appSettings(this))
        val ble = AndroidBleClient(this)
        engine = ActivitySessionEngine(
            ble = ble,
            store = AndroidActivityTrackStore(this),
            prefs = prefs,
            autoSleep = ActivityAutoSleepManager(ble, prefs),
            watchAddress = { prefs.watchAddress },
            now = { System.currentTimeMillis() },
            metricsConfig = { ActivityMetricsRepository(appSettings(this)).get() },
        )
        engine.state
            .onEach {
                ActivitySessionHost.mutableState.value = it
                updateNotification(it)
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> stopSession(abnormal = false)
            ACTION_CYCLE -> engine.cycleMetric()
            ACTION_SET_UNIT -> engine.setUnit(prefs.activityUnit)
            ACTION_PAUSE -> engine.pause(System.currentTimeMillis())
            ACTION_RESUME -> engine.resume(System.currentTimeMillis())
        }
        return START_STICKY
    }

    // The location permission is gated upstream by ActivityController.onStart() — the engine
    // never reaches the service's ACTION_START without ACCESS_FINE_LOCATION already granted.
    // A session is always a recording, so ACTION_START while one is already running is a no-op.
    private fun startSession() {
        if (ActivitySessionHost.isRunning) return
        ActivitySessionHost.isRunning = true
        startForegroundCompat()
        loops = launchLoops { engine.start() }
    }

    @SuppressLint("MissingPermission")
    private fun launchLoops(startEngine: suspend () -> Unit): Job = scope.launch {
        startEngine()
        val location = launch {
            AndroidLocationStream(this@ActivitySessionService).stream()
                .onEach {
                    lastCoords = it
                    engine.ingest(it, System.currentTimeMillis())
                }
                .launchIn(this)
        }
        val ticker = launch {
            var ticks = 0
            while (isActive) {
                engine.tick(System.currentTimeMillis())
                if (++ticks % FLUSH_EVERY_TICKS == 0) engine.flush()
                delay(TICK_MS)
            }
        }
        val pressure = launch { drivePressure() }
        location.join()
        ticker.join()
        pressure.join()
    }

    // Barometric pressure for the recorded PRESSURE metric: prefer the phone sensor (live, local);
    // if there's no barometer, fall back to network surface pressure at the latest GPS fix.
    private suspend fun drivePressure() = kotlinx.coroutines.coroutineScope {
        var sawSensor = false
        val sensor = launch {
            AndroidPressureStream(this@ActivitySessionService).stream().onEach {
                sawSensor = true
                engine.ingestPressure(it)
            }.launchIn(this)
        }
        delay(PRESSURE_PROBE_MS)
        if (sawSensor) {
            sensor.join()
            return@coroutineScope
        }
        sensor.cancel()
        while (isActive) {
            val hpa = lastCoords?.let {
                OpenMeteoClient.currentPressureHpa(it.lat, it.lng, RetryPolicy.Preview).getOrNull()
            }
            engine.ingestPressure(hpa)
            delay(PRESSURE_NETWORK_REFRESH_MS)
        }
    }

    private fun stopSession(abnormal: Boolean) {
        scope.launch {
            loops?.cancel()
            engine.stop(abnormal)
            ActivitySessionHost.isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundCompat() {
        val n = baseNotification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(state: ActivityState) {
        if (!ActivitySessionHost.isRunning) return
        val now = System.currentTimeMillis()
        val text = "${state.selectedMetric.name.lowercase()} · ${state.lastPushText ?: "…"}"
        // Throttle policy (rate cap + dedupe) lives in the pure ActivityNotificationThrottle so it
        // is unit-tested; posting on every sub-second emission would trip Android 17's rate limiter.
        if (!ActivityNotificationThrottle.shouldPost(now, lastNotifyAtMs, text, lastNotifiedText)) return
        lastNotifyAtMs = now
        lastNotifiedText = text
        ContextCompat.getSystemService(this, NotificationManager::class.java)
            ?.notify(NOTIF_ID, baseNotification(text))
    }

    private fun baseNotification(text: String): Notification {
        ensureChannel()
        val stopIntent = android.app.PendingIntent.getService(
            this,
            0,
            Intent(this, ActivitySessionService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return if (Build.VERSION.SDK_INT >= API_37) {
            buildPromotedNotification(text, stopIntent)
        } else {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Activity in progress")
                .setContentText(text)
                .setOngoing(true)
                .addAction(0, "Stop", stopIntent)
                .build()
        }
    }

    // Android 17+: same ongoing notification, but requested as a promoted Live Update so the system
    // can surface it on the lock screen / status bar chip for the duration of the activity.
    @RequiresApi(API_37)
    private fun buildPromotedNotification(
        text: String,
        stopIntent: android.app.PendingIntent,
    ): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Activity in progress")
            .setContentText(text)
            // The compact status string the system shows in the promoted Live Update chip; also one
            // of the "promotable characteristics" the framework requires before it will promote at
            // all (we deliberately don't use ProgressStyle for an open-ended session).
            .setShortCriticalText(text)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .addAction(
                Notification.Action.Builder(null as Icon?, "Stop", stopIntent).build(),
            )
            .build()

    private fun ensureChannel() {
        val mgr = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Activity", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onDestroy() {
        ActivitySessionHost.isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 4201
        private const val API_37 = 37
        private const val CHANNEL_ID = "activity_session"
        private const val TICK_MS = 1000L
        private const val FLUSH_EVERY_TICKS = 10 // flush the track ~every 10s
        private const val PRESSURE_PROBE_MS = 3000L // wait this long for a barometer sample
        private const val PRESSURE_NETWORK_REFRESH_MS = 600_000L // network fallback refresh ~10 min
        const val ACTION_START = "com.blizzardcaron.freeolleefaces.activity.START"
        const val ACTION_STOP = "com.blizzardcaron.freeolleefaces.activity.STOP"
        const val ACTION_CYCLE = "com.blizzardcaron.freeolleefaces.activity.CYCLE"
        const val ACTION_SET_UNIT = "com.blizzardcaron.freeolleefaces.activity.SET_UNIT"
        const val ACTION_PAUSE = "com.blizzardcaron.freeolleefaces.activity.PAUSE"
        const val ACTION_RESUME = "com.blizzardcaron.freeolleefaces.activity.RESUME"

        private fun send(context: Context, action: String, foreground: Boolean) {
            val intent = Intent(context, ActivitySessionService::class.java).setAction(action)
            if (foreground) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun start(context: Context) = send(context, ACTION_START, foreground = true)
        fun stop(context: Context) = send(context, ACTION_STOP, foreground = false)
        fun cycle(context: Context) = send(context, ACTION_CYCLE, foreground = false)
        fun setUnit(context: Context) = send(context, ACTION_SET_UNIT, foreground = false)
        fun pause(context: Context) = send(context, ACTION_PAUSE, foreground = false)
        fun resume(context: Context) = send(context, ACTION_RESUME, foreground = false)
    }
}
