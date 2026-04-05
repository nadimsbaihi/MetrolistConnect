/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import com.metrolist.music.db.entities.Song
import com.metrolist.music.listentogether.TrackInfo
import com.metrolist.music.models.MediaMetadata

internal fun TrackInfo.toConnectDisplayMediaMetadata(): MediaMetadata {
    val safeTitle = title.takeIf { it.isNotBlank() } ?: "Unknown"
    val artistList = artist
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { listOf("Unknown") }
        .map { MediaMetadata.Artist(id = null, name = it) }

    return MediaMetadata(
        id = id,
        title = safeTitle,
        artists = artistList,
        duration = (duration / 1000L).toInt(),
        thumbnailUrl = thumbnail,
        album = album?.let { MediaMetadata.Album(id = "", title = it) },
    )
}

internal fun connectPlaceholderMediaMetadata(connectedDeviceName: String?): MediaMetadata {
    return MediaMetadata(
        id = "connect:remote",
        title = connectedDeviceName?.let { "Controlling $it" } ?: "Metrolist Connect",
        artists = listOf(MediaMetadata.Artist(id = null, name = "Remote playback")),
        duration = -1,
        thumbnailUrl = null,
    )
}

internal fun MediaMetadata.withSongFallback(song: Song?): MediaMetadata {
    val fallbackSong = song ?: return this

    val hasMeaningfulArtists = artists.any { it.name.isNotBlank() && !it.name.equals("Unknown", ignoreCase = true) }
    val resolvedArtists =
        if (hasMeaningfulArtists) {
            artists.mapIndexed { index, artist ->
                val resolvedId =
                    artist.id?.takeIf { it.isNotBlank() }
                        ?: fallbackSong.orderedArtists.firstOrNull { it.name.equals(artist.name, ignoreCase = true) }?.id
                        ?: fallbackSong.orderedArtists.getOrNull(index)?.id
                val resolvedName =
                    artist.name.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
                        ?: fallbackSong.orderedArtists.getOrNull(index)?.name
                        ?: artist.name
                MediaMetadata.Artist(id = resolvedId, name = resolvedName)
            }
        } else {
            fallbackSong.orderedArtists.map { MediaMetadata.Artist(id = it.id, name = it.name) }.ifEmpty { artists }
        }

    val fallbackAlbumId = fallbackSong.album?.id ?: fallbackSong.song.albumId
    val fallbackAlbumTitle = fallbackSong.album?.title ?: fallbackSong.song.albumName
    val resolvedAlbum =
        when {
            album != null -> {
                val resolvedId = album.id.takeIf { it.isNotBlank() } ?: fallbackAlbumId
                val resolvedTitle = album.title.takeIf { it.isNotBlank() } ?: fallbackAlbumTitle
                if (resolvedId != null && resolvedTitle != null) {
                    MediaMetadata.Album(id = resolvedId, title = resolvedTitle)
                } else {
                    album
                }
            }
            fallbackAlbumId != null && !fallbackAlbumTitle.isNullOrBlank() -> {
                MediaMetadata.Album(id = fallbackAlbumId, title = fallbackAlbumTitle)
            }
            else -> null
        }

    val resolvedTitle =
        title.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
            ?: fallbackSong.song.title
    val resolvedDuration = if (duration > 0) duration else fallbackSong.song.duration
    val resolvedThumbnail = thumbnailUrl ?: fallbackSong.song.thumbnailUrl

    return copy(
        title = resolvedTitle,
        artists = resolvedArtists,
        duration = resolvedDuration,
        thumbnailUrl = resolvedThumbnail,
        album = resolvedAlbum,
    )
}
