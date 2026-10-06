package com.wolfpc.remote.notif

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wolfpc.remote.R

/**
 * Posts the notifications. [StateNotifier] decides when and what.
 *
 * These fire while the app is running — foreground, or backgrounded but not yet
 * evicted. Waking a phone whose app is fully closed would mean FCM, and there
 * is no second option on Android; that was considered and deliberately not
 * taken, because it means a Google account holding a service-account key for a
 * system that otherwise touches nobody but Supabase. See docs/NOTIFICATIONS.md.
 *
 * Nothing here ever carries a nonce, a signature, a token or anything about a
 * password — PRD §23 is explicit, and a lock screen is a public surface.
 */
class Notifier(private val context: Context) {

    companion object {
        const val CHANNEL_EVENTS = "wolfpc_events"
        const val CHANNEL_ALERTS = "wolfpc_alerts"
        private var nextId = 1000
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                "PC events",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Power on, login screen, shutdown and restart" },
        )

        // Separate channel so a failed hard reset can be made loud without the
        // routine "PC turned on" notifications being loud too.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Failed commands and devices going offline" },
        )
    }

    fun event(title: String, body: String, alert: Boolean = false) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(
            context,
            if (alert) CHANNEL_ALERTS else CHANNEL_EVENTS,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(if (alert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(nextId++, notification)
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
