/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.utils.joinByBullet

/**
 * Manages the persistent notification that appears when Metrolist Connect
 * is actively controlling or being controlled by another device.
 */
class ConnectNotificationManager(
    private val context: Context,
) {
    companion object {
        private const val CHANNEL_ID = "metrolist_connect"
        const val NOTIFICATION_ID = 889
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.metrolist_connect_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.metrolist_connect_notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Shows a notification while being controlled by remote devices.
     */
    fun showReceiverNotification(
        controllerNames: List<String>,
        trackTitle: String?,
        trackArtist: String?,
        isPlaying: Boolean,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stateText = if (isPlaying) "Playing" else "Paused"
        val controllerSummary =
            if (controllerNames.isNotEmpty()) {
                controllerNames.joinToString(", ")
            } else {
                "0"
            }

        val contentText = joinByBullet(
            context.getString(R.string.metrolist_connect_notification_receiver, controllerSummary),
            trackTitle,
            stateText,
        )

        val details = buildString {
            appendLine("Controllers: ${if (controllerNames.isEmpty()) "None" else controllerNames.joinToString(", ")}")
            appendLine("Controller count: ${controllerNames.size}")
            if (!trackTitle.isNullOrBlank()) appendLine("Track: $trackTitle")
            if (!trackArtist.isNullOrBlank()) appendLine("Artist: $trackArtist")
            append("Local state: $stateText")
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(context.getString(R.string.metrolist_connect))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
            .also { notificationManager.notify(NOTIFICATION_ID, it) }
    }

    fun dismiss() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
