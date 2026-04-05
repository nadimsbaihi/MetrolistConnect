/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.connect

import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.listentogether.PlaybackActions
import com.metrolist.music.listentogether.PlaybackActionPayload
import com.metrolist.music.listentogether.TrackInfo
import com.metrolist.music.listentogether.SyncStatePayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for connecting to a Metrolist Connect receiver.
 * Performs the X25519 ECDH handshake, then sends encrypted playback commands.
 */
class ConnectClient(
    private val keyManager: ConnectKeyManager,
    private val accountKey: ByteArray,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "ConnectClient"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val PING_INTERVAL_MS = 10_000L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val controllerId = UUID.randomUUID().toString()

    private val _connectionState = MutableStateFlow(ConnectConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectConnectionState> = _connectionState.asStateFlow()

    private val _remoteState = MutableStateFlow<ConnectPlaybackState?>(null)
    val remoteState: StateFlow<ConnectPlaybackState?> = _remoteState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private var webSocket: WebSocket? = null
    private var sessionKey: ByteArray? = null
    private var controllerKeyPair: ConnectKeyManager.X25519KeyPair? = null
    private var controllerNonce: ByteArray? = null
    private var currentDevice: ConnectDevice? = null

    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var shouldReconnect = false

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    fun connect(device: ConnectDevice) {
        disconnect()
        currentDevice = device
        shouldReconnect = true
        _connectionState.value = ConnectConnectionState.CONNECTING

        // Generate ephemeral keypair and nonce
        controllerKeyPair = keyManager.generateEphemeralKeyPair()
        controllerNonce = keyManager.generateNonce()

        val url = "ws://${device.host}:${device.port}/connect"
        Timber.tag(TAG).d("Connecting to $url")

        val request = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
    }

    fun disconnect() {
        shouldReconnect = false
        pingJob?.cancel()
        pingJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        sessionKey = null
        controllerKeyPair = null
        controllerNonce = null
        _connectionState.value = ConnectConnectionState.DISCONNECTED
        _remoteState.value = null
        _connectedDeviceName.value = null
    }

    // --- Playback commands ---

    fun play() = sendPlaybackCommand(PlaybackActions.PLAY)
    fun pause() = sendPlaybackCommand(PlaybackActions.PAUSE)

    fun seekTo(position: Long) = sendPlaybackCommand(
        PlaybackActions.SEEK,
        PlaybackActionPayload(action = PlaybackActions.SEEK, position = position),
    )

    fun skipNext() = sendPlaybackCommand(PlaybackActions.SKIP_NEXT)
    fun skipPrevious() = sendPlaybackCommand(PlaybackActions.SKIP_PREV)

    fun setVolume(volume: Float) = sendPlaybackCommand(
        PlaybackActions.SET_VOLUME,
        PlaybackActionPayload(action = PlaybackActions.SET_VOLUME, volume = volume),
    )

    fun changeTrack(index: Int, trackId: String? = null) = sendPlaybackCommand(
        PlaybackActions.CHANGE_TRACK,
        PlaybackActionPayload(
            action = PlaybackActions.CHANGE_TRACK,
            position = index.toLong(),
            trackId = trackId,
        )
    )

    fun addToQueue(item: MediaItem, playNext: Boolean = false) = addToQueue(listOf(item), playNext)

    fun playNext(item: MediaItem) = playNext(listOf(item))

    fun playNext(items: List<MediaItem>) = addToQueue(items, playNext = true)

    fun addToQueue(items: List<MediaItem>, playNext: Boolean = false) {
        val tracks = items.mapNotNull { it.toTrackInfoOrNull() }
        if (tracks.isEmpty()) return
        sendPlaybackCommand(
            PlaybackActions.QUEUE_ADD,
            PlaybackActionPayload(
                action = PlaybackActions.QUEUE_ADD,
                trackInfo = tracks.firstOrNull(),
                queue = tracks,
                insertNext = playNext,
            ),
        )
    }

    fun playQueue(
        items: List<MediaItem>,
        queueTitle: String? = null,
        currentTrackId: String? = items.firstOrNull()?.mediaId,
        position: Long = 0L,
    ) {
        val tracks = items.mapNotNull { it.toTrackInfoOrNull() }
        if (tracks.isEmpty()) return
        val resolvedCurrentTrackId = currentTrackId?.takeIf { it.isNotBlank() } ?: tracks.first().id
        sendPlaybackCommand(
            PlaybackActions.SYNC_QUEUE,
            PlaybackActionPayload(
                action = PlaybackActions.SYNC_QUEUE,
                queue = tracks,
                queueTitle = queueTitle,
                trackId = resolvedCurrentTrackId,
                position = position,
            ),
        )
    }

    fun transferPlayback(payload: TransferPlaybackPayload) {
        sendEncrypted(ConnectMessageTypes.TRANSFER_PLAYBACK, json.encodeToString(payload))
    }

    fun requestState() {
        sendEncrypted(ConnectMessageTypes.REQUEST_STATE, "{}")
    }

    private fun sendPlaybackCommand(
        action: String,
        payload: PlaybackActionPayload = PlaybackActionPayload(action = action),
    ) {
        sendEncrypted(ConnectMessageTypes.PLAYBACK_COMMAND, json.encodeToString(payload))
    }

    private fun sendEncrypted(type: String, payloadJson: String) {
        val key = sessionKey ?: return
        val ws = webSocket ?: return

        try {
            val encrypted = keyManager.encrypt(payloadJson.toByteArray(Charsets.UTF_8), key)
            val envelope = ConnectEnvelope(
                type = type,
                payload = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP),
            )
            ws.send(json.encodeToString(envelope))
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to send encrypted message")
        }
    }

    // --- WebSocket listener ---

    private fun createWebSocketListener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.tag(TAG).d("WebSocket opened, starting handshake")
            _connectionState.value = ConnectConnectionState.HANDSHAKING
            sendHello(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(text, webSocket)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to handle message")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.tag(TAG).d("WebSocket closing: code=$code reason=$reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.tag(TAG).d("WebSocket closed: code=$code reason=$reason")
            handleDisconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.tag(TAG).e(t, "WebSocket failure")
            handleDisconnect()
        }
    }

    private fun sendHello(ws: WebSocket) {
        val keyPair = controllerKeyPair ?: return
        val nonce = controllerNonce ?: return

        val hello = ConnectHello(
            controllerPub = android.util.Base64.encodeToString(
                keyManager.publicKeyBytes(keyPair),
                android.util.Base64.NO_WRAP,
            ),
            controllerNonce = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP),
            controllerId = controllerId,
        )
        val msg = mapOf(
            "type" to ConnectMessageTypes.CONNECT_HELLO,
            "payload" to json.encodeToString(hello),
        )
        ws.send(json.encodeToString(msg))
    }

    private fun handleMessage(text: String, ws: WebSocket) {
        // During handshake, messages are plaintext JSON
        if (_connectionState.value == ConnectConnectionState.HANDSHAKING) {
            handleHandshakeMessage(text, ws)
            return
        }

        // After handshake, messages are encrypted envelopes
        handleEncryptedMessage(text)
    }

    private fun handleHandshakeMessage(text: String, ws: WebSocket) {
        val msg = json.decodeFromString<Map<String, String>>(text)

        when (msg["type"]) {
            ConnectMessageTypes.CONNECT_HELLO_REPLY -> {
                val reply = json.decodeFromString<ConnectHelloReply>(msg["payload"] ?: return)
                _connectedDeviceName.value = reply.deviceName

                val keyPair = controllerKeyPair ?: return
                val myNonce = controllerNonce ?: return
                val deviceNonce = android.util.Base64.decode(reply.deviceNonce, android.util.Base64.NO_WRAP)
                val devicePubBytes = android.util.Base64.decode(reply.devicePub, android.util.Base64.NO_WRAP)

                // Compute shared secret and session key
                val sharedSecret = keyManager.performKeyExchange(keyPair, devicePubBytes)
                sessionKey = keyManager.deriveSessionKey(myNonce, deviceNonce, sharedSecret, accountKey)

                // Send pairing proof
                val proofBytes = keyManager.createPairingProof(
                    sessionKey!!, accountKey, myNonce, deviceNonce,
                )
                val proof = ConnectProof(
                    proof = android.util.Base64.encodeToString(proofBytes, android.util.Base64.NO_WRAP),
                )
                val proofMsg = mapOf(
                    "type" to ConnectMessageTypes.CONNECT_PROOF,
                    "payload" to json.encodeToString(proof),
                )
                ws.send(json.encodeToString(proofMsg))
            }

            ConnectMessageTypes.CONNECT_ESTABLISHED -> {
                Timber.tag(TAG).d("Handshake complete — connected to ${_connectedDeviceName.value}")
                _connectionState.value = ConnectConnectionState.CONNECTED
                startPing()
                requestState()
            }

            ConnectMessageTypes.CONNECT_REJECTED -> {
                Timber.tag(TAG).w("Connection rejected — authentication failed")
                _connectionState.value = ConnectConnectionState.DISCONNECTED
                shouldReconnect = false
                ws.close(1000, "Auth failed")
            }
        }
    }

    private fun handleEncryptedMessage(text: String) {
        val key = sessionKey ?: return

        try {
            val envelope = json.decodeFromString<ConnectEnvelope>(text)
            val decrypted = keyManager.decrypt(
                android.util.Base64.decode(envelope.payload, android.util.Base64.NO_WRAP),
                key,
            )
            val payloadJson = String(decrypted, Charsets.UTF_8)

            when (envelope.type) {
                ConnectMessageTypes.STATE_UPDATE -> {
                    val state = json.decodeFromString<SyncStatePayload>(payloadJson)
                    val queue = state.queue ?: emptyList()
                    val previousTrack = _remoteState.value?.currentTrack
                    val resolvedCurrentTrack =
                        ConnectPlaybackState(
                            currentTrack = state.currentTrack,
                            queue = queue,
                        ).resolveDisplayTrack(previousTrack)

                    _remoteState.value = ConnectPlaybackState(
                        currentTrack = resolvedCurrentTrack,
                        isPlaying = state.isPlaying,
                        position = state.position,
                        lastUpdate = state.lastUpdate,
                        queue = queue,
                        volume = state.volume ?: 1f,
                    )
                }
                ConnectMessageTypes.PONG -> { /* keepalive acknowledged */ }
                ConnectMessageTypes.PLAYBACK_TRANSFERRED -> {
                    Timber.tag(TAG).d("Playback transfer acknowledged")
                }
                else -> Timber.tag(TAG).d("Unknown encrypted message type: ${envelope.type}")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to decrypt message")
        }
    }

    private fun handleDisconnect() {
        _connectionState.value = ConnectConnectionState.DISCONNECTED
        pingJob?.cancel()
        sessionKey = null

        if (shouldReconnect) {
            _connectionState.value = ConnectConnectionState.RECONNECTING
            reconnectJob = scope.launch {
                delay(RECONNECT_DELAY_MS)
                currentDevice?.let { connect(it) }
            }
        }
    }

    private fun startPing() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && _connectionState.value == ConnectConnectionState.CONNECTED) {
                delay(PING_INTERVAL_MS)
                sendEncrypted(ConnectMessageTypes.PING, "{}")
            }
        }
    }

    private fun MediaItem.toTrackInfoOrNull(): TrackInfo? {
        val meta = mediaMetadata
        val taggedDurationMs = metadata?.duration?.takeIf { it > 0 }?.times(1000L)
        val resolvedId = mediaId.takeIf { it.isNotBlank() } ?: metadata?.id?.takeIf { it.isNotBlank() }
        if (resolvedId == null) {
            Timber.tag(TAG).w("Skipping queue item without media id in Connect payload")
            return null
        }
        return TrackInfo(
            id = resolvedId,
            title = meta.title?.toString() ?: "Unknown",
            artist = meta.artist?.toString() ?: "Unknown",
            album = meta.albumTitle?.toString(),
            duration = taggedDurationMs ?: 0L,
            thumbnail = meta.artworkUri?.toString(),
        )
    }
}
