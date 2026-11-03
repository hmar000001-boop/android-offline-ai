package dev.screenveil.app.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.screenveil.app.MainActivity
import dev.screenveil.app.R

/**
 * User-visible escape hatch and status. While protection is ON an ongoing notification carries a
 * Stop action; if protection stops for a technical reason a separate alert says the screen is no
 * longer protected. Notifications are best effort: Android may hide them when the user has not
 * granted the notification permission, which is why a Quick Settings tile exists as well.
 */
object ProtectionNotifier {
    private const val ACTIVE_CHANNEL = "screenveil_active"
    private const val ALERT_CHANNEL = "screenveil_alerts"
    private const val ACTIVE_ID = 7101
    private const val ALERT_ID = 7102
    const val ACTION_STOP = "dev.screenveil.app.action.STOP_PROTECTION"

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private fun ensureChannels(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(ACTIVE_CHANNEL, "Protection status", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Protection alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun showActive(context: Context) {
        runCatching {
            val manager = manager(context) ?: return
            ensureChannels(manager)
            val stop = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, StopProtectionReceiver::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, ACTIVE_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_screenveil)
                .setContentTitle("ScreenVeil protection is ON")
                .setContentText("If the screen is covered, tap Stop.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent(context))
                .addAction(0, "Stop", stop)
                .build()
            manager.notify(ACTIVE_ID, notification)
        }
    }

    fun clearActive(context: Context) {
        runCatching { manager(context)?.cancel(ACTIVE_ID) }
    }

    fun showPaused(context: Context, message: String) {
        runCatching {
            val manager = manager(context) ?: return
            ensureChannels(manager)
            val notification = NotificationCompat.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_screenveil)
                .setContentTitle("ScreenVeil paused - screen is NOT protected")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .build()
            manager.notify(ALERT_ID, notification)
        }
    }

    fun clearPaused(context: Context) {
        runCatching { manager(context)?.cancel(ALERT_ID) }
    }
}
