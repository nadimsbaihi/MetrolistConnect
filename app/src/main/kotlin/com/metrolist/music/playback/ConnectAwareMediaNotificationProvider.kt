/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.connect.resolveDisplayTrack

/**
 * Media playback notification provider that reflects Connect state.
 *
 * - When controlling a remote device, title/text come from remote playback state.
 * - When being controlled, text indicates who is controlling this device.
 */
class ConnectAwareMediaNotificationProvider(
    context: Context,
    private val musicService: MusicService,
    notificationIdProvider: DefaultMediaNotificationProvider.NotificationIdProvider,
    channelId: String,
    channelNameResourceId: Int,
) : MediaNotification.Provider {

    private val appContext = context.applicationContext
    private val delegate =
        DefaultMediaNotificationProvider(
            appContext,
            notificationIdProvider,
            channelId,
            channelNameResourceId,
        )

    fun setSmallIcon(@DrawableRes smallIconResourceId: Int) {
        delegate.setSmallIcon(smallIconResourceId)
    }

    private fun currentRemoteTrack(): com.metrolist.music.listentogether.TrackInfo? {
        val manager = musicService.connectManager ?: return null
        return manager.remotePlaybackState.value.resolveDisplayTrack()
    }

    private fun connectTitleAndText(): Pair<CharSequence?, CharSequence?> {
        val connectManager = musicService.connectManager
        if (connectManager?.isControlling?.value == true) {
            val remoteTitle = currentRemoteTrack()?.title?.takeIf { it.isNotBlank() }
            val deviceName = connectManager.connectedDeviceName.value
            val title =
                remoteTitle
                    ?: deviceName?.let { appContext.getString(R.string.metrolist_connect_controlling, it) }
                    ?: appContext.getString(R.string.metrolist_connect)

            val remoteTrack = currentRemoteTrack()
            val artist = remoteTrack?.artist?.takeIf { it.isNotBlank() }
            val album = remoteTrack?.album?.takeIf { it.isNotBlank() }
            val deviceInfo = deviceName?.let { appContext.getString(R.string.metrolist_connect_playing_on, it) }
            val text =
                listOfNotNull(artist, album, deviceInfo)
                    .joinToString(separator = " • ")
                    .ifBlank { deviceInfo ?: appContext.getString(R.string.metrolist_connect) }
            return title to text
        }

        if (connectManager?.isReceiving?.value == true) {
            val controllers = connectManager.connectedControllers.value
            if (controllers.isNotEmpty()) {
                val text =
                    appContext.getString(
                        R.string.metrolist_connect_notification_receiver,
                        controllers.joinToString(", "),
                    )
                return appContext.getString(R.string.metrolist_connect) to text
            }
        }

        return null to null
    }

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<androidx.media3.session.CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val callback =
            MediaNotification.Provider.Callback { updated ->
                onNotificationChangedCallback.onNotificationChanged(customizeNotification(updated))
            }
        val base = delegate.createNotification(mediaSession, mediaButtonPreferences, actionFactory, callback)
        return customizeNotification(base)
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: android.os.Bundle,
    ): Boolean = delegate.handleCustomCommand(session, action, extras)

    fun overrideNotificationContent(notification: Notification): Notification {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return notification
        }

        val (title, text) = connectTitleAndText()
        if (title.isNullOrBlank() && text.isNullOrBlank()) {
            return notification
        }

        val builder = Notification.Builder.recoverBuilder(appContext, notification)
        if (!title.isNullOrBlank()) {
            builder.setContentTitle(title)
        }
        if (!text.isNullOrBlank()) {
            builder.setContentText(text)
        }
        return builder.build()
    }

    private fun customizeNotification(base: MediaNotification): MediaNotification {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return base
        }

        val contentOverridden = overrideNotificationContent(base.notification)
        val builder = Notification.Builder.recoverBuilder(appContext, contentOverridden)

        addOpenDevicesAction(builder)

        return MediaNotification(base.notificationId, builder.build())
    }

    private fun addOpenDevicesAction(builder: Notification.Builder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val openDevicesIntent =
            Intent(appContext, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_CONNECT_PICKER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val openDevicesPendingIntent =
            PendingIntent.getActivity(
                appContext,
                9302,
                openDevicesIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val action =
            Notification.Action.Builder(
                Icon.createWithResource(appContext, R.drawable.devices),
                appContext.getString(R.string.metrolist_connect_device_picker_title),
                openDevicesPendingIntent,
            ).build()

        builder.addAction(action)
    }
}
