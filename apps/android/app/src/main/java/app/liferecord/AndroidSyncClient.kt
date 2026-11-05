package app.liferecord

import android.content.Context
import android.net.Network
import app.liferecord.core.SyncExchange
import app.liferecord.core.SyncRepository
import app.liferecord.core.IncomingState
import app.liferecord.core.SyncStatus
import app.liferecord.core.SYNC_PROTOCOL
import app.liferecord.core.SYNC_DATA_SCHEMA
import app.liferecord.core.SyncWire
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A route is replaceable when DHCP changes; trust remains bound to stableHost and certificateSha256. */
data class AndroidPeerRoute(
    val serverId: String,
    val stableHost: String,
    val address: InetAddress,
    val port: Int,
    val certificateSha256: String,
    val network: Network,
) {
    init {
        require(stableHost == "life-$serverId.local")
        require(port in 1..65535)
        require(FINGERPRINT.matches(certificateSha256))
        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        require(address.isSiteLocalAddress || address.isLinkLocalAddress || bytes.size == 16 && (first and 0xfe) == 0xfc) {
            "请选择私有局域网地址"
        }
    }
    private companion object { val FINGERPRINT = Regex("[0-9a-fA-F]{64}") }
}

data class PairingRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val clientId: String,
    val deviceName: String,
    val code: String,
)

sealed interface PairingResult {
    data class Pending(val requestId: String) : PairingResult
    data class Approved(val peer: AndroidSyncPeer) : PairingResult
    data class Rejected(val code: String, val message: String) : PairingResult
    data class TransportError(val message: String) : PairingResult
}

sealed interface SyncRoundResult {
    data class Complete(val pushedThrough: Long, val pulledThrough: Long) : SyncRoundResult
    data class Stopped(val code: String, val message: String) : SyncRoundResult
    data class Retryable(val message: String) : SyncRoundResult
}
class AndroidSyncFailure(val code: String, val publicMessage: String, val retryable: Boolean) : Exception(publicMessage)
data class AndroidPeerCounts(val records: Long, val drafts: Long, val reports: Long, val goals: Long, val reviews: Long)

class AndroidSyncClient(private val context: Context) {
    @Volatile var remoteCounts: AndroidPeerCounts? = null
        private set
    /** Authenticated metadata only; permitted before either side consents to content merge. */
    fun status(route: AndroidPeerRoute, peer: AndroidSyncPeer, exchange: SyncExchange, repository: SyncRepository): SyncStatus {
        requireRouteMatchesPeer(route, peer)
        val identity = repository.identity()
        val value = getStatus(route, peer, identity.databaseId, exchange.status(peer.pairId))
        require(value.localDatabaseId == peer.peerDatabaseId && (value.peerDatabaseId == null || value.peerDatabaseId == identity.databaseId) &&
            value.spaceId == peer.spaceId && value.spaceId == identity.spaceId) { "电脑或同步空间身份已变化，请重新核验" }
        return value
    }

    fun generation(route: AndroidPeerRoute, peer: AndroidSyncPeer, localDatabaseId: String, operation: String, body: JsonObject): JsonObject {
        require(operation in setOf("claim", "heartbeat", "finish"))
        requireRouteMatchesPeer(route, peer)
        return try {
            execute(route, peer, "POST", listOf("ai", "v1", operation), body.toString(), localDatabaseId).use {
                requireSuccess(it)
                responseObject(it)
            }
        } catch (error: SyncHttpFailure) {
            throw AndroidSyncFailure(error.code, error.publicMessage, error.retryable)
        } catch (error: Exception) {
            throw AndroidSyncFailure("TRANSPORT", publicTransportMessage(error), error !is javax.net.ssl.SSLException && error !is CertificateException)
        }
    }

    fun goalGeneration(route: AndroidPeerRoute, peer: AndroidSyncPeer, localDatabaseId: String, operation: String, body: JsonObject): JsonObject {
        require(operation in setOf("status", "claim", "heartbeat", "finish"))
        requireRouteMatchesPeer(route, peer)
        return try {
            execute(route, peer, "POST", listOf("goal-ai", "v1", operation), body.toString(), localDatabaseId).use {
                requireSuccess(it)
                responseObject(it)
            }
        } catch (error: SyncHttpFailure) {
            throw AndroidSyncFailure(error.code, error.publicMessage, error.retryable)
        } catch (error: Exception) {
            throw AndroidSyncFailure("TRANSPORT", publicTransportMessage(error), error !is javax.net.ssl.SSLException && error !is CertificateException)
        }
    }

    fun pair(route: AndroidPeerRoute, request: PairingRequest): PairingResult {
        val code = request.code.replace("-", "").replace(" ", "").uppercase()
        require(PAIR_CODE.matches(code)) { "配对码格式无效" }
        require(request.requestId.isNotBlank() && request.clientId.isNotBlank() && request.deviceName.isNotBlank())
        val body = buildJsonObject {
            put("requestId", request.requestId)
            put("clientId", request.clientId)
            put("deviceName", request.deviceName)
            put("code", code)
        }.toString()
        return try {
            execute(route, null, "POST", listOf("pair", "v1", "request"), body).use { response ->
                when (response.code) {
                    202 -> PairingResult.Pending(request.requestId)
                    200 -> {
                        val value = responseObject(response)
                        val serverId = value.string("serverId")
                        val stableHost = value.string("stableHostName")
                        require(serverId == route.serverId && stableHost == route.stableHost) { "服务器身份与已核验地址不一致" }
                        PairingResult.Approved(
                            AndroidSyncPeer(
                                serverId = serverId,
                                displayName = value.optionalString("displayName") ?: route.stableHost,
                                stableHost = stableHost,
                                port = route.port,
                                certificateSha256 = route.certificateSha256.lowercase(),
                                pairId = value.string("pairId"),
                                bearerToken = value.string("token"),
                                spaceId = value.string("spaceId"),
                                peerDatabaseId = value.string("serverDatabaseId"),
                            )
                        )
                    }
                    else -> response.errorResult()
                }
            }
        } catch (error: Exception) {
            PairingResult.TransportError(publicTransportMessage(error))
        }
    }

    /** One complete push then pull. Fences are exchanged before any content. */
    fun exchangeOnce(
        route: AndroidPeerRoute,
        peer: AndroidSyncPeer,
        exchange: SyncExchange,
        repository: SyncRepository,
    ): SyncRoundResult {
        requireRouteMatchesPeer(route, peer)
        return try {
            val localStatus = exchange.status(peer.pairId)
            val localIdentity = repository.identity()
            val remoteStatus = getStatus(route, peer, localIdentity.databaseId, localStatus)
            if (remoteStatus.localDatabaseId != peer.peerDatabaseId ||
                (remoteStatus.peerDatabaseId != null && remoteStatus.peerDatabaseId != localIdentity.databaseId)
            ) {
                return SyncRoundResult.Stopped("IDENTITY_MISMATCH", "电脑数据库身份已变化，请重新核验并配对")
            }
            if (remoteStatus.spaceId != localIdentity.spaceId || remoteStatus.spaceId != peer.spaceId) {
                return SyncRoundResult.Stopped("SPACE_MISMATCH", "同步空间身份不一致，请重新核验")
            }
            if (!localStatus.sendConsent || !localStatus.receiveConsent ||
                !remoteStatus.sendConsent || !remoteStatus.receiveConsent
            ) {
                return SyncRoundResult.Stopped("CONSENT_REQUIRED", "双方尚未确认双向数据合并")
            }
            performRound(route, peer, exchange, remoteStatus, localIdentity.databaseId)
        } catch (error: AndroidSyncFailure) {
            if (error.retryable) SyncRoundResult.Retryable(error.publicMessage)
            else SyncRoundResult.Stopped(error.code, error.publicMessage)
        } catch (error: SyncHttpFailure) {
            if (error.retryable) SyncRoundResult.Retryable(error.publicMessage)
            else SyncRoundResult.Stopped(error.code, error.publicMessage)
        } catch (error: app.liferecord.core.SyncProtocolException) {
            val retryable = error.code in setOf(app.liferecord.core.SyncErrorCode.INCOMPLETE_EXPORT,
                app.liferecord.core.SyncErrorCode.EXPORT_INVALIDATED, app.liferecord.core.SyncErrorCode.CURSOR_GAP)
            if (retryable) SyncRoundResult.Retryable(error.message)
            else SyncRoundResult.Stopped(error.code.name, error.message)
        } catch (error: javax.net.ssl.SSLException) {
            SyncRoundResult.Stopped("CERTIFICATE_MISMATCH", publicTransportMessage(error))
        } catch (error: CertificateException) {
            SyncRoundResult.Stopped("CERTIFICATE_MISMATCH", publicTransportMessage(error))
        } catch (error: IllegalArgumentException) {
            SyncRoundResult.Stopped("INVALID_RESPONSE", "同步身份或协议数据未通过校验，请重新核验")
        } catch (error: Exception) {
            SyncRoundResult.Retryable(publicTransportMessage(error))
        } finally {
            // Applied ACK receipts and content-free change cursors survive this staging cleanup.
            exchange.cleanup()
        }
    }

    private fun getStatus(
        route: AndroidPeerRoute,
        peer: AndroidSyncPeer,
        localDatabaseId: String,
        localStatus: SyncStatus,
    ): SyncStatus {
        remoteCounts = null
        execute(
            route, peer, "GET", listOf("sync", "v1", "status"), null, localDatabaseId,
            consent = localStatus.sendConsent to localStatus.receiveConsent,
        ).use { response ->
            requireSuccess(response)
            val document = responseObject(response)
            fun version(name: String): Int? = (document[name] as? JsonPrimitive)
                ?.takeUnless { it.isString }?.content?.toIntOrNull()
            if (version("protocol") != SYNC_PROTOCOL || version("dataSchema") != SYNC_DATA_SCHEMA) {
                remoteCounts = null
                throw AndroidSyncFailure("UNSUPPORTED_SCHEMA", "同步协议或数据版本不同，请将手机和电脑都升级到支持目标同步的版本", false)
            }
            val result = SyncWire.decodeStatus(document.toString())
            val counts = result.counts.takeIf {
                localStatus.sendConsent && localStatus.receiveConsent && result.sendConsent && result.receiveConsent
            }
            remoteCounts = counts?.let { AndroidPeerCounts(it.records, it.drafts, it.reports, it.goals, it.reviews) }
            return if (localStatus.sendConsent && localStatus.receiveConsent && result.sendConsent && result.receiveConsent) result
                else result.copy(entityCount = null, purgeCount = null, counts = null)
        }
    }

    private fun performRound(
        route: AndroidPeerRoute,
        peer: AndroidSyncPeer,
        exchange: SyncExchange,
        remote: SyncStatus,
        localDatabaseId: String,
    ): SyncRoundResult {
        val localFences = exchange.fences()
        val remoteFences = execute(
            route, peer, "POST", listOf("sync", "v1", "fences"),
            SyncWire.encodeFences(localFences), localDatabaseId,
        ).use { response -> requireSuccess(response); SyncWire.decodeFences(responseText(response)) }
        if (remoteFences.senderDatabaseId != peer.peerDatabaseId) {
            return SyncRoundResult.Stopped("IDENTITY_MISMATCH", "电脑数据库身份已变化，请重新配对")
        }
        exchange.mergeFences(peer.pairId, peer.peerDatabaseId, remoteFences)

        val manifest = exchange.prepare(
            peerId = peer.pairId,
            peerDatabaseId = peer.peerDatabaseId,
            baseSeq = remote.receivedRemoteSeq,
            full = remote.receivedRemoteSeq == 0L,
        )
        val disposition = execute(
            route, peer, "POST", listOf("sync", "v1", "push", "begin"),
            SyncWire.encodeManifest(manifest), localDatabaseId,
        ).use { response -> requireSuccess(response); SyncWire.decodeDisposition(responseText(response)) }
        val pushAck = if (disposition.state == IncomingState.APPLIED) {
            requireNotNull(disposition.ack) { "电脑缺少已应用批次的确认" }
        } else {
            repeat(manifest.partCount) { partNo ->
                val part = exchange.exportPart(manifest.exportId, partNo)
                execute(
                    route, peer, "PUT", listOf("sync", "v1", "push", manifest.exportId, "parts", partNo.toString()),
                    SyncWire.encodePart(part), localDatabaseId,
                ).use(::requireSuccess)
            }
            execute(
                route, peer, "POST", listOf("sync", "v1", "push", manifest.exportId, "commit"),
                "{}", localDatabaseId,
            ).use { response -> requireSuccess(response); SyncWire.decodeAck(responseText(response)) }
        }
        exchange.acknowledge(peer.pairId, peer.peerDatabaseId, pushAck)

        val receivedSeq = exchange.status(peer.pairId).receivedRemoteSeq
        val pullRequest = buildJsonObject {
            put("baseSeq", receivedSeq)
            put("full", receivedSeq == 0L)
        }.toString()
        val incomingManifest = execute(
            route, peer, "POST", listOf("sync", "v1", "pull", "begin"), pullRequest, localDatabaseId,
        ).use { response -> requireSuccess(response); SyncWire.decodeManifest(responseText(response)) }
        val incomingDisposition = exchange.beginIncoming(peer.pairId, incomingManifest)
        val pullAck = if (incomingDisposition.state == IncomingState.APPLIED) {
            requireNotNull(incomingDisposition.ack) { "本机缺少已应用批次的确认" }
        } else {
            repeat(incomingManifest.partCount) { partNo ->
                val part = execute(
                    route, peer, "GET", listOf("sync", "v1", "pull", incomingManifest.exportId, "parts", partNo.toString()),
                    null, localDatabaseId,
                ).use { response -> requireSuccess(response); SyncWire.decodePart(responseText(response)) }
                exchange.receivePart(incomingManifest.exportId, part)
            }
            exchange.commitIncoming(peer.pairId, incomingManifest.exportId)
        }
        execute(
            route, peer, "POST", listOf("sync", "v1", "pull", incomingManifest.exportId, "ack"),
            SyncWire.encodeAck(pullAck), localDatabaseId,
        ).use(::requireSuccess)
        return SyncRoundResult.Complete(pushAck.throughSeq, pullAck.throughSeq)
    }

    private fun execute(
        route: AndroidPeerRoute,
        peer: AndroidSyncPeer?,
        method: String,
        path: List<String>,
        body: String?,
        localDatabaseId: String? = null,
        consent: Pair<Boolean, Boolean>? = null,
    ): Response {
        AndroidLanNetworks.requireSelected(context, route.network)
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(route.stableHost)
            .port(route.port)
            .apply { path.forEach(::addPathSegment) }
            .build()
        val request = Request.Builder().url(url).apply {
            if (peer != null) {
                header("Authorization", "Bearer ${peer.bearerToken}")
                header("X-LifeRecord-Pair-Id", peer.pairId)
                header("X-LifeRecord-Database-Id", requireNotNull(localDatabaseId))
                if (path.firstOrNull() == "goal-ai") header("X-LifeRecord-Data-Schema", SYNC_DATA_SCHEMA.toString())
                consent?.let {
                    header("X-LifeRecord-Send-Consent", it.first.toString())
                    header("X-LifeRecord-Receive-Consent", it.second.toString())
                }
            }
            when (method) {
                "GET" -> get()
                "POST" -> post((body ?: "").toRequestBody(JSON_MEDIA))
                "PUT" -> put((body ?: "").toRequestBody(JSON_MEDIA))
                else -> error("unsupported method")
            }
        }.build()
        return client(route).newCall(request).execute()
    }

    private fun client(route: AndroidPeerRoute): OkHttpClient {
        val trust = PinnedSelfSignedTrust(route.certificateSha256)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .socketFactory(route.network.socketFactory)
            .dns(ExactHostDns(route.stableHost, route.address))
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private fun requireRouteMatchesPeer(route: AndroidPeerRoute, peer: AndroidSyncPeer) {
        require(route.serverId == peer.serverId && route.stableHost == peer.stableHost &&
            route.certificateSha256.equals(peer.certificateSha256, true)) {
            "连接地址与已配对身份不一致"
        }
    }

    private fun requireSuccess(response: Response) {
        if (response.isSuccessful) return
        val error = response.errorObject()
        throw SyncHttpFailure(error.first, error.second, response.code >= 500 || response.code == 408 || response.code == 429 ||
            error.first in setOf("EXPORT_INVALIDATED", "CURSOR_GAP", "INCOMPLETE_EXPORT"))
    }

    private fun Response.errorResult(): PairingResult.Rejected {
        val error = errorObject()
        return PairingResult.Rejected(error.first, error.second)
    }

    private fun Response.errorObject(): Pair<String, String> = try {
        val value = responseObject(this)
        (value.optionalString("code") ?: "HTTP_$code") to (value.optionalString("message") ?: "配对请求被拒绝")
    } catch (_: Exception) {
        "HTTP_$code" to "服务器拒绝了请求"
    }

    private fun responseObject(response: Response): JsonObject =
        JSON.parseToJsonElement(responseText(response)) as? JsonObject ?: error("服务器响应格式无效")

    private fun responseText(response: Response): String {
        val body = response.body ?: error("服务器响应为空")
        val length = body.contentLength()
        require(length == -1L || length <= MAX_RESPONSE_BYTES) { "服务器响应过大" }
        val source = body.source()
        source.request(MAX_RESPONSE_BYTES.toLong() + 1L)
        require(source.buffer.size <= MAX_RESPONSE_BYTES.toLong()) { "服务器响应过大" }
        return source.readUtf8()
    }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("响应缺少 $key")
    private fun JsonObject.optionalString(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String): Long {
        val value = get(key) as? JsonPrimitive ?: error("响应缺少 $key")
        require(!value.isString)
        return value.content.toLong()
    }
    private fun JsonObject.boolean(key: String): Boolean {
        val value = get(key) as? JsonPrimitive ?: error("响应缺少 $key")
        require(!value.isString && (value.content == "true" || value.content == "false"))
        return value.content.toBoolean()
    }

    private fun publicTransportMessage(error: Exception): String = when (error) {
        is CertificateException, is javax.net.ssl.SSLException -> "电脑证书核验失败，未发送配对码或数据"
        is java.net.SocketTimeoutException -> "连接电脑超时"
        is UnknownHostException -> "无法通过已选局域网连接电脑"
        else -> "无法安全连接电脑"
    }

    private class SyncHttpFailure(val code: String, val publicMessage: String, val retryable: Boolean) : Exception(publicMessage)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = false; isLenient = false; explicitNulls = true }
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val PAIR_CODE = Regex("[A-Z2-7]{26}")
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}

private class ExactHostDns(private val host: String, private val address: InetAddress) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.equals(host, ignoreCase = true)) throw UnknownHostException("unexpected host")
        return listOf(address)
    }
}

private class PinnedSelfSignedTrust(fingerprint: String) : X509TrustManager {
    private val expected = fingerprint.lowercase()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificate not accepted")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.singleOrNull() ?: throw CertificateException("exactly one pinned certificate required")
        if (authType.isNullOrBlank()) throw CertificateException("missing authentication type")
        certificate.checkValidity()
        if (certificate.subjectX500Principal != certificate.issuerX500Principal) throw CertificateException("certificate is not self-signed")
        try { certificate.verify(certificate.publicKey) } catch (error: Exception) {
            throw CertificateException("invalid self-signature", error)
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))) {
            throw CertificateException("certificate pin mismatch")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
