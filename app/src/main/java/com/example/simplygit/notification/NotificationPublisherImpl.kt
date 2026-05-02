package com.example.simplygit.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.simplygit.R
import com.example.simplygit.domain.model.ConflictClass
import com.example.simplygit.domain.service.NotificationPublisher
import com.example.simplygit.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [NotificationPublisher] backed by `NotificationManagerCompat`
 * (SPEC §4.5 Iteration 2).
 *
 *  - High-priority events use [NotificationChannels.CHANNEL_SYNC_ALERT].
 *  - Network-broken uses [NotificationChannels.CHANNEL_SYNC_LOW] with
 *    dedup by repoId so repeated BROKEN transitions don't spam.
 *  - Android 13+: when `POST_NOTIFICATIONS` is denied we silently drop the
 *    notify call; the paused-count badge on Home picks up the slack.
 */
@Singleton
class NotificationPublisherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationPublisher {

    override fun publishConflict(repoId: Long, kind: ConflictClass) {
        val body = context.getString(R.string.notif_conflict_body, kind.displayName())
        notify(
            id = notifId(NotifCategory.CONFLICT, repoId),
            channelId = NotificationChannels.CHANNEL_SYNC_ALERT,
            title = context.getString(R.string.notif_conflict_title),
            body = body,
            navKey = NAV_AUDIT,
        )
    }

    override fun publishAuthFailed(repoId: Long) {
        notify(
            id = notifId(NotifCategory.AUTH, repoId),
            channelId = NotificationChannels.CHANNEL_SYNC_ALERT,
            title = context.getString(R.string.notif_auth_title),
            body = context.getString(R.string.notif_auth_body),
            navKey = NAV_RESUME,
        )
    }

    override fun publishFsPermissionLost(repoId: Long) {
        notify(
            id = notifId(NotifCategory.FS, repoId),
            channelId = NotificationChannels.CHANNEL_SYNC_ALERT,
            title = context.getString(R.string.notif_fs_title),
            body = context.getString(R.string.notif_fs_body),
            navKey = NAV_RESUME,
        )
    }

    override fun publishNetworkBroken(repoId: Long) {
        notify(
            id = notifId(NotifCategory.NETWORK, repoId),
            channelId = NotificationChannels.CHANNEL_SYNC_LOW,
            title = context.getString(R.string.notif_network_title),
            body = context.getString(R.string.notif_network_body),
            navKey = NAV_AUDIT,
        )
    }

    override fun publishLowPriority(msg: String) {
        notify(
            id = notifId(NotifCategory.LOW, 0L),
            channelId = NotificationChannels.CHANNEL_SYNC_LOW,
            title = context.getString(R.string.app_name),
            body = msg,
            navKey = NAV_AUDIT,
        )
    }

    @SuppressLint("MissingPermission")
    private fun notify(
        id: Int,
        channelId: String,
        title: String,
        body: String,
        navKey: String,
    ) {
        if (!canPostNotifications()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_NAV, navKey)
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(context, id, intent, pendingFlags)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ConflictClass.displayName(): String = context.getString(
        when (this) {
            ConflictClass.FAST_FORWARD -> R.string.conflict_class_fast_forward
            ConflictClass.AUTO_MERGED -> R.string.conflict_class_auto_merged
            ConflictClass.TEXT_LINE_CONFLICT -> R.string.conflict_class_text_line
            ConflictClass.BINARY_CONFLICT -> R.string.conflict_class_binary
            ConflictClass.DELETE_MODIFY -> R.string.conflict_class_delete_modify
            ConflictClass.REMOTE_REWRITE -> R.string.conflict_class_remote_rewrite
        },
    )

    private fun notifId(category: NotifCategory, repoId: Long): Int =
        (category.base + repoId.toInt()).coerceAtLeast(1)

    private enum class NotifCategory(val base: Int) {
        CONFLICT(1_000),
        AUTH(2_000),
        FS(3_000),
        NETWORK(4_000),
        LOW(5_000),
    }

    companion object {
        const val EXTRA_NAV: String = "nav"
        const val NAV_AUDIT: String = "audit"
        const val NAV_RESUME: String = "resume"
    }
}
