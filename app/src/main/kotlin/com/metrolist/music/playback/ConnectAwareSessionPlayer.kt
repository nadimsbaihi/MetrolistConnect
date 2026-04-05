/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.metrolist.music.connect.ConnectManager
import com.metrolist.music.connect.resolveDisplayTrack
import com.metrolist.music.listentogether.TrackInfo

/**
 * Player wrapper for MediaSession when Metrolist Connect is controlling a remote device.
 *
 * It exposes remote metadata/state/position to system media controls (notification, lockscreen,
 * quick settings) while forwarding commands to the remote device.
 */
class ConnectAwareSessionPlayer(
    basePlayer: Player,
    private val connectManager: ConnectManager,
) : ForwardingPlayer(basePlayer) {

    private companion object {
        private const val REMOTE_SEEK_SETTLE_WINDOW_MS = 1500L
        private const val REMOTE_SEEK_CATCHUP_TOLERANCE_MS = 2500L
    }

    private var pendingSeekPositionMs: Long? = null
    private var pendingSeekAtMs: Long = 0L
    private var pendingSeekTrackId: String? = null

    private fun isRemoteControlActive(): Boolean = connectManager.isControlling.value

    private fun remoteTrack(): TrackInfo? {
        return connectManager.remotePlaybackState.value.resolveDisplayTrack()
    }

    private fun remoteQueue(): List<TrackInfo> = connectManager.remotePlaybackState.value?.queue ?: emptyList()

    private fun remoteCurrentIndex(): Int {
        val track = remoteTrack() ?: return 0
        val queue = remoteQueue()
        val idx = queue.indexOfFirst { it.id == track.id }
        return if (idx >= 0) idx else 0
    }

    private fun remotePositionNow(): Long {
        val remote = connectManager.remotePlaybackState.value ?: return 0L
        val resolvedTrack = remoteTrack()
        val resolvedTrackId = resolvedTrack?.id

        if (pendingSeekTrackId != null && pendingSeekTrackId != resolvedTrackId) {
            pendingSeekPositionMs = null
            pendingSeekAtMs = 0L
            pendingSeekTrackId = null
        }

        val elapsed =
            if (remote.isPlaying && remote.lastUpdate > 0L) {
                (System.currentTimeMillis() - remote.lastUpdate).coerceAtLeast(0L)
            } else {
                0L
            }
        val duration = resolvedTrack?.duration ?: C.TIME_UNSET
        val estimatedRemote = (remote.position + elapsed).coerceAtLeast(0L)

        val pendingPosition = pendingSeekPositionMs
        if (pendingPosition != null) {
            val sinceSeek = (System.currentTimeMillis() - pendingSeekAtMs).coerceAtLeast(0L)
            val remoteCaughtUp = kotlin.math.abs(remote.position - pendingPosition) <= REMOTE_SEEK_CATCHUP_TOLERANCE_MS

            if (remoteCaughtUp || sinceSeek > REMOTE_SEEK_SETTLE_WINDOW_MS) {
                pendingSeekPositionMs = null
                pendingSeekAtMs = 0L
                pendingSeekTrackId = null
            } else {
                val estimatedPending =
                    if (remote.isPlaying) {
                        (pendingPosition + sinceSeek).coerceAtLeast(0L)
                    } else {
                        pendingPosition.coerceAtLeast(0L)
                    }
                return if (duration > 0L) estimatedPending.coerceAtMost(duration) else estimatedPending
            }
        }

        return if (duration > 0L) estimatedRemote.coerceAtMost(duration) else estimatedRemote
    }

    private fun remoteMetadata(track: TrackInfo): MediaMetadata {
        val safeTitle = track.title.takeIf { it.isNotBlank() } ?: "Unknown"
        val safeArtist = track.artist.takeIf { it.isNotBlank() } ?: "Unknown"
        val builder =
            MediaMetadata.Builder()
                .setTitle(safeTitle)
                .setDisplayTitle(safeTitle)
                .setArtist(safeArtist)
                .setAlbumTitle(track.album)
                .setIsPlayable(true)

        if (!track.thumbnail.isNullOrBlank()) {
            builder.setArtworkUri(Uri.parse(track.thumbnail))
        }
        if (track.duration > 0L) {
            builder.setDurationMs(track.duration)
        }
        return builder.build()
    }

    private fun placeholderRemoteMetadata(): MediaMetadata {
        val device = connectManager.connectedDeviceName.value
        val title =
            if (device.isNullOrBlank()) {
                "Metrolist Connect"
            } else {
                "Controlling $device"
            }
        return MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setIsPlayable(true)
            .build()
    }

    override fun getCurrentMediaItem(): MediaItem? {
        if (!isRemoteControlActive()) return super.getCurrentMediaItem()
        val track = remoteTrack()
        return if (track != null) {
            MediaItem.Builder()
                .setMediaId(track.id)
                .setMediaMetadata(remoteMetadata(track))
                .build()
        } else {
            MediaItem.Builder()
                .setMediaId("connect:remote")
                .setMediaMetadata(placeholderRemoteMetadata())
                .build()
        }
    }

    override fun getMediaMetadata(): MediaMetadata {
        if (!isRemoteControlActive()) return super.getMediaMetadata()
        val track = remoteTrack()
        return if (track != null) remoteMetadata(track) else placeholderRemoteMetadata()
    }

    override fun getCurrentMediaItemIndex(): Int {
        if (!isRemoteControlActive()) return super.getCurrentMediaItemIndex()
        return remoteCurrentIndex()
    }

    override fun getMediaItemCount(): Int {
        if (!isRemoteControlActive()) return super.getMediaItemCount()
        val queueCount = remoteQueue().size
        return when {
            queueCount > 0 -> queueCount
            else -> 1
        }
    }

    override fun hasNextMediaItem(): Boolean {
        if (!isRemoteControlActive()) return super.hasNextMediaItem()
        return remoteCurrentIndex() < getMediaItemCount() - 1
    }

    override fun hasPreviousMediaItem(): Boolean {
        if (!isRemoteControlActive()) return super.hasPreviousMediaItem()
        return remoteCurrentIndex() > 0
    }

    override fun getCurrentPosition(): Long {
        if (!isRemoteControlActive()) return super.getCurrentPosition()
        return remotePositionNow()
    }

    override fun getContentPosition(): Long {
        if (!isRemoteControlActive()) return super.getContentPosition()
        return remotePositionNow()
    }

    override fun getBufferedPosition(): Long {
        if (!isRemoteControlActive()) return super.getBufferedPosition()
        return remotePositionNow()
    }

    override fun getDuration(): Long {
        if (!isRemoteControlActive()) return super.getDuration()
        val duration = remoteTrack()?.duration ?: C.TIME_UNSET
        return if (duration > 0L) duration else C.TIME_UNSET
    }

    override fun isCurrentMediaItemSeekable(): Boolean {
        if (!isRemoteControlActive()) return super.isCurrentMediaItemSeekable()
        return getDuration() > 0L
    }

    override fun isPlaying(): Boolean {
        if (!isRemoteControlActive()) return super.isPlaying()
        return connectManager.remotePlaybackState.value?.isPlaying ?: false
    }

    override fun getPlayWhenReady(): Boolean {
        if (!isRemoteControlActive()) return super.getPlayWhenReady()
        return connectManager.remotePlaybackState.value?.isPlaying ?: false
    }

    override fun getPlaybackState(): Int {
        if (!isRemoteControlActive()) return super.getPlaybackState()
        val remote = connectManager.remotePlaybackState.value
        return if (remote != null && (remoteTrack() != null || remote.isPlaying)) Player.STATE_READY else Player.STATE_IDLE
    }

    override fun isCommandAvailable(command: Int): Boolean {
        if (!isRemoteControlActive()) return super.isCommandAvailable(command)
        return when (command) {
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD -> true

            else -> super.isCommandAvailable(command)
        }
    }

    override fun getAvailableCommands(): Player.Commands {
        if (!isRemoteControlActive()) return super.getAvailableCommands()
        return super.getAvailableCommands()
            .buildUpon()
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .build()
    }

    override fun play() {
        if (isRemoteControlActive()) {
            connectManager.play()
            return
        }
        super.play()
    }

    override fun pause() {
        if (isRemoteControlActive()) {
            connectManager.pause()
            return
        }
        super.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (isRemoteControlActive()) {
            pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
            pendingSeekAtMs = System.currentTimeMillis()
            pendingSeekTrackId = remoteTrack()?.id
            connectManager.seekTo(positionMs.coerceAtLeast(0L))
            return
        }
        super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (isRemoteControlActive()) {
            pendingSeekPositionMs = positionMs.coerceAtLeast(0L)
            pendingSeekAtMs = System.currentTimeMillis()
            pendingSeekTrackId = remoteTrack()?.id
            connectManager.seekTo(positionMs.coerceAtLeast(0L))
            return
        }
        super.seekTo(mediaItemIndex, positionMs)
    }

    override fun seekBack() {
        if (isRemoteControlActive()) {
            val target = (remotePositionNow() - seekBackIncrement).coerceAtLeast(0L)
            pendingSeekPositionMs = target
            pendingSeekAtMs = System.currentTimeMillis()
            pendingSeekTrackId = remoteTrack()?.id
            connectManager.seekTo(target)
            return
        }
        super.seekBack()
    }

    override fun seekForward() {
        if (isRemoteControlActive()) {
            val duration = getDuration()
            val targetRaw = remotePositionNow() + seekForwardIncrement
            val target = if (duration > 0L) targetRaw.coerceAtMost(duration) else targetRaw
            pendingSeekPositionMs = target.coerceAtLeast(0L)
            pendingSeekAtMs = System.currentTimeMillis()
            pendingSeekTrackId = remoteTrack()?.id
            connectManager.seekTo(target.coerceAtLeast(0L))
            return
        }
        super.seekForward()
    }

    override fun seekToNext() {
        if (isRemoteControlActive()) {
            connectManager.skipNext()
            return
        }
        super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        if (isRemoteControlActive()) {
            connectManager.skipNext()
            return
        }
        super.seekToNextMediaItem()
    }

    override fun seekToPrevious() {
        if (isRemoteControlActive()) {
            connectManager.skipPrevious()
            return
        }
        super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        if (isRemoteControlActive()) {
            connectManager.skipPrevious()
            return
        }
        super.seekToPreviousMediaItem()
    }
}
