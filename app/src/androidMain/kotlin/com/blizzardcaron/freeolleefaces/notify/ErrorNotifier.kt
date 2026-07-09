package com.blizzardcaron.freeolleefaces.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.blizzardcaron.freeolleefaces.MainActivity

/**
 * Owns the single error notification for face-update problems (post/clear driven by
 * [NotifyDecision]).
 */
object ErrorNotifier {

    private const val CHANNEL_ID = "background_problems"
    private const val NOTIFICATION_ID = 1001

    /**
     * Posts (or replaces) the single error notification. Returns `true` if it was actually shown,
     * `false` if suppressed because POST_NOTIFICATIONS is not granted — callers must not record the
     * notification as shown unless this returned `true`, or a later grant would never surface an
     * ongoing failure.
     */
    fun notify(context: Context, kind: FailureKind): Boolean {
        val ctx = context.applicationContext
        ensureChannel(ctx)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(titleFor(kind))
            .setContentText(textFor(kind))
            .setContentIntent(pending)
            .setAutoCancel(true)

        // Transient failures get a Retry action that re-runs the failed push in the background.
        if (kind.retryable) {
            val retryIntent = Intent(ctx, RetryReceiver::class.java)
            val retryPending = PendingIntent.getBroadcast(
                ctx,
                RetryReceiver.REQUEST_CODE,
                retryIntent,
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Retry", retryPending)
        }

        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, builder.build())
        return true
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(ctx: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background update problems",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Alerts when a background watch update fails." }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun titleFor(kind: FailureKind): String = when (kind) {
        FailureKind.WATCH_UNREACHABLE -> "Watch unreachable"
        FailureKind.WEATHER_FETCH_FAILED -> "Weather update failed"
        FailureKind.SETUP_INCOMPLETE -> "Setup incomplete"
        FailureKind.HEALTH_UNAVAILABLE -> "Health access needed"
    }

    private fun textFor(kind: FailureKind): String = when (kind) {
        FailureKind.WATCH_UNREACHABLE ->
            "Couldn't reach your watch after several tries. Long-press the ALARM button to wake its " +
                "Bluetooth, then tap Retry."
        FailureKind.WEATHER_FETCH_FAILED -> "Couldn't fetch the temperature. The watch value may be stale."
        FailureKind.SETUP_INCOMPLETE -> "Open the app to set your location and watch."
        FailureKind.HEALTH_UNAVAILABLE -> "Open the app and grant Health access so step counts can sync."
    }
}
