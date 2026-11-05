package app.liferecord.desktop

import app.liferecord.core.Ack
import app.liferecord.core.ExportManifest
import app.liferecord.core.SyncErrorCode
import app.liferecord.core.SyncExchange
import app.liferecord.core.SyncProtocolException
import app.liferecord.core.SyncWire
import app.liferecord.core.GoalReviewCoordinationError
import app.liferecord.core.GoalReviewCoordinationException
import app.liferecord.core.GoalReviewCoordinator
import app.liferecord.core.GoalReviewKey
import app.liferecord.core.SYNC_DATA_SCHEMA
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val MAX_JSON_BYTES = 2 * 1024 * 1024
private const val PAIR_CODE_BYTES = 16
private const val PAIR_CODE_LENGTH = 26
private const val MAX_PAIR_FAILURES = 5

data class PairingWindow(
    val code: String,
    val expiresAt: Instant,
    val attemptsRemaining: Int,
)

data class PendingPairRequest(
    val requestId: String,
    val clientId: String,
    val deviceName: String,
    val requestedAt: Instant,
)

data class WindowsSyncServerState(
    val running: Boolean = false,
    val address: String = "",
    val port: Int = 0,
    val pairing: PairingWindow? = null,
    val pendingRequest: PendingPairRequest? = null,
    val pairedDeviceName: String = "",
    val localSendConsent: Boolean = false,
    val localReceiveConsent: Boolean = false,
    val error: String? = null,
)

/** HTTPS protocol endpoint. It never binds wildcard addresses and never trusts proxy/redirect headers. */
class WindowsSyncServer(
    private val exchange: SyncExchange,
    private val secrets: WindowsSecrets,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
    private val stateChanged: (WindowsSyncServerState) -> Unit = {},
    private val coordinator: GenerationCoordinator? = null,
    private val goalCoordinator: GoalReviewCoordinator? = null,
) : AutoCloseable {
    private val lock = Any()
    private var server: HttpsServer? = null
    private var httpExecutor: ExecutorService? = null
    private var state = WindowsSyncServerState()
    private var pairing: PairingState? = null

    fun state(): WindowsSyncServerState = synchronized(lock) {
        val pair = secrets.pair()
        val local = pair?.let { exchange.status(it.pairId) }
        state.copy(
            pairedDeviceName = pair?.deviceName.orEmpty(),
            localSendConsent = local?.sendConsent ?: false,
            localReceiveConsent = local?.receiveConsent ?: false,
        )
    }

    fun start(address: PrivateLanInterface, port: Int): Int = synchronized(lock) {
        require(WindowsDiscovery.isPrivateLan(address.address)) { "只能监听明确选择的私有局域网地址" }
        require(port in 0..65535) { "同步端口无效" }
        stopLocked(clearPairing = true)
        val identity = secrets.identity()
        val created = HttpsServer.create(InetSocketAddress(address.address, port), 0)
        try {
            created.httpsConfigurator = HttpsConfigurator(tlsContext(identity))
            created.createContext("/") { request -> handle(request) }
            val executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "liferecord-sync-http").apply { isDaemon = true }
            }
            httpExecutor = executor
            created.executor = executor
            exchange.cleanup()
            created.start()
            val actualPort = created.address.port
            server = created
            state = WindowsSyncServerState(
                running = true,
                address = address.address.hostAddress,
                port = actualPort,
                pairedDeviceName = secrets.pair()?.deviceName.orEmpty(),
                localSendConsent = secrets.pair()?.let { exchange.status(it.pairId).sendConsent } ?: false,
                localReceiveConsent = secrets.pair()?.let { exchange.status(it.pairId).receiveConsent } ?: false,
            )
            publish()
            actualPort
        } catch (failure: Throwable) {
            created.stop(0)
            httpExecutor?.shutdownNow()
            httpExecutor = null
            state = WindowsSyncServerState(error = "无法在所选私有局域网接口启动 HTTPS 同步服务")
            publish()
            throw IllegalStateException(state.error, failure)
        }
    }

    fun startPairing(): PairingWindow = synchronized(lock) {
        check(server != null) { "请先选择局域网接口并启动服务" }
        val bytes = ByteArray(PAIR_CODE_BYTES).also(random::nextBytes)
        val window = PairingWindow(base32(bytes), clock.instant().plus(Duration.ofMinutes(5)), MAX_PAIR_FAILURES)
        pairing = PairingState(window)
        updateState()
        window
    }

    fun cancelPairing() = synchronized(lock) {
        pairing = null
        updateState()
    }

    fun approvePairing(requestId: String) = synchronized(lock) {
        val active = validPairing() ?: throw IllegalStateException("配对页面已关闭或配对码已过期")
        val pending = active.pending?.takeIf { it.requestId == requestId }
            ?: throw IllegalArgumentException("配对请求不存在或已变化")
        if (active.approved == null) {
            val pair = StoredPair(
                pairId = secrets.newPairId(), token = secrets.newBearerToken(), clientId = pending.clientId,
                deviceName = pending.deviceName,
                spaceId = exchange.status(pending.clientId).spaceId ?: secrets.newSpaceId(), peerDatabaseId = null,
                peerSendConsent = false, peerReceiveConsent = false, revoked = false,
            )
            exchange.bindSpace(pair.spaceId)
            secrets.savePair(pair)
            exchange.setConsent(pair.pairId, send = false, receive = false)
            active.approved = ApprovedRequest(pending.requestId, active.codeDigest, pair)
        }
        updateState()
    }

    fun rejectPairing(requestId: String) = synchronized(lock) {
        val active = validPairing() ?: return
        if (active.pending?.requestId == requestId) active.pending = null
        updateState()
    }

    fun setLocalConsent(send: Boolean, receive: Boolean) = synchronized(lock) {
        secrets.pair()?.let { exchange.setConsent(it.pairId, send, receive) }
        updateState()
    }

    fun revoke(pairId: String) = synchronized(lock) {
        secrets.revokePair(pairId)
        pairing = null
        updateState()
    }

    fun stop() = synchronized(lock) { stopLocked(clearPairing = true) }

    private fun stopLocked(clearPairing: Boolean) {
        server?.stop(1)
        server = null
        httpExecutor?.shutdownNow()
        httpExecutor = null
        runCatching { exchange.cleanup() }
        if (clearPairing) pairing = null
        state = WindowsSyncServerState(pairedDeviceName = secrets.pair()?.deviceName.orEmpty())
        publish()
    }

    private fun handle(request: HttpExchange) {
        try {
            if (request.requestHeaders.getFirst("Origin") != null) throw HttpFailure(403, "ORIGIN_NOT_ALLOWED", "不接受浏览器跨域请求")
            val path = request.requestURI.path
            if (path == "/pair/v1/request") {
                requireMethod(request, "POST")
                handlePair(request)
                return
            }
            val authenticated = authenticate(request)
            when {
                path == "/sync/v1/status" -> {
                    requireMethod(request, "GET")
                    updatePeerConsent(request, authenticated)
                    val pair = synchronized(lock) { secrets.pair()?.takeIf { it.pairId == authenticated.pairId } }
                        ?: throw HttpFailure(401, "AUTH_REQUIRED", "配对已撤销")
                    val local = exchange.status(authenticated.pairId)
                    val status = if (pair.peerSendConsent && pair.peerReceiveConsent) local
                        else local.copy(entityCount = null, purgeCount = null, counts = null)
                    respond(request, 200, SyncWire.encodeStatus(status))
                }
                path in GOAL_COORDINATION_PATHS -> {
                    requireMethod(request, "POST")
                    requireConsent(authenticated)
                    requireGoalDataSchema(request)
                    handleGoalCoordination(request, path, authenticated)
                }
                path in setOf("/ai/v1/claim","/ai/v1/heartbeat","/ai/v1/finish") -> {
                    requireMethod(request,"POST"); requireConsent(authenticated)
                    val service=coordinator ?: throw HttpFailure(503,"COORDINATION_UNAVAILABLE","生成协调未启动")
                    val input=objectBody(request)
                    val date=input.required("date",10)
                    val run=input.required("runId",128)
                    val lease=try {
                        when(path.substringAfterLast('/')) {
                            "claim" -> service.claim(date,run,authenticated.databaseId,input.required("inputHash",256),input["takeover"]?.jsonPrimitive?.booleanOrNull ?: false)
                            "heartbeat" -> service.heartbeat(date,run,input.required("token",128))
                            else -> service.finish(date,run,input.required("token",128))
                        }
                    } catch(error:IllegalStateException) {
                        val state=service.current(date)?.state
                        throw HttpFailure(409,if(state=="UNKNOWN")"GENERATION_UNKNOWN" else "GENERATION_RUNNING","另一端生成状态需要核对，未自动重复调用")
                    }
                    respond(request,200,buildJsonObject {
                        put("date",lease.date);put("runId",lease.runId);put("inputHash",lease.inputHash)
                        put("state",lease.state);put("token",lease.token);put("holder",lease.holder);put("expiresAt",lease.expiresAt)
                    }.toString())
                }
                path == "/sync/v1/fences" -> {
                    requireMethod(request, "POST"); requireConsent(authenticated)
                    exchange.mergeFences(authenticated.pairId, authenticated.databaseId, SyncWire.decodeFences(body(request)))
                    respond(request, 200, SyncWire.encodeFences(exchange.fences()))
                }
                path == "/sync/v1/push/begin" -> {
                    requireMethod(request, "POST"); requireConsent(authenticated)
                    val manifest = SyncWire.decodeManifest(body(request))
                    if (manifest.senderDatabaseId != authenticated.databaseId) {
                        throw SyncProtocolException(SyncErrorCode.IDENTITY_MISMATCH, "manifest发送方与认证数据库身份不一致")
                    }
                    val disposition = exchange.beginIncoming(authenticated.pairId, manifest)
                    respond(request, 200, SyncWire.encodeDisposition(disposition))
                }
                PUSH_PART.matches(path) -> {
                    requireMethod(request, "PUT"); requireConsent(authenticated)
                    val match = PUSH_PART.matchEntire(path)!!
                    val part = SyncWire.decodePart(body(request))
                    require(part.exportId == match.groupValues[1] && part.partNo == match.groupValues[2].toInt()) { "part路径与内容不一致" }
                    exchange.receivePart(part.exportId, part)
                    respond(request, 204, null)
                }
                PUSH_COMMIT.matches(path) -> {
                    requireMethod(request, "POST"); requireConsent(authenticated)
                    val id = PUSH_COMMIT.matchEntire(path)!!.groupValues[1]
                    respond(request, 200, SyncWire.encodeAck(exchange.commitIncoming(authenticated.pairId, id)))
                }
                path == "/sync/v1/pull/begin" -> {
                    requireMethod(request, "POST"); requireConsent(authenticated)
                    val root = objectBody(request)
                    val baseSeq = root["baseSeq"]?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("baseSeq无效")
                    val full = root["full"]?.jsonPrimitive?.booleanOrNull ?: false
                    val manifest = exchange.prepare(authenticated.pairId, authenticated.databaseId, baseSeq, full)
                    respond(request, 200, SyncWire.encodeManifest(manifest))
                }
                PULL_PART.matches(path) -> {
                    requireMethod(request, "GET"); requireConsent(authenticated)
                    val match = PULL_PART.matchEntire(path)!!
                    val part = exchange.exportPart(match.groupValues[1], match.groupValues[2].toInt())
                    respond(request, 200, SyncWire.encodePart(part))
                }
                PULL_ACK.matches(path) -> {
                    requireMethod(request, "POST"); requireConsent(authenticated)
                    val id = PULL_ACK.matchEntire(path)!!.groupValues[1]
                    val ack = SyncWire.decodeAck(body(request))
                    require(ack.exportId == id) { "ACK路径与内容不一致" }
                    exchange.acknowledge(authenticated.pairId, authenticated.databaseId, ack)
                    respond(request, 204, null)
                }
                else -> throw HttpFailure(404, "NOT_FOUND", "接口不存在")
            }
        } catch (failure: SyncProtocolException) {
            respondError(request, protocolStatus(failure.code), failure.code.name, failure.message)
        } catch (failure: HttpFailure) {
            respondError(request, failure.status, failure.code, failure.message ?: "请求失败")
        } catch (failure: IllegalArgumentException) {
            respondError(request, 400, "INVALID_REQUEST", failure.message ?: "请求无效")
        } catch (_: Throwable) {
            respondError(request, 500, "INTERNAL_ERROR", "同步服务处理失败，未提交部分数据")
        } finally {
            request.close()
        }
    }

    private fun handleGoalCoordination(request: HttpExchange, path: String, authenticated: AuthenticatedPeer) {
        val service = goalCoordinator
            ?: throw HttpFailure(503, "COORDINATION_UNAVAILABLE", "目标回顾生成协调未启动")
        val input = objectBody(request)
        val operation = path.substringAfterLast('/')
        val expectedKeys = when (operation) {
            "status" -> GOAL_STATUS_FIELDS
            "claim" -> GOAL_CLAIM_FIELDS
            "heartbeat", "finish" -> GOAL_LEASE_FIELDS
            else -> throw HttpFailure(404, "NOT_FOUND", "接口不存在")
        }
        input.requireExactKeys(expectedKeys)
        val key = GoalReviewKey(
            input.required("goalId", 128),
            input.required("fromDate", 10),
            input.required("throughDate", 10),
        )
        if (operation == "status") {
            val lease = service.current(key)
            respond(request, 200, buildJsonObject {
                put("goalId", key.goalId)
                put("fromDate", key.fromDate)
                put("throughDate", key.throughDate)
                put("state", lease?.state ?: "IDLE")
                put("runId", lease?.runId?.let(::JsonPrimitive) ?: JsonNull)
                put("holder", lease?.holder?.let(::JsonPrimitive) ?: JsonNull)
                put("inputHash", lease?.inputHash?.let(::JsonPrimitive) ?: JsonNull)
                put("expiresAt", lease?.expiresAt?.let(::JsonPrimitive) ?: JsonNull)
            }.toString())
            return
        }

        val runId = input.required("runId", 128)
        val lease = try {
            when (operation) {
                "claim" -> {
                    val takeover = input["takeover"]?.jsonPrimitive?.booleanOrNull
                        ?: throw IllegalArgumentException("takeover格式无效")
                    service.claim(
                        key,
                        runId,
                        authenticated.databaseId,
                        input.required("inputHash", 256),
                        takeover,
                    )
                }
                "heartbeat" -> {
                    requireGoalLeaseHolder(service, key, authenticated.databaseId)
                    service.heartbeat(key, runId, input.required("token", 128))
                }
                else -> {
                    requireGoalLeaseHolder(service, key, authenticated.databaseId)
                    service.finish(key, runId, input.required("token", 128))
                }
            }
        } catch (failure: GoalReviewCoordinationException) {
            val code = when (failure.error) {
                GoalReviewCoordinationError.INPUT_MISMATCH -> "GOAL_INPUT_MISMATCH"
                GoalReviewCoordinationError.GENERATION_RUNNING -> "GOAL_GENERATION_RUNNING"
                GoalReviewCoordinationError.GENERATION_UNKNOWN -> "GOAL_GENERATION_UNKNOWN"
                GoalReviewCoordinationError.LEASE_INVALID -> "GOAL_LEASE_INVALID"
            }
            throw HttpFailure(409, code, failure.message ?: "目标回顾生成协调失败")
        }
        respond(request, 200, buildJsonObject {
            put("goalId", lease.key.goalId)
            put("fromDate", lease.key.fromDate)
            put("throughDate", lease.key.throughDate)
            put("runId", lease.runId)
            put("inputHash", lease.inputHash)
            put("holder", lease.holder)
            put("token", lease.token)
            put("state", lease.state)
            put("expiresAt", lease.expiresAt)
        }.toString())
    }

    private fun requireGoalLeaseHolder(service: GoalReviewCoordinator, key: GoalReviewKey, holder: String) {
        val current = service.current(key)
        if (current == null || !constantEquals(current.holder, holder)) {
            throw HttpFailure(409, "GOAL_LEASE_INVALID", "目标回顾生成租约不属于当前认证设备")
        }
    }

    private fun requireGoalDataSchema(request: HttpExchange) {
        val actual = request.requestHeaders.getFirst("X-LifeRecord-Data-Schema")?.toIntOrNull()
        if (actual != SYNC_DATA_SCHEMA) {
            throw SyncProtocolException(SyncErrorCode.UNSUPPORTED_SCHEMA, "目标回顾协调要求相同的数据schema")
        }
    }

    private fun handlePair(request: HttpExchange) {
        val root = objectBody(request)
        val requestId = root.required("requestId", 128)
        val clientId = root.required("clientId", 128)
        val deviceName = root.required("deviceName", 128)
        val code = root.required("code", PAIR_CODE_LENGTH)
        val result = synchronized(lock) {
            val active = validPairing() ?: throw HttpFailure(410, "PAIRING_EXPIRED", "配对页面已关闭或配对码已过期")
            val digest = digest(code)
            val approved = active.approved
            if (approved != null && approved.requestId == requestId && MessageDigest.isEqual(approved.codeDigest, digest)) {
                if (approved.pair.clientId != clientId || approved.pair.deviceName != deviceName) {
                    throw HttpFailure(409, "PAIRING_BUSY", "配对重试的设备身份与已确认请求不一致")
                }
                return@synchronized pairResponse(approved.pair)
            }
            if (!MessageDigest.isEqual(active.codeDigest, digest)) {
                active.failures++
                if (active.failures >= MAX_PAIR_FAILURES) pairing = null
                updateState()
                throw HttpFailure(401, "PAIRING_CODE_INVALID", "配对码无效或已失效")
            }
            val existing = active.pending
            if (existing != null && (existing.requestId != requestId || existing.clientId != clientId || existing.deviceName != deviceName)) {
                throw HttpFailure(409, "PAIRING_BUSY", "已有配对请求等待人工确认")
            }
            if (existing == null) active.pending = PendingPairRequest(requestId, clientId, deviceName, clock.instant())
            updateState()
            null
        }
        if (result == null) respond(request, 202, buildJsonObject { put("state", "PENDING") }.toString())
        else respond(request, 200, result)
    }

    private fun pairResponse(pair: StoredPair): String {
        val identity = secrets.identity()
        val status = exchange.status(pair.pairId)
        return buildJsonObject {
            put("state", "APPROVED"); put("pairId", pair.pairId); put("token", pair.token)
            put("serverId", identity.serverId); put("stableHostName", identity.stableHostName)
            put("serverDatabaseId", status.localDatabaseId); put("spaceId", pair.spaceId)
        }.toString()
    }

    private fun authenticate(request: HttpExchange): AuthenticatedPeer = synchronized(lock) {
        val pair = secrets.pair() ?: throw HttpFailure(401, "AUTH_REQUIRED", "尚未配对或配对已撤销")
        val pairId = request.requestHeaders.getFirst("X-LifeRecord-Pair-Id").orEmpty()
        val authorization = request.requestHeaders.getFirst("Authorization").orEmpty()
        val databaseId = request.requestHeaders.getFirst("X-LifeRecord-Database-Id").orEmpty()
        if (!constantEquals(pair.pairId, pairId) || !authorization.startsWith("Bearer ") || !constantEquals(pair.token, authorization.removePrefix("Bearer "))) {
            throw HttpFailure(401, "AUTH_INVALID", "配对凭据无效或已撤销")
        }
        require(databaseId.isNotBlank() && databaseId.length <= 128 && databaseId.none { it <= ' ' }) { "数据库身份无效" }
        if (pair.peerDatabaseId != null && !constantEquals(pair.peerDatabaseId, databaseId)) {
            throw HttpFailure(409, "IDENTITY_MISMATCH", "对端数据库身份已变化，必须重新配对")
        }
        if (pair.peerDatabaseId == null) secrets.savePair(pair.copy(peerDatabaseId = databaseId))
        AuthenticatedPeer(pair.pairId, databaseId)
    }

    private fun updatePeerConsent(request: HttpExchange, peer: AuthenticatedPeer) {
        val sendHeader = request.requestHeaders.getFirst("X-LifeRecord-Send-Consent")
        val receiveHeader = request.requestHeaders.getFirst("X-LifeRecord-Receive-Consent")
        if (sendHeader == null && receiveHeader == null) return
        val send = sendHeader?.toBooleanStrictOrNull() ?: throw HttpFailure(400, "INVALID_CONSENT", "发送同意格式无效")
        val receive = receiveHeader?.toBooleanStrictOrNull() ?: throw HttpFailure(400, "INVALID_CONSENT", "接收同意格式无效")
        synchronized(lock) {
            val pair = secrets.pair()?.takeIf { it.pairId == peer.pairId } ?: return
            if (pair.peerSendConsent != send || pair.peerReceiveConsent != receive) secrets.savePair(pair.copy(peerSendConsent = send, peerReceiveConsent = receive))
        }
    }

    private fun requireConsent(peer: AuthenticatedPeer) = synchronized(lock) {
        val pair = secrets.pair()?.takeIf { it.pairId == peer.pairId } ?: throw HttpFailure(401, "AUTH_REQUIRED", "配对已撤销")
        val local = exchange.status(peer.pairId)
        if (!local.sendConsent || !local.receiveConsent || !pair.peerSendConsent || !pair.peerReceiveConsent) {
            throw SyncProtocolException(SyncErrorCode.CONSENT_REQUIRED, "双方尚未分别确认发送和接收数据")
        }
    }

    private fun validPairing(): PairingState? {
        val active = pairing ?: return null
        if (!clock.instant().isBefore(active.window.expiresAt) || active.failures >= MAX_PAIR_FAILURES) {
            pairing = null
            updateState()
            return null
        }
        return active
    }

    private fun updateState() {
        val active = validPairingWithoutRecursion()
        val pair = secrets.pair()
        val local = pair?.let { exchange.status(it.pairId) }
        state = state.copy(
            pairing = active?.window?.copy(attemptsRemaining = MAX_PAIR_FAILURES - active.failures),
            pendingRequest = active?.pending,
            pairedDeviceName = pair?.deviceName.orEmpty(),
            localSendConsent = local?.sendConsent ?: false,
            localReceiveConsent = local?.receiveConsent ?: false,
        )
        publish()
    }

    private fun validPairingWithoutRecursion(): PairingState? {
        val active = pairing ?: return null
        if (!clock.instant().isBefore(active.window.expiresAt) || active.failures >= MAX_PAIR_FAILURES) pairing = null
        return pairing
    }

    private fun publish() = stateChanged(state)

    private fun body(request: HttpExchange): String {
        val declared = request.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > MAX_JSON_BYTES) throw HttpFailure(413, "REQUEST_TOO_LARGE", "请求内容过大")
        val bytes = request.requestBody.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_JSON_BYTES) throw HttpFailure(413, "REQUEST_TOO_LARGE", "请求内容过大")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun objectBody(request: HttpExchange): JsonObject = try {
        SyncWire.json.parseToJsonElement(body(request)) as JsonObject
    } catch (failure: Throwable) {
        if (failure is HttpFailure) throw failure
        throw IllegalArgumentException("JSON请求格式无效")
    }

    private fun respond(request: HttpExchange, status: Int, text: String?) {
        request.responseHeaders.set("Cache-Control", "no-store")
        request.responseHeaders.set("X-Content-Type-Options", "nosniff")
        if (text == null) {
            request.sendResponseHeaders(status, -1)
        } else {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            request.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            request.sendResponseHeaders(status, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
    }

    private fun respondError(request: HttpExchange, status: Int, code: String, message: String) = runCatching {
        respond(request, status, buildJsonObject { put("code", code); put("message", message) }.toString())
    }

    private fun tlsContext(identity: WindowsServerIdentity): SSLContext {
        val password = CharArray(24) { ('a'.code + random.nextInt(26)).toChar() }
        return try {
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, null)
            store.setKeyEntry("liferecord", identity.certificate.keyPair.private, password, arrayOf(identity.certificate.certificate))
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            managers.init(store, password)
            SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, random) }
        } finally {
            password.fill('\u0000')
        }
    }

    override fun close() {
        stop()
    }

    private data class AuthenticatedPeer(val pairId: String, val databaseId: String)
    private data class PairingState(
        val window: PairingWindow,
        val codeDigest: ByteArray = digest(window.code),
        var failures: Int = 0,
        var pending: PendingPairRequest? = null,
        var approved: ApprovedRequest? = null,
    )
    private data class ApprovedRequest(val requestId: String, val codeDigest: ByteArray, val pair: StoredPair)
    private class HttpFailure(val status: Int, val code: String, message: String) : RuntimeException(message)

    companion object {
        private val PUSH_PART = Regex("^/sync/v1/push/([A-Za-z0-9._-]{1,128})/parts/(\\d{1,9})$")
        private val PUSH_COMMIT = Regex("^/sync/v1/push/([A-Za-z0-9._-]{1,128})/commit$")
        private val PULL_PART = Regex("^/sync/v1/pull/([A-Za-z0-9._-]{1,128})/parts/(\\d{1,9})$")
        private val PULL_ACK = Regex("^/sync/v1/pull/([A-Za-z0-9._-]{1,128})/ack$")
        private val GOAL_COORDINATION_PATHS = setOf(
            "/goal-ai/v1/status", "/goal-ai/v1/claim", "/goal-ai/v1/heartbeat", "/goal-ai/v1/finish",
        )
        private val GOAL_STATUS_FIELDS = setOf("goalId", "fromDate", "throughDate")
        private val GOAL_CLAIM_FIELDS = GOAL_STATUS_FIELDS + setOf("runId", "inputHash", "takeover")
        private val GOAL_LEASE_FIELDS = GOAL_STATUS_FIELDS + setOf("runId", "token")
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private fun requireMethod(request: HttpExchange, method: String) {
            if (request.requestMethod != method) throw HttpFailure(405, "METHOD_NOT_ALLOWED", "请求方法不支持")
        }

        private fun JsonObject.required(name: String, maximum: Int): String {
            val value = get(name)?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("缺少$name")
            require(value.isNotBlank() && value.length <= maximum && value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "$name 格式无效" }
            return value
        }

        private fun JsonObject.requireExactKeys(expected: Set<String>) {
            require(keys == expected) { "JSON字段必须严格为${expected.sorted().joinToString(",")}" }
        }

        private fun constantEquals(first: String, second: String): Boolean = MessageDigest.isEqual(
            first.toByteArray(StandardCharsets.UTF_8), second.toByteArray(StandardCharsets.UTF_8),
        )

        private fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.US_ASCII))

        private fun base32(bytes: ByteArray): String {
            val result = StringBuilder(PAIR_CODE_LENGTH)
            var buffer = 0
            var bits = 0
            bytes.forEach { byte ->
                buffer = (buffer shl 8) or (byte.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    result.append(BASE32[(buffer shr bits) and 31])
                }
            }
            if (bits > 0) result.append(BASE32[(buffer shl (5 - bits)) and 31])
            return result.toString()
        }

        private fun protocolStatus(code: SyncErrorCode): Int = when (code) {
            SyncErrorCode.CONSENT_REQUIRED -> 403
            SyncErrorCode.NOT_FOUND -> 404
            SyncErrorCode.CURSOR_GAP, SyncErrorCode.IDENTITY_MISMATCH, SyncErrorCode.SPACE_MISMATCH,
            SyncErrorCode.CONTENT_MISMATCH, SyncErrorCode.INCOMPLETE_EXPORT, SyncErrorCode.EXPORT_INVALIDATED -> 409
            SyncErrorCode.UNSUPPORTED_PROTOCOL, SyncErrorCode.UNSUPPORTED_SCHEMA -> 422
        }
    }
}
