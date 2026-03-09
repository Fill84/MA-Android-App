package io.musicassistant.companion.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredServer(val name: String, val address: String, val url: String)

class ServerDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "ServerDiscovery"
        private const val SERVICE_TYPE = "_mass._tcp."
    }

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var isDiscovering = false

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val discoveryListener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "mDNS discovery started for $serviceType")
                    _isSearching.value = true
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                    resolveService(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                    _servers.value = _servers.value.filter { it.name != serviceInfo.serviceName }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "mDNS discovery stopped")
                    _isSearching.value = false
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery start failed: error $errorCode")
                    _isSearching.value = false
                    isDiscovering = false
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Discovery stop failed: error $errorCode")
                }
            }

    fun startDiscovery() {
        if (isDiscovering) return

        _servers.value = emptyList()
        acquireMulticastLock()

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            isDiscovering = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) return

        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop discovery", e)
        }
        isDiscovering = false
        releaseMulticastLock()
    }

    fun refresh() {
        stopDiscovery()
        startDiscovery()
    }

    @Suppress("DEPRECATION")
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed for ${service.serviceName}: error $errorCode")
                    }

                    override fun onServiceResolved(service: NsdServiceInfo) {
                        val host = service.host?.hostAddress ?: return
                        val port = service.port
                        val name = service.serviceName

                        Log.d(TAG, "Resolved: $name at $host:$port")

                        val server =
                                DiscoveredServer(
                                        name = name,
                                        address = "$host:$port",
                                        url = "http://$host:$port"
                                )

                        _servers.value =
                                (_servers.value.filter { it.name != name } + server).sortedBy {
                                    it.name
                                }
                    }
                }
        )
    }

    private fun acquireMulticastLock() {
        if (multicastLock == null) {
            val wifiManager =
                    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock =
                    wifiManager.createMulticastLock("ma_mdns_lock").apply {
                        setReferenceCounted(true)
                        acquire()
                    }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }
}
