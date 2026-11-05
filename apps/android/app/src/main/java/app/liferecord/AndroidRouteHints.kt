package app.liferecord

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.AtomicFile
import java.io.File
import java.net.InetAddress
import kotlinx.serialization.json.*

/** Restart hint only. No identity, certificate or bearer credential is learned from this file. */
internal class AndroidRouteHints(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "sync-route-hint.json"))
    data class Hint(val serverId: String, val address: InetAddress, val port: Int, val interfaceName: String?)

    @Synchronized fun read(): Hint? {
        if (!file.baseFile.exists()) return null
        return runCatching {
            val p = file.openRead().bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            val host = p.getValue("address").jsonPrimitive.content
            require(host.matches(Regex("[0-9a-fA-F:.%]+")))
            Hint(p.getValue("serverId").jsonPrimitive.content, InetAddress.getByName(host),
                p.getValue("port").jsonPrimitive.int, p["interface"]?.jsonPrimitive?.contentOrNull)
                .also { require(it.port in 1..65535) }
        }.getOrNull()
    }

    @Synchronized fun write(route: AndroidPeerRoute, connectivity: ConnectivityManager) {
        val text = buildJsonObject {
            put("serverId", route.serverId)
            put("address", route.address.hostAddress)
            put("port", route.port)
            connectivity.getLinkProperties(route.network)?.interfaceName?.let { put("interface", it) }
        }.toString()
        val stream = file.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    @Synchronized fun clear() = file.delete()

    fun network(context: Context, address: InetAddress, preferredInterface: String? = null): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val networks = AndroidLanNetworks.available(context)
        return networks.firstOrNull { manager.getLinkProperties(it)?.interfaceName == preferredInterface && preferredInterface != null }
            ?: networks.firstOrNull { network -> manager.getLinkProperties(network)?.routes?.any { !it.isDefaultRoute && it.matches(address) } == true }
            ?: networks.singleOrNull()
    }
}
