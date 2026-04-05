/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.connect

import com.metrolist.music.listentogether.PlaybackActionPayload
import com.metrolist.music.listentogether.SyncStatePayload
import com.metrolist.music.listentogether.TrackInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Message types for the Metrolist Connect protocol.
 * Reuses [TrackInfo], [PlaybackActionPayload], and [SyncStatePayload] from Listen Together.
 */
object ConnectMessageTypes {
    // Handshake (plaintext, pre-encryption)
    const val CONNECT_HELLO = "connect_hello"
    const val CONNECT_HELLO_REPLY = "connect_hello_reply"
    const val CONNECT_PROOF = "connect_proof"
    const val CONNECT_ESTABLISHED = "connect_established"
    const val CONNECT_REJECTED = "connect_rejected"

    // Control (controller → receiver, encrypted)
    const val PLAYBACK_COMMAND = "playback_command"
    const val TRANSFER_PLAYBACK = "transfer_playback"
    const val REQUEST_STATE = "request_state"

    // State (receiver → controller, encrypted)
    const val STATE_UPDATE = "state_update"
    const val DEVICE_INFO = "device_info"
    const val PLAYBACK_TRANSFERRED = "playback_transferred"

    // Lifecycle (encrypted)
    const val PING = "ping"
    const val PONG = "pong"
    const val DISCONNECT = "disconnect"
}

// --- Handshake messages (sent as plaintext JSON before encryption is established) ---

/**
 * Step 1: Controller sends its ephemeral public key and nonce.
 */
@Serializable
data class ConnectHello(
    @SerialName("controller_pub") val controllerPub: String, // Base64-encoded X25519 public key
    @SerialName("controller_nonce") val controllerNonce: String, // Base64-encoded 32-byte nonce
    @SerialName("controller_id") val controllerId: String,
    @SerialName("protocol_version") val protocolVersion: Int = 1,
)

/**
 * Step 2: Receiver sends its ephemeral public key and nonce.
 */
@Serializable
data class ConnectHelloReply(
    @SerialName("device_pub") val devicePub: String, // Base64-encoded X25519 public key
    @SerialName("device_nonce") val deviceNonce: String, // Base64-encoded 32-byte nonce
    @SerialName("device_name") val deviceName: String,
)

// --- Encrypted messages (sent within ConnectEnvelope after handshake) ---

/**
 * Envelope for all post-handshake messages. The payload is AES-256-GCM encrypted.
 */
@Serializable
data class ConnectEnvelope(
    val type: String,
    val payload: String, // Base64-encoded encrypted bytes (nonce || ciphertext+tag)
)

/**
 * Step 3: Controller sends identity proof (encrypted).
 */
@Serializable
data class ConnectProof(
    val proof: String, // Base64-encoded encrypted pairing proof
)

/**
 * Payload for transferring playback from one device to another.
 * Includes the complete playback state so the receiving device can resume seamlessly.
 */
@Serializable
data class TransferPlaybackPayload(
    @SerialName("current_track") val currentTrack: TrackInfo?,
    val queue: List<TrackInfo>,
    val position: Long,
    @SerialName("is_playing") val isPlaying: Boolean,
    val volume: Float,
    @SerialName("queue_title") val queueTitle: String? = null,
)

/**
 * Device information sent after successful handshake.
 */
@Serializable
data class DeviceInfoPayload(
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("app_version") val appVersion: String,
)

// --- Connection state ---

enum class ConnectConnectionState {
    DISCONNECTED,
    CONNECTING,
    HANDSHAKING,
    CONNECTED,
    RECONNECTING,
}

/**
 * Represents the playback state received from a remote device.
 */
@Serializable
data class ConnectPlaybackState(
    @SerialName("current_track") val currentTrack: TrackInfo? = null,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    val position: Long = 0,
    @SerialName("last_update") val lastUpdate: Long = 0,
    val queue: List<TrackInfo> = emptyList(),
    val volume: Float = 1f,
)

/**
 * Playback state of a discovered device, shown in the mDNS TXT records.
 */
enum class DiscoveredPlaybackState {
    PLAYING,
    PAUSED,
    IDLE,
    ;

    companion object {
        fun fromString(value: String): DiscoveredPlaybackState =
            when (value.lowercase()) {
                "playing" -> PLAYING
                "paused" -> PAUSED
                else -> IDLE
            }
    }
}

/**
 * Represents a device discovered via mDNS on the local network.
 */
data class ConnectDevice(
    val name: String,
    val host: String,
    val port: Int,
    val accountKeyFingerprint: String,
    val currentTrackId: String? = null,
    val playbackState: DiscoveredPlaybackState = DiscoveredPlaybackState.IDLE,
    val isThisDevice: Boolean = false,
)
