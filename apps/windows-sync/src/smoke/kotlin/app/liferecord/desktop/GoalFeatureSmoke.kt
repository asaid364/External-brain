package app.liferecord.desktop

import app.liferecord.core.*
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.CertificatePinner
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates

/** Host HTTPS acceptance, not an Android or physical-LAN claim. Only UUID SQLite/DPAPI fixtures. */
fun main(args: Array<String>) = runBlocking {
    require(System.getProperty("os.name").startsWith("Windows")) { "Current-user Windows DPAPI required" }
    val selection = args.singleOrNull()?.takeIf { it.isNotBlank() }
        ?: error("Select a newly enumerated private interface with -PgoalSmokeInterface=<interface-id|private-IPv4>")
    val fixture = UUID.randomUUID().toString()
    val root = Files.createTempDirectory("goal-feature-$fixture-")
    val clock = Clock.fixed(Instant.parse("2027-01-22T04:00:00Z"), ZoneOffset.UTC)
    val config = AiConfiguration("https://synthetic.invalid/v1", "synthetic-not-a-real-key", "controlled-smoke", false, false)
    val sends = AtomicInteger()
    var result: GeneratedGoalReview? = null
    val transport = ChatTransport {
        sends.incrementAndGet()
        ChatResponse(200, emptyMap(), buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", "stop")
                put("message", buildJsonObject { put("content", GoalCodec.encodeResult(requireNotNull(result)).toString()) })
            }) })
        }.toString())
    }
    val serverPath = root.resolve("server.db")
    val clientPath = root.resolve("client.db")
    val secrets = WindowsSecrets(root.resolve("server-secrets")) // Real current-user DPAPI, never production secrets.
    val identity = secrets.identity()
    check(WindowsSecrets(root.resolve("server-secrets")).identity().fingerprintSha256 == identity.fingerprintSha256)
    val discovery = WindowsDiscovery(identity)
    val selected = try {
        discovery.availableInterfaces().singleOrNull { it.id == selection || it.address.hostAddress == selection }
            ?: error("Selected interface is unavailable or ambiguous; no fallback bind is permitted")
    } finally { discovery.close() }
    var goalId = ""
    lateinit var key: GoalReviewKey
    var recordId = ""
    var pairId = ""
    var token = ""
    var port = 0
    val clientId = "goal-smoke-$fixture"
    lateinit var remoteDatabase: String
    val tls = HandshakeCertificates.Builder().addTrustedCertificate(identity.certificate.certificate).build()
    val http = OkHttpClient.Builder()
        .sslSocketFactory(tls.sslSocketFactory(), tls.trustManager)
        .certificatePinner(CertificatePinner.Builder().add(identity.stableHostName, CertificatePinner.pin(identity.certificate.certificate)).build())
        .dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                if (hostname == identity.stableHostName) listOf(selected.address) else error("Unexpected host")
        })
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    fun request(path: String, method: String = "POST", body: String = "{}", expected: Int = 200, authenticated: Boolean = true): String {
        val builder = Request.Builder().url("https://${identity.stableHostName}:$port$path")
        if (authenticated) builder.header("Authorization", "Bearer $token")
            .header("X-LifeRecord-Pair-Id", pairId).header("X-LifeRecord-Database-Id", remoteDatabase)
            .header("X-LifeRecord-Data-Schema", SYNC_DATA_SCHEMA.toString())
            .header("X-LifeRecord-Send-Consent", "true").header("X-LifeRecord-Receive-Consent", "true")
        if (method == "GET") builder.get() else builder.method(method, body.toRequestBody("application/json".toMediaType()))
        return http.newCall(builder.build()).execute().use { response ->
            check(response.code == expected) { "HTTPS $path expected=$expected actual=${response.code}; body redacted" }
            response.body?.string().orEmpty()
        }
    }
    fun keyBody(extra: JsonObject = buildJsonObject {}): String = JsonObject(buildJsonObject {
        put("goalId", key.goalId); put("fromDate", key.fromDate); put("throughDate", key.throughDate)
    } + extra).toString()
    fun push(client: SyncExchange, serverDatabaseId: String, full: Boolean = false) {
        val base = client.status(pairId).ackedLocalSeq
        val manifest = client.prepare(pairId, serverDatabaseId, base, full)
        request("/sync/v1/push/begin", body = SyncWire.encodeManifest(manifest))
        repeat(manifest.partCount) { n -> request("/sync/v1/push/${manifest.exportId}/parts/$n", "PUT", SyncWire.encodePart(client.exportPart(manifest.exportId, n)), 204) }
        val ack = SyncWire.decodeAck(request("/sync/v1/push/${manifest.exportId}/commit"))
        // Deliberately omit ACK locally once, retry commit: durable receiver receipt must be identical.
        check(SyncWire.decodeAck(request("/sync/v1/push/${manifest.exportId}/commit")) == ack)
        client.acknowledge(pairId, serverDatabaseId, ack)
        check(client.status(pairId).ackedLocalSeq == manifest.throughSeq)
    }
    fun pull(client: SyncExchange, full: Boolean = false) {
        val manifest = SyncWire.decodeManifest(request("/sync/v1/pull/begin", body = buildJsonObject {
            put("baseSeq", client.status(pairId).receivedRemoteSeq); put("full", full)
        }.toString()))
        client.beginIncoming(pairId, manifest)
        repeat(manifest.partCount) { n -> client.receivePart(manifest.exportId,
            SyncWire.decodePart(request("/sync/v1/pull/${manifest.exportId}/parts/$n", "GET"))) }
        val ack = client.commitIncoming(pairId, manifest.exportId)
        request("/sync/v1/pull/${manifest.exportId}/ack", body = SyncWire.encodeAck(ack), expected = 204)
        check(client.status(pairId).receivedRemoteSeq == manifest.throughSeq)
    }
    try {
        WindowsDatabase(serverPath).use { serverDb -> WindowsDatabase(clientPath).use { clientDb ->
            val goals = GoalRepository(serverDb, clock)
            val exchange = SyncExchange(serverDb)
            val peer = SyncExchange(clientDb)
            remoteDatabase = SyncRepository(clientDb).identity().databaseId
            val coordinator = GoalReviewCoordinator(serverDb, goals, clock)
            WindowsSyncServer(exchange, secrets, goalCoordinator = coordinator).use { server ->
                port = server.start(selected, 0)
                val pairing = server.startPairing()
                val requestId = UUID.randomUUID().toString()
                val pairBody = buildJsonObject {
                    put("requestId", requestId); put("clientId", clientId); put("deviceName", "SYNTH goal host client"); put("code", pairing.code)
                }.toString()
                request("/pair/v1/request", body = pairBody, expected = 202, authenticated = false)
                server.approvePairing(requestId)
                val approved = SyncWire.json.parseToJsonElement(request("/pair/v1/request", body = pairBody, authenticated = false)).jsonObject
                pairId = approved.getValue("pairId").jsonPrimitive.content
                token = approved.getValue("token").jsonPrimitive.content
                peer.bindSpace(approved.getValue("spaceId").jsonPrimitive.content)
                peer.setConsent(pairId, true, true)
                val hidden = SyncWire.decodeStatus(request("/sync/v1/status", "GET"))
                check(hidden.counts == null && hidden.entityCount == null && hidden.purgeCount == null)
                server.setLocalConsent(true, true)
                check(SyncWire.decodeStatus(request("/sync/v1/status", "GET")).counts != null)
                val edit = goals.openEditor()
                goalId = edit.goalId
                val spec = GoalSpec(goalId, "SYNTH-GOAL-$fixture", "交付合成小工具", "2027-01-01", "2027-01-21", "UTC", 7,
                    listOf(GoalMilestone(UUID.randomUUID().toString(), "交付可运行原型"), GoalMilestone(UUID.randomUUID().toString(), "用独立数据验证结果")), false)
                goals.save(edit, spec, UUID.randomUUID().toString())
                val store = RecordStore(serverDb, clock)
                fun record(text: String, date: String): String {
                    val editor = store.beginNew()
                    return store.save(editor, editor.draft.copy(text = text, local = "$date 09:00", zone = "UTC", offset = "Z"), UUID.randomUUID().toString())
                }
                recordId = record("合成：可运行原型已交付", "2027-01-15")
                record("合成：独立验证被测试环境阻塞", "2027-01-17")
                key = GoalReviewKey(goalId, "2027-01-15", "2027-01-21")
                val ref = goals.snapshot(key).evidence.first { it.dependency.ref.entityKey == recordId }.dependency.ref.id
                result = GeneratedGoalReview(GoalDirection.ADVANCING, GoalEvidenceClaim("模拟AI：原型已交付", listOf(ref)), emptyList(),
                    listOf(GoalEvidenceClaim("模拟AI：已有交付成果", listOf(ref))), emptyList(),
                    listOf(GoalEvidenceClaim("模拟AI：交付了可运行原型，这是具体推进", listOf(ref))),
                    listOf(GoalMilestoneSuggestion(spec.milestones.first().id, "模拟AI：请核对后确认", listOf(ref))),
                    listOf(GoalEvidenceClaim("模拟AI：可考虑核对独立验证环境", listOf(ref))), listOf("其余日期行动未知"))
                goals.grantAnalysis(goalId, GoalCodec.specHash(spec), config.endpoint, config.model, true, false)
                val engine = GoalAnalysisEngine(goals, { config }, GoalAnalysisClient(clock, transport), clock)
                engine.request(key); withTimeout(15_000) { engine.awaitCompletion(key) }
                check(goals.run(key)?.state == GoalRunState.SUCCEEDED && sends.get() == 1)
                check(goals.detail(goalId).summary.completed == 0)
                pull(peer, true)
                val clientGoals = GoalRepository(clientDb, clock)
                check(clientGoals.detail(goalId).reviews.single().assessment.text.startsWith("模拟AI"))
                clientGoals.confirmMilestone(goalId, spec.milestones.first().id, GoalCodec.criterionHash(spec.milestones.first().criterion),
                    true, "合成用户声明", emptyList(), emptySet(), UUID.randomUUID().toString())
                push(peer, SyncRepository(serverDb).identity().databaseId, true)
                check(goals.detail(goalId).summary.completed == 1 && goals.detail(goalId).summary.total == 2)
                val status = SyncWire.json.parseToJsonElement(request("/goal-ai/v1/status", body = keyBody())).jsonObject
                check(status["token"] == null && status.getValue("state").jsonPrimitive.content == "IDLE")
                val run = UUID.randomUUID().toString()
                val hash = goals.snapshot(key).hash
                val claim = keyBody(buildJsonObject { put("runId", run); put("inputHash", hash); put("takeover", false) })
                val lease = SyncWire.json.parseToJsonElement(request("/goal-ai/v1/claim", body = claim)).jsonObject
                check(lease.getValue("holder").jsonPrimitive.content == remoteDatabase)
                val leaseBody = keyBody(buildJsonObject { put("runId", run); put("token", lease.getValue("token")) })
                request("/goal-ai/v1/heartbeat", body = leaseBody)
                request("/goal-ai/v1/finish", body = leaseBody)
                // Persist a RUNNING lease; reopening below must expose UNKNOWN, never automatic takeover.
                request("/goal-ai/v1/claim", body = keyBody(buildJsonObject {
                    put("runId", UUID.randomUUID().toString()); put("inputHash", hash); put("takeover", false)
                }))
                // A confirmed milestone is no longer eligible for an AI suggestion.
                result = requireNotNull(result).copy(milestoneSuggestions = emptyList())
                serverDb.exec("CREATE TRIGGER synthetic_goal_fail BEFORE INSERT ON sync_heads WHEN NEW.kind='goal-review' BEGIN SELECT RAISE(ABORT,'synthetic local publication failure'); END")
                engine.request(key, force = true); withTimeout(15_000) { engine.awaitCompletion(key) }
                check(goals.run(key)?.state == GoalRunState.RESULT_READY && sends.get() == 2)
                serverDb.exec("DROP TRIGGER synthetic_goal_fail")
            }
        } }
        WindowsDatabase(serverPath).use { serverDb -> WindowsDatabase(clientPath).use { clientDb ->
            val goals = GoalRepository(serverDb, clock)
            check(goals.detail(goalId).summary.completed == 1)
            val engine = GoalAnalysisEngine(goals, { error("Save-only recovery must not read credentials") },
                object : GoalAnalysisAi {
                    override suspend fun generate(configuration: AiConfiguration, input: GoalAnalysisInput, beforeSend: suspend () -> Unit): GeneratedGoalReview = error("Save-only recovery must not send")
                }, clock)
            engine.recover()
            check(goals.run(key)?.state == GoalRunState.SUCCEEDED && sends.get() == 2)
            val coordinator = GoalReviewCoordinator(serverDb, goals, clock)
            check(coordinator.current(key)?.state == "UNKNOWN")
            WindowsSyncServer(SyncExchange(serverDb), WindowsSecrets(root.resolve("server-secrets")), goalCoordinator = coordinator).use { server ->
                port = server.start(selected, 0)
                check(server.state().localSendConsent && server.state().localReceiveConsent)
                check(SyncWire.decodeStatus(request("/sync/v1/status", "GET")).ackedLocalSeq > 0)
                val unknown = SyncWire.json.parseToJsonElement(request("/goal-ai/v1/status", body = keyBody())).jsonObject
                check(unknown.getValue("state").jsonPrimitive.content == "UNKNOWN")
                val oldLease = requireNotNull(coordinator.current(key))
                val replacementRun = UUID.randomUUID().toString()
                val retryClaim = keyBody(buildJsonObject {
                    put("runId", replacementRun); put("inputHash", goals.snapshot(key).hash); put("takeover", false)
                })
                request("/goal-ai/v1/claim", body = retryClaim, expected = 409)
                val replacement = SyncWire.json.parseToJsonElement(request("/goal-ai/v1/claim", body = keyBody(buildJsonObject {
                    put("runId", replacementRun); put("inputHash", goals.snapshot(key).hash); put("takeover", true)
                }))).jsonObject
                request("/goal-ai/v1/finish", body = keyBody(buildJsonObject {
                    put("runId", oldLease.runId); put("token", oldLease.token)
                }), expected = 409)
                request("/goal-ai/v1/finish", body = keyBody(buildJsonObject {
                    put("runId", replacementRun); put("token", replacement.getValue("token"))
                }))
                val interrupted = GoalAnalysisEngine(goals, { config }, GoalAnalysisClient(clock, ChatTransport {
                    sends.incrementAndGet()
                    throw java.io.IOException("synthetic ambiguous connection loss after send")
                }), clock)
                interrupted.request(key, force = true)
                withTimeout(15_000) { interrupted.awaitCompletion(key) }
                check(goals.run(key)?.state == GoalRunState.UNKNOWN && sends.get() == 3)
                engine.recover()
                check(goals.run(key)?.state == GoalRunState.UNKNOWN && sends.get() == 3)
                check(goals.detail(goalId).reviews.size == 1)
                val peer = SyncExchange(clientDb)
                pull(peer)
                val sync = SyncRepository(serverDb)
                val store = RecordStore(serverDb, clock)
                store.moveToTrash(recordId, sync.headIds("record", recordId), UUID.randomUUID().toString())
                store.purge(recordId, UUID.randomUUID().toString())
                val fences = SyncWire.decodeFences(request("/sync/v1/fences", body = SyncWire.encodeFences(peer.fences())))
                peer.mergeFences(pairId, SyncRepository(serverDb).identity().databaseId, fences)
                pull(peer, true)
                val copied = GoalRepository(clientDb, clock).detail(goalId)
                check(copied.reviews.size == 1 && copied.summary.completed == 1)
                check(SyncRepository(clientDb).isPurged(recordId))
                val spec = requireNotNull(copied.summary.spec)
                val milestone = spec.milestones.first()
                GoalRepository(clientDb, clock).confirmMilestone(goalId, milestone.id, GoalCodec.criterionHash(milestone.criterion), false,
                    "合成：用户撤回", emptyList(), SyncRepository(clientDb).headIds("goal-confirmation", "$goalId/${milestone.id}"), UUID.randomUUID().toString())
                push(peer, sync.identity().databaseId)
                check(goals.detail(goalId).summary.completed == 0)
            }
        } }
        println("GOAL_FEATURE_SMOKE: PASS fixture=$fixture goal=$goalId controlledAIRequests=${sends.get()}; real selected-interface HTTPS/default hostname/pin/current-user DPAPI; bidirectional full+incremental parts/ACK/restart; progress 0/2 -> 1/2 -> 0/2; durable save-only recovery, lease restart UNKNOWN, source purge retains derived review. Host HTTPS only; external AI/Android/physical LAN not exercised")
    } finally {
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdownNow()
        println("GOAL_FEATURE_SMOKE evidence=$root")
    }
}
