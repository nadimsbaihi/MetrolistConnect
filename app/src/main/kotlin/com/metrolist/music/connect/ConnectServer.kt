/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.connect

import androidx.media3.common.MediaItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.listentogether.PlaybackActions
import com.metrolist.music.listentogether.PlaybackActionPayload
import com.metrolist.music.listentogether.SyncStatePayload
import com.metrolist.music.listentogether.TrackInfo
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.MusicService
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded WebSocket server running on the receiver device.
 * Handles the X25519 ECDH handshake, then accepts encrypted playback commands
 * from authenticated controllers.
 */
class ConnectServer(
    private val musicService: MusicService,
    private val keyManager: ConnectKeyManager,
    private val accountKey: ByteArray,
    private val deviceName: String,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "ConnectServer"
        private const val PING_INTERVAL_MS = 15_000L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _port = MutableStateFlow<Int?>(null)
    val port: StateFlow<Int?> = _port.asStateFlow()

    private val _connectedControllers = MutableStateFlow<List<String>>(emptyList())
    val connectedControllers: StateFlow<List<String>> = _connectedControllers.asStateFlow()

    private var serverJob: Job? = null
    private var stateUpdateJob: Job? = null

    // Active sessions keyed by controller ID
    private val sessions = ConcurrentHashMap<String, ControllerSession>()

    private data class ControllerSession(
        val controllerId: String,
        val sessionKey: ByteArray,
        val sendFrame: suspend (String) -> Unit,
    )

    fun start() {
        if (serverJob != null) {
            Timber.tag(TAG).d("Server already running")
            return
        }

        val availablePort = findAvailablePort()
        _port.value = availablePort

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val server = embeddedServer(CIO, port = availablePort) {
                    install(WebSockets) {
                        pingPeriodMillis = PING_INTERVAL_MS
                        timeoutMillis = PING_INTERVAL_MS * 3
                        maxFrameSize = Long.MAX_VALUE
                    }
                    routing {
                        webSocket("/connect") {
                            handleConnection()
                        }
                    }
                }
                Timber.tag(TAG).d("Starting server on port $availablePort")
                server.start(wait = true)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Server failed")
                _port.value = null
            }
        }

        startStateUpdates()
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        stateUpdateJob?.cancel()
        stateUpdateJob = null
        sessions.clear()
        _connectedControllers.value = emptyList()
        _port.value = null
        Timber.tag(TAG).d("Server stopped")
    }

    /**
     * Handles a single WebSocket connection from a controller.
     * Called as the receiver of DefaultWebSocketServerSession.
     */
    private suspend fun DefaultWebSocketServerSession.handleConnection() {
        var controllerId: String? = null

        try {
            // Step 1: Receive CONNECT_HELLO from controller
            val helloFrame = incoming.receive() as? Frame.Text ?: return
            val helloMsg = json.decodeFromString<Map<String, String>>(helloFrame.readText())

            if (helloMsg["type"] != ConnectMessageTypes.CONNECT_HELLO) {
                Timber.tag(TAG).d("Expected CONNECT_HELLO, got: ${helloMsg["type"]}")
                return
            }

            val hello = json.decodeFromString<ConnectHello>(
                helloMsg["payload"] ?: return,
            )
            controllerId = hello.controllerId

            // Step 2: Generate our ephemeral keypair and compute session key
            val deviceKeyPair = keyManager.generateEphemeralKeyPair()
            val deviceNonce = keyManager.generateNonce()
            val controllerNonce = android.util.Base64.decode(hello.controllerNonce, android.util.Base64.NO_WRAP)
            val controllerPubBytes = android.util.Base64.decode(hello.controllerPub, android.util.Base64.NO_WRAP)

            val sharedSecret = keyManager.performKeyExchange(deviceKeyPair, controllerPubBytes)
            val sessionKey = keyManager.deriveSessionKey(controllerNonce, deviceNonce, sharedSecret, accountKey)

            // Send CONNECT_HELLO_REPLY
            val reply = ConnectHelloReply(
                devicePub = android.util.Base64.encodeToString(
                    keyManager.publicKeyBytes(deviceKeyPair),
                    android.util.Base64.NO_WRAP,
                ),
                deviceNonce = android.util.Base64.encodeToString(deviceNonce, android.util.Base64.NO_WRAP),
                deviceName = deviceName,
            )
            val replyMsg = mapOf(
                "type" to ConnectMessageTypes.CONNECT_HELLO_REPLY,
                "payload" to json.encodeToString(reply),
            )
            send(json.encodeToString(replyMsg))

            // Step 3: Receive CONNECT_PROOF from controller
            val proofFrame = incoming.receive() as? Frame.Text ?: return
            val proofMsg = json.decodeFromString<Map<String, String>>(proofFrame.readText())

            if (proofMsg["type"] != ConnectMessageTypes.CONNECT_PROOF) {
                Timber.tag(TAG).d("Expected CONNECT_PROOF, got: ${proofMsg["type"]}")
                return
            }

            val proof = json.decodeFromString<ConnectProof>(proofMsg["payload"] ?: return)
            val proofBytes = android.util.Base64.decode(proof.proof, android.util.Base64.NO_WRAP)

            if (!keyManager.verifyPairingProof(proofBytes, sessionKey, accountKey, controllerNonce, deviceNonce)) {
                Timber.tag(TAG).w("Pairing proof verification failed — different account")
                val rejectMsg = mapOf("type" to ConnectMessageTypes.CONNECT_REJECTED)
                send(json.encodeToString(rejectMsg))
                return
            }

            // Step 4: Send CONNECT_ESTABLISHED
            val establishedMsg = mapOf("type" to ConnectMessageTypes.CONNECT_ESTABLISHED)
            send(json.encodeToString(establishedMsg))

            Timber.tag(TAG).d("Controller authenticated: $controllerId")

            // Register session
            val wsSession = this
            val controllerSession = ControllerSession(
                controllerId = controllerId,
                sessionKey = sessionKey,
                sendFrame = { text -> wsSession.send(text) },
            )
            sessions[controllerId] = controllerSession
            updateControllerList()

            // Send initial state
            sendStateUpdate(controllerSession)

            // Process encrypted commands
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    handleEncryptedMessage(frame.readText(), controllerSession)
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            Timber.tag(TAG).d("Controller disconnected: $controllerId")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error handling connection from $controllerId")
        } finally {
            controllerId?.let {
                sessions.remove(it)
                updateControllerList()
            }
        }
    }

    private suspend fun handleEncryptedMessage(text: String, session: ControllerSession) {
        try {
            val envelope = json.decodeFromString<ConnectEnvelope>(text)
            val decryptedPayload = keyManager.decrypt(
                android.util.Base64.decode(envelope.payload, android.util.Base64.NO_WRAP),
                session.sessionKey,
            )
            val payloadJson = String(decryptedPayload, Charsets.UTF_8)

            when (envelope.type) {
                ConnectMessageTypes.PLAYBACK_COMMAND -> handlePlaybackCommand(payloadJson)
                ConnectMessageTypes.TRANSFER_PLAYBACK -> handleTransferPlayback(payloadJson, session)
                ConnectMessageTypes.REQUEST_STATE -> sendStateUpdate(session)
                ConnectMessageTypes.PING -> sendEncrypted(session, ConnectMessageTypes.PONG, "{}")
                ConnectMessageTypes.DISCONNECT -> {
                    sessions.remove(session.controllerId)
                    updateControllerList()
                }
                else -> Timber.tag(TAG).d("Unknown message type: ${envelope.type}")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to handle encrypted message")
        }
    }

    private suspend fun handlePlaybackCommand(payloadJson: String) {
        val command = json.decodeFromString<PlaybackActionPayload>(payloadJson)

        withContext(Dispatchers.Main) {
            val player = musicService.player

            when (command.action) {
                PlaybackActions.PLAY -> player.play()
                PlaybackActions.PAUSE -> player.pause()
                PlaybackActions.SEEK -> command.position?.let { player.seekTo(it) }
                PlaybackActions.SKIP_NEXT -> {
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                }
                PlaybackActions.SKIP_PREV -> {
                    if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                }
                PlaybackActions.CHANGE_TRACK -> {
                    val targetIndex = resolveTrackIndex(command, player)
                    if (targetIndex != null && targetIndex in 0 until player.mediaItemCount) {
                        player.seekToDefaultPosition(targetIndex)
                        player.playWhenReady = true
                    }
                }
                PlaybackActions.QUEUE_ADD -> {
                    val mediaItems = command.queue?.map { it.toMediaItem() }
                        ?: command.trackInfo?.let { listOf(it.toMediaItem()) }
                        ?: emptyList()
                    if (mediaItems.isNotEmpty()) {
                        if (command.insertNext == true) {
                            if (player.mediaItemCount == 0 || player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                                player.setMediaItems(mediaItems)
                                player.prepare()
                                player.playWhenReady = true
                            } else {
                                player.addMediaItems(player.nextMediaItemIndex, mediaItems)
                                player.prepare()
                            }
                        } else {
                            player.addMediaItems(mediaItems)
                            player.prepare()
                        }
                    }
                }
                PlaybackActions.SYNC_QUEUE -> {
                    val mediaItems = command.queue?.map { it.toMediaItem() }.orEmpty()
                    if (mediaItems.isNotEmpty()) {
                        val startIndex = resolveQueueStartIndex(command, mediaItems)
                        player.setMediaItems(mediaItems, startIndex, command.position ?: 0L)
                        player.prepare()
                        player.playWhenReady = true
                        command.queueTitle?.let { musicService.queueTitle = it }
                    }
                }
                PlaybackActions.SET_VOLUME -> command.volume?.let { player.volume = it }
                else -> Timber.tag(TAG).d("Unhandled playback action: ${command.action}")
            }
        }

        // Broadcast updated state to all controllers
        broadcastStateUpdate()
    }

    private suspend fun handleTransferPlayback(
        payloadJson: String,
        @Suppress("UNUSED_PARAMETER") session: ControllerSession,
    ) {
        val transfer = json.decodeFromString<TransferPlaybackPayload>(payloadJson)
        Timber.tag(TAG).d("Received transfer playback request")

        // Send acknowledgement that playback is being transferred
        broadcastEncrypted(ConnectMessageTypes.PLAYBACK_TRANSFERRED, json.encodeToString(transfer))

        Timber.tag(TAG).d("Transfer payload: track=${transfer.currentTrack?.title}, pos=${transfer.position}")
    }

    private suspend fun sendStateUpdate(session: ControllerSession) {
        val state = buildStatePayload()
        sendEncrypted(session, ConnectMessageTypes.STATE_UPDATE, json.encodeToString(state))
    }

    private suspend fun broadcastStateUpdate() {
        val state = buildStatePayload()
        broadcastEncrypted(ConnectMessageTypes.STATE_UPDATE, json.encodeToString(state))
    }

    private suspend fun buildStatePayload(): SyncStatePayload = withContext(Dispatchers.Main) {
        val player = musicService.player
        val currentTrack = player.currentMediaItem?.toTrackInfo(durationOverrideMs = player.duration)
        
        SyncStatePayload(
            currentTrack = currentTrack,
            isPlaying = player.isPlaying,
            position = player.currentPosition,
            lastUpdate = System.currentTimeMillis(),
            queue = (0 until player.mediaItemCount).map { i -> player.getMediaItemAt(i).toTrackInfo() },
            volume = player.volume,
        )
    }

    private fun MediaItem.toTrackInfo(durationOverrideMs: Long? = null): TrackInfo {
        val meta = mediaMetadata
        val taggedMetadata = metadata
        val taggedDurationMs = metadata?.duration?.takeIf { it > 0 }?.times(1000L)
        val resolvedDuration = durationOverrideMs?.takeIf { it > 0 } ?: taggedDurationMs ?: 0L

        return TrackInfo(
            id = mediaId,
            title =
                meta.title?.toString()?.takeIf { it.isNotBlank() }
                    ?: taggedMetadata?.title?.takeIf { it.isNotBlank() }
                    ?: "Unknown",
            artist =
                meta.artist?.toString()?.takeIf { it.isNotBlank() }
                    ?: taggedMetadata?.artists
                        ?.map { it.name }
                        ?.filter { it.isNotBlank() }
                        ?.joinToString(", ")
                        ?.takeIf { it.isNotBlank() }
                    ?: "Unknown",
            album = meta.albumTitle?.toString()?.takeIf { it.isNotBlank() } ?: taggedMetadata?.album?.title,
            duration = resolvedDuration,
            thumbnail = meta.artworkUri?.toString() ?: taggedMetadata?.thumbnailUrl,
        )
    }

    private fun resolveTrackIndex(command: PlaybackActionPayload, player: androidx.media3.common.Player): Int? {
        command.trackId?.let { trackId ->
            val trackIndex = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == trackId }
            if (trackIndex != null) return trackIndex
        }
        return command.position?.toInt()
    }

    private fun resolveQueueStartIndex(command: PlaybackActionPayload, mediaItems: List<MediaItem>): Int {
        command.trackId?.let { trackId ->
            val trackIndex = mediaItems.indexOfFirst { it.mediaId == trackId }
            if (trackIndex >= 0) return trackIndex
        }
        return 0
    }

    private fun TrackInfo.toMediaItem(): MediaItem = MediaMetadata(
        id = id,
        title = title,
        artists = listOf(MediaMetadata.Artist(id = "", name = artist)),
        album = album?.let { MediaMetadata.Album(id = "", title = it) },
        duration = (duration / 1000).toInt(),
        thumbnailUrl = thumbnail,
    ).toMediaItem()

    private suspend fun sendEncrypted(session: ControllerSession, type: String, payloadJson: String) {
        try {
            val encrypted = keyManager.encrypt(payloadJson.toByteArray(Charsets.UTF_8), session.sessionKey)
            val envelope = ConnectEnvelope(
                type = type,
                payload = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP),
            )
            session.sendFrame(json.encodeToString(envelope))
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to send encrypted message to ${session.controllerId}")
        }
    }

    private suspend fun broadcastEncrypted(type: String, payloadJson: String) {
        sessions.values.forEach { session ->
            sendEncrypted(session, type, payloadJson)
        }
    }

    /**
     * Periodically sends state updates to all connected controllers.
     */
    private fun startStateUpdates() {
        stateUpdateJob?.cancel()
        stateUpdateJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (sessions.isNotEmpty()) {
                    try {
                        broadcastStateUpdate()
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to broadcast state update")
                    }
                }
            }
        }
    }

    private fun updateControllerList() {
        _connectedControllers.value = sessions.keys.toList()
    }

    private fun findAvailablePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}
