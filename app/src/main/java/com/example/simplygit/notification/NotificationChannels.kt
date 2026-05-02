package com.example.simplygit.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.example.simplygit.R

/**
 * Notification channel ids + creation helper (SPEC §4.5 Iteration 2).
 *
 *  - [CHANNEL_SYNC_ALERT]: conflict / auth / SAF-permission-lost — IMPORTANCE_HIGH.
 *  - [CHANNEL_SYNC_LOW]: 3-strike network failure / low-disk — IMPORTANCE_LOW.
 *
 * `createAll` is idempotent and safe to invoke on every cold start.
 */
object NotificationChannels {
    const val CHANNEL_SYNC_ALERT: String = "simplygit.channel.sync_alert"
    const val CHANNEL_SYNC_LOW: String = "simplygit.channel.sync_low"

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC_ALERT,
                context.getString(R.string.notif_channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_alert_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC_LOW,
                context.getString(R.string.notif_channel_low_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_low_desc)
            },
        )
    }
}
