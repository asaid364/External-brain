package app.liferecord

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/** An NSD result is only an address hint. It carries no trust until its certificate pin is verified. */
data class AndroidDiscoveredPeer(
    val serviceName: String,
    val serverId: String,
    val stableHost: String,
    val address: InetAddress,
    val port: Int,
)

object AndroidLanNetworks {
    /** Returns only a user-routable LAN network; INTERNET and VALIDATED are deliberately irrelevant. */
    fun available(context: Context): List<Network> {
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.filter { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@filter false
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    fun requireSelected(context: Context, selected: Network): Network {
        require(selected in available(context)) { "请选择当前可用的 Wi-Fi 或以太网连接" }
        return selected
    }
}

class AndroidSyncDiscovery(
    context: Context,
    private val onChanged: (List<AndroidDiscoveredPeer>) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val peers = ConcurrentHashMap<String, AndroidDiscoveredPeer>()
    @Volatile private var running = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            running = false
            onError("局域网发现无法启动（$errorCode）")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            running = false
            onError("局域网发现无法停止（$errorCode）")
        }
        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType != SERVICE_TYPE && service.serviceType != "$SERVICE_TYPE.") return
            @Suppress("DEPRECATION")
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val protocol = info.attributes["protocol"]?.toString(Charsets.UTF_8) ?: return
                    val serverId = info.attributes["serverId"]?.toString(Charsets.UTF_8) ?: return
                    if (protocol != PROTOCOL || !SERVER_ID.matches(serverId)) return
                    @Suppress("DEPRECATION") val address = info.host ?: return
                    if (info.port !in 1..65535) return
                    val peer = AndroidDiscoveredPeer(
                        serviceName = info.serviceName,
                        serverId = serverId,
                        stableHost = "life-$serverId.local",
                        address = address,
                        port = info.port,
                    )
                    peers[info.serviceName] = peer
                    publish()
                }
            })
        }
        override fun onServiceLost(service: NsdServiceInfo) {
            peers.remove(service.serviceName)
            publish()
        }
    }

    @Synchronized fun start() {
        if (running) return
        peers.clear()
        running = true
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: RuntimeException) {
            running = false
            throw IllegalStateException("局域网发现无法启动", error)
        }
    }

    @Synchronized fun stop() {
        if (!running) return
        running = false
        try {
            manager.stopServiceDiscovery(listener)
        } catch (_: IllegalArgumentException) {
            // The platform already removed the listener after a discovery failure.
        } finally {
            peers.clear()
            publish()
        }
    }

    override fun close() = stop()

    private fun publish() = onChanged(peers.values.sortedWith(compareBy({ it.serviceName }, { it.serverId })))

    private companion object {
        const val SERVICE_TYPE = "_liferecord-sync._tcp."
        const val PROTOCOL = "1"
        val SERVER_ID = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,62}")
    }
}
