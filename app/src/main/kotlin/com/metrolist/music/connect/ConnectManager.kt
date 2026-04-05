/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.connect

import android.content.Context
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.MetrolistConnectDeviceNameKey
import com.metrolist.music.constants.MetrolistConnectEnabledKey
import com.metrolist.music.utils.get
import com.metrolist.music.utils.dataStore
import com.metrolist.music.playback.MusicService
import com.metrolist.innertube.utils.parseCookieString
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Orchestrator for Metrolist Connect. Manages the lifecycle of discovery,
 * server (receiver), and client (controller) components.
 *
 * - Automatically starts services when the user is logged in and Connect is enabled
 * - Provides a single API surface for the UI and MusicService to interact with
 * - Requires Android API 31+ for X25519 key exchange
 */
class ConnectManager(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "ConnectManager"
    }

    val keyManager = ConnectKeyManager()
    val discovery = ConnectDiscovery(context)

    private var server: ConnectServer? = null
    private var client: ConnectClient? = null
    private var musicService: MusicService? = null
    private var accountKey: ByteArray? = null
    private var notificationManager: ConnectNotificationManager? = null
    private val receiverObserverJobs = mutableListOf<Job>()

    private var playerListener: androidx.media3.common.Player.Listener? = null
    
    // --- Public state ---

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    private val _isControlling = MutableStateFlow(false)
    val isControlling: StateFlow<Boolean> = _isControlling.asStateFlow()

    val discoveredDevices: StateFlow<List<ConnectDevice>> = discovery.discoveredDevices

    private val _remotePlaybackState = MutableStateFlow<ConnectPlaybackState?>(null)
    val remotePlaybackState: StateFlow<ConnectPlaybackState?> = _remotePlaybackState.asStateFlow()

    val connectedDeviceName: MutableStateFlow<String?> = MutableStateFlow(null)
    val connectedControllers: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())

    private var initialized = false

    // --- Initialization ---

    /**
     * Called from MusicService.onCreate(). Derives the account key
     * and starts services if enabled.
     */
    fun initialize(musicService: MusicService) {
        if (initialized) return
        this.musicService = musicService
        initialized = true

        notificationManager = ConnectNotificationManager(context)

        scope.launch {
            resolveAccountKeyAndStart()
        }


        // Observe state changes for notifications
        scope.launch {
            combine(_isControlling, _isReceiving, connectedDeviceName, _remotePlaybackState, connectedControllers) { controlling, receiving, name, remote, controllers ->
                ControlState(controlling, receiving, name, remote, controllers)
            }.collect {
                updateNotification()
            }
        }
    }

    private data class ControlState(
        val controlling: Boolean,
        val receiving: Boolean,
        val name: String?,
        val remote: ConnectPlaybackState?,
        val controllers: List<String>,
    )

    // --- Account key resolution ---

    /**
     * Resolves the account key using the best available stable identifier.
     * If none is stored locally (old logins), fetches from YouTube API and saves for next time.
     */
    private suspend fun resolveAccountKeyAndStart() {
        val channelHandle = dataStore.get(com.metrolist.music.constants.AccountChannelHandleKey, "")
        val email = dataStore.get(com.metrolist.music.constants.AccountEmailKey, "")
        val name = dataStore.get(com.metrolist.music.constants.AccountNameKey, "")
        val dataSyncId = dataStore.get(com.metrolist.music.constants.DataSyncIdKey, "")

        val stableIdentifier = listOf(channelHandle, email, name, dataSyncId)
            .firstOrNull { it.isNotBlank() }

        if (stableIdentifier != null) {
            val identifierType = when {
                channelHandle.isNotBlank() -> "channelHandle"
                email.isNotBlank() -> "email"
                name.isNotBlank() -> "name"
                else -> "dataSyncId"
            }
            Timber.tag(TAG).d("Using $identifierType as Connect account key source")
            accountKey = keyManager.deriveAccountKey(stableIdentifier)
        } else {
            // No stable identifier stored — device logged in before profile fields were added.
            // Fetch from YouTube API to get a stable, cross-device identifier.
            val cookie = dataStore.get(InnerTubeCookieKey, "")
            if (cookie.isBlank()) {
                Timber.tag(TAG).d("Not logged in — Connect disabled")
                return
            }

            Timber.tag(TAG).d("No local account profile, fetching from YouTube API…")
            try {
                val accountInfo = withContext(Dispatchers.IO) {
                    com.metrolist.innertube.YouTube.accountInfo().getOrThrow()
                }
                val fetchedHandle = accountInfo.channelHandle.orEmpty()
                val fetchedEmail = accountInfo.email.orEmpty()
                val fetchedName = accountInfo.name

                // Persist for future launches so we don't need to fetch again
                if (fetchedHandle.isNotBlank() || fetchedEmail.isNotBlank()) {
                    context.dataStore.edit { prefs ->
                        if (fetchedHandle.isNotBlank()) prefs[com.metrolist.music.constants.AccountChannelHandleKey] = fetchedHandle
                        if (fetchedEmail.isNotBlank()) prefs[com.metrolist.music.constants.AccountEmailKey] = fetchedEmail
                        if (fetchedName.isNotBlank()) prefs[com.metrolist.music.constants.AccountNameKey] = fetchedName
                    }
                    Timber.tag(TAG).d("Fetched and cached account profile: $fetchedEmail / $fetchedHandle")
                }

                val identifier = listOf(fetchedHandle, fetchedEmail, fetchedName)
                    .firstOrNull { it.isNotBlank() }

                if (identifier == null) {
                    Timber.tag(TAG).w("YouTube API returned no usable account identifier — Connect disabled")
                    return
                }
                accountKey = keyManager.deriveAccountKey(identifier)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to fetch account info — Connect disabled")
                return
            }
        }

        Timber.tag(TAG).d("Account key derived, fingerprint: ${keyManager.accountKeyFingerprint(accountKey!!)}")

        val enabled = dataStore.get(MetrolistConnectEnabledKey, true)
        if (enabled) {
            withContext(Dispatchers.Main) {
                startServices()
            }
        }
    }

    // --- Service lifecycle ---

    fun startServices() {
        val key = accountKey ?: return
        val service = musicService ?: return
        val fingerprint = keyManager.accountKeyFingerprint(key)
        val deviceName = dataStore.get(MetrolistConnectDeviceNameKey, defaultDeviceName())

        // Start receiver mode unless we are currently in controller mode.
        if (!_isControlling.value) {
            ensureReceiverAvailable(key, service, fingerprint, deviceName)
        }

        // Start discovery (find other devices)
        discovery.startDiscovery(fingerprint)

        // Initialize client for potential use
        if (client == null) {
            val c = ConnectClient(keyManager, key, scope)
            client = c
            
            // Forward client state
            scope.launch {
                c.connectionState.collect { state ->
                    val isControllerConnected = state == ConnectConnectionState.CONNECTED
                    if (isControllerConnected) {
                        disableReceiverMode()
                    } else {
                        ensureReceiverAvailable(
                            key = key,
                            service = service,
                            fingerprint = fingerprint,
                            deviceName = dataStore.get(MetrolistConnectDeviceNameKey, defaultDeviceName()),
                        )
                    }
                    _isControlling.value = isControllerConnected
                }
            }
            scope.launch {
                c.remoteState.collect { remote ->
                    _remotePlaybackState.value = remote
                }
            }
            scope.launch {
                c.connectedDeviceName.collect { name ->
                    connectedDeviceName.value = name
                }
            }
        }

        // Auto-connect logic: sync automatically to active playing remote devices if we're idle
        scope.launch {
            discovery.discoveredDevices.collect { devices ->
                maybeAutoConnectToPlayingRemote(service, devices)
            }
        }

        Timber.tag(TAG).d("Services started")
    }

    fun stopServices() {
        playerListener?.let { musicService?.player?.removeListener(it) }
        playerListener = null
        disableReceiverMode()
        client?.disconnect()
        discovery.release()
        _isControlling.value = false
        connectedControllers.value = emptyList()
        Timber.tag(TAG).d("Services stopped")
    }

    // --- Controller actions ---

    /**
     * Connects to a remote device as controller.
     */
    fun connectToDevice(device: ConnectDevice) {
        val c = client ?: run {
            val key = accountKey ?: return
            ConnectClient(keyManager, key, scope).also { client = it }
        }

        c.connect(device)
    }

    fun disconnectFromDevice() {
        client?.disconnect()
        _isControlling.value = false
        _remotePlaybackState.value = null
        connectedDeviceName.value = null
    }

    // --- Playback commands (forwarded to client) ---

    fun play() = client?.play()
    fun pause() = client?.pause()
    fun seekTo(position: Long) = client?.seekTo(position)
    fun skipNext() = client?.skipNext()
    fun skipPrevious() = client?.skipPrevious()
    fun setVolume(volume: Float) = client?.setVolume(volume)

    fun changeTrack(index: Int, trackId: String? = null) = client?.changeTrack(index, trackId)

    fun playNext(items: List<MediaItem>) = client?.playNext(items)

    fun addToQueue(item: MediaItem, playNext: Boolean = false) = client?.addToQueue(item, playNext)

    fun addToQueue(items: List<MediaItem>, playNext: Boolean = false) = client?.addToQueue(items, playNext)

    fun playQueue(
        items: List<MediaItem>,
        queueTitle: String? = null,
        currentTrackId: String? = items.firstOrNull()?.mediaId,
        position: Long = 0L,
    ) = client?.playQueue(items, queueTitle, currentTrackId, position)

    /**
     * Transfers playback from the remote device to this device.
     * Pauses the remote player, loads the current track locally, and disconnects.
     */
    fun takeOverPlayback() {
        val remote = _remotePlaybackState.value ?: return
        val service = musicService ?: return
        val currentTrack = remote.currentTrack ?: return

        // Pause the remote device first
        client?.pause()

        // Load the current track into the local player via YouTubeQueue
        scope.launch {
            try {
                val endpoint = com.metrolist.innertube.models.WatchEndpoint(videoId = currentTrack.id)
                val queue = com.metrolist.music.playback.queues.YouTubeQueue(endpoint)
                service.playQueue(queue, playWhenReady = remote.isPlaying)

                // Seek to the position the remote was at once the player starts
                delay(500) // Brief delay for player preparation
                withContext(Dispatchers.Main) {
                    if (remote.position > 0) {
                        service.player.seekTo(remote.position)
                    }
                }
                Timber.tag(TAG).d("Took over playback: ${currentTrack.title} at ${remote.position}ms")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to take over playback")
            }
        }

        disconnectFromDevice()
    }

    // --- Cleanup ---

    fun release() {
        stopServices()
        notificationManager?.dismiss()
        client = null
        initialized = false
    }

    // --- Utilities ---

    private fun defaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercaseChar() }
        val model = Build.MODEL
        // If model already starts with manufacturer, don't repeat
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    private fun maybeAutoConnectToPlayingRemote(
        service: MusicService,
        devices: List<ConnectDevice> = discovery.discoveredDevices.value,
    ) {
        if (_isControlling.value) return
        if (_isReceiving.value) return
        if (service.player.isPlaying) return

        val connectionState = client?.connectionState?.value
        if (
            connectionState == ConnectConnectionState.CONNECTING ||
            connectionState == ConnectConnectionState.HANDSHAKING ||
            connectionState == ConnectConnectionState.RECONNECTING
        ) {
            return
        }

        val playingRemote = devices.firstOrNull { it.playbackState == DiscoveredPlaybackState.PLAYING }
        if (playingRemote != null) {
            Timber.tag(TAG).d("Auto-connecting to active playing device: ${playingRemote.name}")
            connectToDevice(playingRemote)
        }
    }

    private fun ensureReceiverAvailable(
        key: ByteArray,
        service: MusicService,
        fingerprint: String,
        deviceName: String,
    ) {
        if (server == null) {
            val startedServer = ConnectServer(service, keyManager, key, deviceName, scope)
            server = startedServer
            startedServer.start()

            receiverObserverJobs += scope.launch {
                startedServer.connectedControllers.collect { controllers ->
                    if (server !== startedServer) return@collect
                    connectedControllers.value = controllers
                    _isReceiving.value = controllers.isNotEmpty()
                    updateNotification()
                }
            }

            receiverObserverJobs += scope.launch {
                startedServer.port.collect { port ->
                    if (server !== startedServer) return@collect
                    if (port != null) {
                        discovery.advertise(
                            port = port,
                            deviceName = deviceName,
                            accountKeyFingerprint = fingerprint,
                            currentTrackId = service.player.currentMediaItem?.mediaId,
                            playbackState = if (service.player.isPlaying) DiscoveredPlaybackState.PLAYING else DiscoveredPlaybackState.IDLE,
                        )
                    }
                }
            }
        }

        if (playerListener == null) {
            playerListener = object : androidx.media3.common.Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val trackId = service.player.currentMediaItem?.mediaId
                    val state = if (isPlaying) DiscoveredPlaybackState.PLAYING else DiscoveredPlaybackState.IDLE
                    discovery.updateAdvertisement(trackId, state)
                    updateNotification()
                    maybeAutoConnectToPlayingRemote(service)
                }

                override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                    val trackId = item?.mediaId
                    val state = if (service.player.isPlaying) DiscoveredPlaybackState.PLAYING else DiscoveredPlaybackState.IDLE
                    discovery.updateAdvertisement(trackId, state)
                    updateNotification()
                }
            }
            service.player.addListener(playerListener!!)
        }
    }

    private fun disableReceiverMode() {
        receiverObserverJobs.forEach { it.cancel() }
        receiverObserverJobs.clear()
        discovery.stopAdvertising()
        server?.stop()
        server = null
        connectedControllers.value = emptyList()
        _isReceiving.value = false
    }

    private fun updateNotification() {
        val manager = notificationManager ?: return
        val service = musicService
        val controlling = _isControlling.value
        val receiving = _isReceiving.value

        when {
            controlling && connectedDeviceName.value != null -> {
                manager.dismiss()
            }

            receiving -> {
                val metadata = service?.player?.currentMediaItem?.mediaMetadata
                manager.showReceiverNotification(
                    controllerNames = connectedControllers.value,
                    trackTitle = metadata?.title?.toString(),
                    trackArtist = metadata?.artist?.toString(),
                    isPlaying = service?.player?.isPlaying == true,
                )
            }

            else -> manager.dismiss()
        }
    }
}
