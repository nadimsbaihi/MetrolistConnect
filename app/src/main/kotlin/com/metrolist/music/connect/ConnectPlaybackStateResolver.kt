/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.connect

import com.metrolist.music.listentogether.TrackInfo

private fun TrackInfo.hasCompleteIdentityMetadata(): Boolean {
    return title.isNotBlank() && title != "Unknown" && artist.isNotBlank() && artist != "Unknown"
}

/**
 * Resolves the best track to display for remote playback state.
 *
 * Priority:
 * 1. Current track when metadata is complete.
 * 2. Queue match by current/previous track id.
 * 3. Current or previous track fallback.
 * 4. Queue head.
 */
fun ConnectPlaybackState?.resolveDisplayTrack(previousTrack: TrackInfo? = null): TrackInfo? {
    val state = this ?: return null
    val queue = state.queue
    val current = state.currentTrack

    if (current != null) {
        if (current.hasCompleteIdentityMetadata()) {
            return current
        }
        return queue.firstOrNull { it.id == current.id } ?: queue.firstOrNull() ?: current
    }

    if (previousTrack != null) {
        return queue.firstOrNull { it.id == previousTrack.id } ?: queue.firstOrNull() ?: previousTrack
    }

    return queue.firstOrNull()
}
