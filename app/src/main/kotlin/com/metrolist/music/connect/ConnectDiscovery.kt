/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.connect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Handles mDNS service advertisement and discovery for Metrolist Connect
 * using Android's native NsdManager.
 *
 * - Receivers advertise themselves as `_metroconnect._tcp`
 * - Controllers discover receivers filtered by account key fingerprint
 */
class ConnectDiscovery(
    private val context: Context,
) {
    companion object {
        private const val TAG = "ConnectDiscovery"
        private const val SERVICE_TYPE = "_metroconnect._tcp"
        private const val SERVICE_NAME_PREFIX = "MetroConnect_"

        const val TXT_VERSION = "ver"
        const val TXT_DEVICE_NAME = "name"
        const val TXT_ACCOUNT_FINGERPRINT = "akfp"
        const val TXT_PLAYING = "playing"
        const val TXT_STATE = "state"
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val wifiManager: android.net.wifi.WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager

    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    private val _discoveredDevices = MutableStateFlow<List<ConnectDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<ConnectDevice>> = _discoveredDevices.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var ownAccountFingerprint: String? = null
    private var ownServiceName: String? = null
    private var ownDeviceName: String? = null

    // Mapping from NSD service name to resolved host:port for removal on service lost
    private val serviceNameToKey = mutableMapOf<String, String>()

    private var lastRegisteredPort: Int? = null

    // Pending resolves — NsdManager allows only one resolve at a time on older APIs
    private val pendingResolves = mutableListOf<NsdServiceInfo>()
    private var isResolving = false

    // --- Advertisement ---

    /**
     * Updates the currently advertised service with new playback state.
     * With NsdManager, we must unregister and re-register to update TXT records.
     */
    fun updateAdvertisement(
        currentTrackId: String?,
        playbackState: DiscoveredPlaybackState,
    ) {
        if (!_isAdvertising.value) return
        val currentFingerprint = ownAccountFingerprint ?: return
        val currentName = ownDeviceName ?: return
        val port = lastRegisteredPort ?: return
        
        Timber.tag(TAG).d("Updating advertisement state to $playbackState")
        
        // Temporarily clear the flag so we don't think we've stopped completely
        stopAdvertising()
        
        // Brief delay needed by NsdManager before re-registering
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(200)
            advertise(port, currentName, currentFingerprint, currentTrackId, playbackState)
        }
    }

    /**
     * Registers this device as an mDNS service so controllers can discover it.
     */
    fun advertise(
        port: Int,
        deviceName: String,
        accountKeyFingerprint: String,
        currentTrackId: String? = null,
        playbackState: DiscoveredPlaybackState = DiscoveredPlaybackState.IDLE,
    ) {
        if (_isAdvertising.value) {
            Timber.tag(TAG).d("Already advertising, stopping first")
            stopAdvertising()
        }

        ownAccountFingerprint = accountKeyFingerprint
        ownDeviceName = deviceName
        // If deviceName is already an mDNS format (like updating), use it directly, else generate
        ownServiceName = if (deviceName.startsWith(SERVICE_NAME_PREFIX)) deviceName else SERVICE_NAME_PREFIX + accountKeyFingerprint.take(6)
        lastRegisteredPort = port

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = ownServiceName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(TXT_VERSION, "1")
            setAttribute(TXT_DEVICE_NAME, deviceName)
            setAttribute(TXT_ACCOUNT_FINGERPRINT, accountKeyFingerprint)
            setAttribute(TXT_PLAYING, currentTrackId ?: "")
            setAttribute(TXT_STATE, playbackState.name.lowercase())
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                ownServiceName = info.serviceName
                _isAdvertising.value = true
                Timber.tag(TAG).d("Service registered: ${info.serviceName} on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _isAdvertising.value = false
                Timber.tag(TAG).e("Registration failed: errorCode=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                _isAdvertising.value = false
                Timber.tag(TAG).d("Service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Timber.tag(TAG).e("Unregistration failed: errorCode=$errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to register service")
            _isAdvertising.value = false
        }
    }

    fun stopAdvertising() {
        registrationListener?.let { listener ->
            try {
                nsdManager.unregisterService(listener)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to unregister service")
            }
        }
        registrationListener = null
        _isAdvertising.value = false
    }

    // --- Discovery ---

    /**
     * Starts discovering Metrolist Connect services on the local network.
     * Only devices with the same account key fingerprint are added to [discoveredDevices].
     */
    fun startDiscovery(accountKeyFingerprint: String) {
        if (_isDiscovering.value) {
            Timber.tag(TAG).d("Already discovering, stopping first")
            stopDiscovery()
        }

        try {
            if (multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock("MetrolistConnectDiscovery")
                multicastLock?.setReferenceCounted(true)
            }
            multicastLock?.acquire()
            Timber.tag(TAG).d("MulticastLock acquired")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to acquire MulticastLock")
        }

        ownAccountFingerprint = accountKeyFingerprint
        _discoveredDevices.value = emptyList()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _isDiscovering.value = true
                Timber.tag(TAG).d("Discovery started for $serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Timber.tag(TAG).d("Service found: ${service.serviceName}")
                // Skip our own service
                if (service.serviceName == ownServiceName) {
                    Timber.tag(TAG).d("Skipping own service")
                    return
                }
                enqueueResolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Timber.tag(TAG).d("Service lost: ${service.serviceName}")
                val key = serviceNameToKey.remove(service.serviceName) ?: return
                val parts = key.split(":", limit = 2)
                if (parts.size == 2) {
                    val host = parts[0]
                    val port = parts[1].toIntOrNull() ?: return
                    _discoveredDevices.value = _discoveredDevices.value.filter {
                        !(it.host == host && it.port == port)
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _isDiscovering.value = false
                Timber.tag(TAG).d("Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isDiscovering.value = false
                Timber.tag(TAG).e("Start discovery failed: errorCode=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.tag(TAG).e("Stop discovery failed: errorCode=$errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start discovery")
            _isDiscovering.value = false
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to stop discovery")
            }
        }
        discoveryListener = null
        _isDiscovering.value = false
        pendingResolves.clear()
        serviceNameToKey.clear()

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Timber.tag(TAG).d("MulticastLock released")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to release MulticastLock")
        }
    }

    // --- Resolve queue (NsdManager serializes resolves on pre-API-34) ---

    private fun enqueueResolve(service: NsdServiceInfo) {
        synchronized(pendingResolves) {
            pendingResolves.add(service)
            if (!isResolving) {
                resolveNext()
            }
        }
    }

    private fun resolveNext() {
        val service: NsdServiceInfo
        synchronized(pendingResolves) {
            if (pendingResolves.isEmpty()) {
                isResolving = false
                return
            }
            isResolving = true
            service = pendingResolves.removeAt(0)
        }

        @Suppress("DEPRECATION")
        nsdManager.resolveService(
            service,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Timber.tag(TAG).e("Resolve failed for ${serviceInfo.serviceName}: errorCode=$errorCode")
                    resolveNext()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    handleResolvedService(serviceInfo)
                    resolveNext()
                }
            },
        )
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo) {
        val attributes = serviceInfo.attributes
        val fingerprint = attributes[TXT_ACCOUNT_FINGERPRINT]
            ?.let { String(it, Charsets.UTF_8) } ?: return
        val deviceName = attributes[TXT_DEVICE_NAME]
            ?.let { String(it, Charsets.UTF_8) } ?: serviceInfo.serviceName
        val playing = attributes[TXT_PLAYING]
            ?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
        val state = attributes[TXT_STATE]
            ?.let { String(it, Charsets.UTF_8) }
            ?.let { DiscoveredPlaybackState.fromString(it) }
            ?: DiscoveredPlaybackState.IDLE

        // Only show devices that belong to the same account
        if (fingerprint != ownAccountFingerprint) {
            Timber.tag(TAG).d("Ignoring device with different account: $deviceName")
            return
        }

        val host = serviceInfo.host?.hostAddress ?: return
        val port = serviceInfo.port

        // Track NSD service name → host:port for removal on service lost
        serviceNameToKey[serviceInfo.serviceName] = "$host:$port"

        val device = ConnectDevice(
            name = deviceName,
            host = host,
            port = port,
            accountKeyFingerprint = fingerprint,
            currentTrackId = playing,
            playbackState = state,
        )

        // Update or add the device
        val currentList = _discoveredDevices.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.host == host && it.port == port }
        if (existingIndex >= 0) {
            currentList[existingIndex] = device
        } else {
            currentList.add(device)
        }
        _discoveredDevices.value = currentList

        Timber.tag(TAG).d("Resolved device: $deviceName at $host:$port (state=$state)")
    }

    // --- Cleanup ---

    fun release() {
        stopAdvertising()
        stopDiscovery()
    }
}
