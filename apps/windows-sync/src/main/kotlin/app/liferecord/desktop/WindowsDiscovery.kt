package app.liferecord.desktop

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

data class PrivateLanInterface(
    val id: String,
    val interfaceName: String,
    val displayName: String,
    val address: InetAddress,
)

data class DiscoveryState(
    val active: Boolean = false,
    val selected: PrivateLanInterface? = null,
    val error: String? = null,
)

/** mDNS is bound to exactly the interface/address selected by the user. */
class WindowsDiscovery(
    private val identity: WindowsServerIdentity,
    private val stateChanged: (DiscoveryState) -> Unit = {},
    private val interfaceLost: () -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val monitor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "liferecord-interface-monitor").apply { isDaemon = true }
    }
    private var mdns: JmDNS? = null
    private var service: ServiceInfo? = null
    private var selected: PrivateLanInterface? = null
    private var watch: ScheduledFuture<*>? = null

    fun availableInterfaces(): List<PrivateLanInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
        .asSequence()
        .filter(::eligibleInterface)
        .flatMap { network -> Collections.list(network.inetAddresses).asSequence().map { network to it } }
        .filter { (_, address) -> isPrivateLan(address) }
        .map { (network, address) ->
            val normalized = address.hostAddress.substringBefore('%')
            PrivateLanInterface("${network.name}|$normalized", network.name, network.displayName ?: network.name, address)
        }
        .sortedWith(compareBy<PrivateLanInterface>({ it.address is Inet6Address }, { it.displayName }, { it.address.hostAddress }))
        .toList()

    fun bind(interfaceId: String, port: Int): PrivateLanInterface = synchronized(lock) {
        require(port in 1..65535) { "同步端口无效" }
        val candidate = availableInterfaces().singleOrNull { it.id == interfaceId }
            ?: throw IllegalArgumentException("所选私有局域网接口已不可用")
        stopLocked(notify = false)
        try {
            val instance = JmDNS.create(candidate.address, identity.stableHostName.removeSuffix(".local"))
            mdns = instance
            val info = ServiceInfo.create(
                SERVICE_TYPE,
                "LifeRecord-${identity.serverId}",
                port,
                0,
                0,
                mapOf("protocol" to "1", "serverId" to identity.serverId, "host" to identity.stableHostName),
            )
            instance.registerService(info)
            service = info
            selected = candidate
            watch = monitor.scheduleWithFixedDelay(::checkSelected, 2, 2, TimeUnit.SECONDS)
            stateChanged(DiscoveryState(true, candidate))
            candidate
        } catch (failure: Throwable) {
            stopLocked(notify = false)
            stateChanged(DiscoveryState(error = "无法在所选局域网接口发布发现服务"))
            throw IllegalStateException("无法在所选局域网接口发布发现服务", failure)
        }
    }

    fun selected(): PrivateLanInterface? = synchronized(lock) { selected }

    private fun checkSelected() {
        val expected = synchronized(lock) { selected } ?: return
        if (availableInterfaces().none { it.id == expected.id }) {
            synchronized(lock) { stopLocked(notify = true) }
            interfaceLost()
        }
    }

    fun stop() = synchronized(lock) { stopLocked(notify = true) }

    private fun stopLocked(notify: Boolean) {
        watch?.cancel(false)
        watch = null
        val instance = mdns
        val registered = service
        service = null
        mdns = null
        selected = null
        if (registered != null) runCatching { instance?.unregisterService(registered) }
        runCatching { instance?.close() }
        if (notify) stateChanged(DiscoveryState())
    }

    override fun close() {
        stop()
        monitor.shutdownNow()
    }

    companion object {
        const val SERVICE_TYPE = "_liferecord-sync._tcp.local."
        private val blockedNames = listOf("vpn", "tun", "tap", "wireguard", "tailscale", "zerotier", "hamachi", "loopback")

        private fun eligibleInterface(network: NetworkInterface): Boolean = try {
            val name = "${network.name} ${network.displayName.orEmpty()}".lowercase(Locale.ROOT)
            network.isUp && !network.isLoopback && !network.isVirtual && !network.isPointToPoint && blockedNames.none(name::contains)
        } catch (_: Throwable) { false }

        fun isPrivateLan(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress || address.isLinkLocalAddress) return false
            val bytes = address.address
            return when (address) {
                is Inet4Address -> {
                    val first = bytes[0].toInt() and 0xff
                    val second = bytes[1].toInt() and 0xff
                    first == 10 || first == 172 && second in 16..31 || first == 192 && second == 168
                }
                is Inet6Address -> (bytes[0].toInt() and 0xfe) == 0xfc
                else -> false
            }
        }
    }
}
