package app.liferecord

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.liferecord.core.EditSession
import app.liferecord.core.EntityState
import app.liferecord.core.EventTime
import app.liferecord.core.Record
import app.liferecord.core.RecordTime
import app.liferecord.core.SqlDatabase
import app.liferecord.core.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.time.Instant
import java.util.Base64
import java.util.UUID

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = AppRuntime.get(application)
    private val storage = AndroidSyncStorage(application)
    private val client = AndroidSyncClient(application)

    var state by mutableStateOf(AndroidSyncUiState())
        private set
    var conflictsOpen by mutableStateOf(false)
        private set
    var conflictState by mutableStateOf(ConflictScreenUiState())
        private set

    private var hints = emptyList<AndroidDiscoveredPeer>()
    private var displayed = emptyList<EntityState>()
    private var endedDraftIds = emptySet<String>()
    private val conflictIntents = mutableMapOf<String, String>()
    private fun textFingerprint(text: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private var selected: DiscoveredWindowsPeer? = null
    private var route: AndroidPeerRoute? = null
    private var pairing: Job? = null

    init {
        viewModelScope.launch {
            try {
                runtime.ready.await()
                runtime.sync.startDiscovery()
                launch {
                    try {
                        runtime.sync.discoveredPeers.collectLatest { peers ->
                            hints = peers
                            state = state.copy(discoveredPeers = peers.map(::toUiPeer))
                            restoreRoute()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        state = state.copy(errorMessage = safeMessage(error, "无法更新局域网发现结果"))
                    }
                }
                launch {
                    try {
                        runtime.sync.message.collectLatest { state = state.copy(syncProgressMessage = it) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        state = state.copy(errorMessage = safeMessage(error, "无法更新同步进度"))
                    }
                }
                launch {
                    try {
                        runtime.sync.syncing.collectLatest { state = state.copy(isSyncing = it) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        state = state.copy(errorMessage = safeMessage(error, "无法更新同步运行状态"))
                    }
                }
                launch {
                    try {
                        runtime.sync.remoteStatus.collectLatest { status -> if (status != null) applyRemoteStatus(status) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        state = state.copy(errorMessage = safeMessage(error, "无法更新电脑端同步状态"))
                    }
                }
                launch { runtime.sync.remoteCounts.collectLatest { applyRemoteCounts(it) } }
                launch {
                    try {
                        runtime.sync.changes.collectLatest { refresh() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        state = state.copy(errorMessage = safeMessage(error, "无法响应本机数据变化"))
                    }
                }
                refresh()
                resumeConsentIfNeeded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(errorMessage = safeMessage(error, "无法读取同步状态"))
            }
        }
    }

    private fun toUiPeer(peer: AndroidDiscoveredPeer) = DiscoveredWindowsPeer(
        peer.serverId,
        peer.stableHost,
        peer.address.hostAddress.orEmpty(),
        peer.port,
    )

    private suspend fun refresh() {
        try {
            val snapshot = withContext(Dispatchers.IO) {
                val peer = storage.read()
                val identity = runtime.store.sync.identity()
                val localStatus = peer?.let { runtime.sync.exchange.status(it.pairId) }
                RefreshSnapshot(
                    peer = peer,
                    commitSeq = identity.commitSeq,
                    conflictCount = runtime.store.sync.conflicts().size,
                    localCounts = localStatus?.counts?.takeIf { runtime.sync.remoteCounts.value != null }?.let {
                        AndroidSyncCounts(it.records, it.drafts, it.reports, it.goals, it.reviews)
                    } ?: AndroidSyncCounts(),
                    purgeTrackers = purgeTrackers(runtime.database, peer?.peerDatabaseId),
                    ackedLocalSeq = localStatus?.ackedLocalSeq ?: 0L,
                )
            }
            state = state.copy(
                isPaired = snapshot.peer != null,
                peerDeviceName = snapshot.peer?.displayName.orEmpty(),
                peerHost = snapshot.peer?.stableHost.orEmpty(),
                peerPort = snapshot.peer?.port ?: 8443,
                spaceId = snapshot.peer?.spaceId.orEmpty(),
                pinnedFingerprintSha256 = snapshot.peer?.certificateSha256.orEmpty(),
                localCommitSeq = snapshot.commitSeq,
                remoteAckedSeq = snapshot.ackedLocalSeq,
                unresolvedConflictCount = snapshot.conflictCount,
                localCounts = snapshot.localCounts,
                purgeTrackers = snapshot.purgeTrackers,
            )
            val purged = snapshot.purgeTrackers.mapTo(hashSetOf()) { it.recordId }
            val retained = displayed.filterNot { entity ->
                entity.kind == "record" && entity.key in purged || entity.kind == "draft-slot" && entity.heads.any { head ->
                    val payload = head.payload as? JsonObject
                    (payload?.get("targetRecordId") as? JsonPrimitive)?.contentOrNull in purged ||
                        (payload?.get("recordId") as? JsonPrimitive)?.contentOrNull in purged
                }
            }
            if (retained.size != displayed.size) {
                displayed = retained
                conflictState = conflictUi(retained, endedDraftIds).copy(selectedTab = conflictState.selectedTab,
                    message = "相关记录已彻底删除，候选缓冲已清除；请重新打开查看其余冲突。")
            }
            restoreRoute()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state = state.copy(errorMessage = safeMessage(error, "无法刷新同步状态"))
        }
    }

    private suspend fun resumeConsentIfNeeded() {
        val peer = withContext(Dispatchers.IO) { storage.read() } ?: return
        val status = withContext(Dispatchers.IO) { runtime.sync.exchange.status(peer.pairId) }
        if (!status.sendConsent || !status.receiveConsent) {
            state = state.copy(
                isPairingActive = true,
                pairingStep = AndroidPairingStep.PostAuthMergeConsent,
                errorMessage = null,
            )
            loadPostAuthStatus()
        }
    }

    private fun restoreRoute() {
        viewModelScope.launch {
            try {
                val peer = withContext(Dispatchers.IO) { storage.read() } ?: return@launch
                val hint = hints.firstOrNull { it.serverId == peer.serverId } ?: return@launch
                val network = AndroidLanNetworks.available(getApplication()).firstOrNull() ?: return@launch
                val value = AndroidPeerRoute(
                    peer.serverId,
                    peer.stableHost,
                    hint.address,
                    hint.port,
                    peer.certificateSha256,
                    network,
                )
                route = value
                runtime.sync.selectRoute(value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state = state.copy(syncProgressMessage = "已配对，等待可用的局域网路由")
            }
        }
    }

    fun startPairing() {
        state = state.copy(
            isPairingActive = true,
            pairingStep = AndroidPairingStep.Discovering,
            errorMessage = null,
        )
        runtime.sync.startDiscovery()
        viewModelScope.launch {
            try {
                resumeConsentIfNeeded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(errorMessage = safeMessage(error, "无法恢复待确认的合并步骤"))
            }
        }
    }

    fun cancelPairing() {
        pairing?.cancel()
        pairing = null
        state = state.copy(isPairingActive = false, pairingStep = AndroidPairingStep.Idle)
    }

    fun selectPeer(peer: DiscoveredWindowsPeer) {
        selected = peer
        state = state.copy(pairingStep = AndroidPairingStep.VerifyFingerprint, errorMessage = null)
    }

    fun manualConnect(host: String, port: Int, serverId: String) {
        try {
            val id = serverId.removePrefix("life-").removeSuffix(".local").lowercase()
            require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,62}"))) { "请填写电脑展示的稳定主机名" }
            require(port in 1..65535) { "端口必须在 1 到 65535 之间" }
            val address = numericAddress(host.trim())
            selectPeer(DiscoveredWindowsPeer(id, "life-$id.local", address.hostAddress.orEmpty(), port))
        } catch (error: IllegalArgumentException) {
            state = state.copy(errorMessage = error.message ?: "请填写有效的局域网 IP 地址")
        }
    }

    fun confirmFingerprint(value: String) {
        val peer = selected ?: return
        val pin = value.replace(Regex("[:\\s]"), "").lowercase()
        if (!pin.matches(Regex("[0-9a-f]{64}"))) {
            state = state.copy(errorMessage = "证书 SHA-256 指纹应为 64 位十六进制")
            return
        }
        viewModelScope.launch {
            try {
                val network = AndroidLanNetworks.available(getApplication()).firstOrNull()
                    ?: error("没有可用 Wi-Fi 或以太网")
                val address = numericAddress(peer.ipAddress)
                route = AndroidPeerRoute(peer.serverId, peer.hostName, address, peer.port, pin, network)
                route?.let(runtime.sync::selectRoute)
                state = state.copy(pairingStep = AndroidPairingStep.EnterCode, errorMessage = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(errorMessage = safeMessage(error, "地址、网络或指纹无效"))
            }
        }
    }

    fun submitCode(code: String) {
        val selectedRoute = route ?: run {
            state = state.copy(errorMessage = "请先核对电脑证书指纹")
            return
        }
        pairing?.cancel()
        pairing = viewModelScope.launch {
            try {
                val identity = withContext(Dispatchers.IO) { runtime.store.sync.identity() }
                val request = PairingRequest(clientId = identity.actorEpoch, deviceName = "Android", code = code)
                while (isActive) {
                    when (val result = withContext(Dispatchers.IO) { client.pair(selectedRoute, request) }) {
                        is PairingResult.Pending -> {
                            state = state.copy(pairingStep = AndroidPairingStep.WaitingWindowsAuth)
                            delay(2_000)
                        }
                        is PairingResult.Approved -> {
                            withContext(Dispatchers.IO) {
                                storage.write(result.peer)
                                runtime.sync.exchange.bindSpace(result.peer.spaceId)
                            }
                            runtime.sync.selectRoute(selectedRoute)
                            state = state.copy(pairingStep = AndroidPairingStep.PostAuthMergeConsent, errorMessage = null)
                            refresh()
                            loadPostAuthStatus()
                            return@launch
                        }
                        is PairingResult.Rejected -> {
                            state = state.copy(errorMessage = result.message, pairingStep = AndroidPairingStep.EnterCode)
                            return@launch
                        }
                        is PairingResult.TransportError -> {
                            state = state.copy(errorMessage = result.message, pairingStep = AndroidPairingStep.EnterCode)
                            return@launch
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(errorMessage = safeMessage(error, "配对失败，请核对电脑状态后重试"), pairingStep = AndroidPairingStep.EnterCode)
            }
        }
    }

    private suspend fun loadPostAuthStatus() {
        try {
            val status = runtime.sync.authenticatedStatus()
            if (status == null) {
                state = state.copy(errorMessage = "已安全配对，但暂时无法读取电脑数据统计")
            } else {
                applyRemoteStatus(status)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state = state.copy(errorMessage = safeMessage(error, "已安全配对，但暂时无法读取电脑数据统计"))
        }
    }

    private fun applyRemoteStatus(status: SyncStatus) {
        applyRemoteCounts(status.counts?.let { AndroidPeerCounts(it.records, it.drafts, it.reports, it.goals, it.reviews) })
        if (state.isPairingActive && status.counts == null) state = state.copy(
            syncProgressMessage = "已核验电脑身份；双方同意新增同步范围后显示数量",
        )
    }

    private fun applyRemoteCounts(counts: AndroidPeerCounts?) {
        state = state.copy(
            remoteCounts = counts?.let { AndroidSyncCounts(it.records, it.drafts, it.reports, it.goals, it.reviews) } ?: AndroidSyncCounts(),
            remoteCountsVerified = counts != null,
        )
    }

    fun consent() {
        viewModelScope.launch {
            try {
                val peer = withContext(Dispatchers.IO) { storage.read() } ?: return@launch
                withContext(Dispatchers.IO) { runtime.sync.exchange.setConsent(peer.pairId, true, true) }
                state = state.copy(isPairingActive = false, pairingStep = AndroidPairingStep.Idle, errorMessage = null)
                refresh()
                runtime.sync.wake()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(errorMessage = safeMessage(error, "无法保存合并同意，尚未开始传输"))
            }
        }
    }

    fun trigger() {
        runtime.sync.wake()
        if (state.isPairingActive) viewModelScope.launch { loadPostAuthStatus() }
    }

    fun unpair() {
        viewModelScope.launch {
            try {
                pairing?.cancel()
                pairing = null
                runtime.sync.clearPeer()
                selected = null
                route = null
                state = AndroidSyncUiState(syncProgressMessage = "已解除配对；本机业务数据未删除")
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state = state.copy(errorMessage = safeMessage(error, "解除配对失败，原凭据仍保留"))
            }
        }
    }

    fun openConflicts() {
        viewModelScope.launch { reloadConflicts(open = true) }
    }

    private suspend fun reloadConflicts(open: Boolean = conflictsOpen) {
        try {
            val (current, ended) = withContext(Dispatchers.IO) { runtime.database.transaction { db ->
                val entities = runtime.store.sync.conflicts()
                val terminated = db.query("SELECT DISTINCT draft_id FROM sync_draft_terminals").use { c ->
                    buildSet { while (c.next()) add(c.string(0)) }
                }
                entities to terminated
            } }
            displayed = current
            endedDraftIds = ended
            val selectedTab = conflictState.selectedTab
            conflictState = conflictUi(current, ended).copy(selectedTab = selectedTab)
            conflictsOpen = open
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            conflictState = conflictState.copy(message = safeMessage(error, "无法读取冲突候选"))
        }
    }

    fun closeConflicts() {
        conflictsOpen = false
        conflictIntents.clear()
    }

    fun selectConflictTab(tab: ConflictTab) {
        conflictState = conflictState.copy(selectedTab = tab)
    }

    private fun resolve(kind: String, key: String, actionKey: String, action: (EntityState, Set<String>, String) -> Unit) {
        if (conflictState.resolvingId != null) return
        val baseline = displayed.firstOrNull { it.kind == kind && it.key == key } ?: return
        val heads = baseline.heads.mapTo(linkedSetOf()) { it.id }
        val identity = "$kind\u0000$key\u0000${heads.sorted().joinToString("\u0000")}\u0000$actionKey"
        val intent = conflictIntents.getOrPut(identity) { UUID.randomUUID().toString() }
        viewModelScope.launch {
            conflictState = conflictState.copy(resolvingId = "$kind/$key", message = "")
            try {
                withContext(Dispatchers.IO) { action(baseline, heads, intent) }
                reloadConflicts(open = true)
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                conflictState = conflictState.copy(
                    resolvingId = null,
                    message = safeMessage(error, "冲突处理失败；已展示候选仍保留，请重新查看"),
                )
            } finally { conflictState = conflictState.copy(resolvingId = null) }
        }
    }

    fun chooseRecord(id: String, head: String) = resolve("record", id, "choose:$head") { _, base, intent ->
        runtime.store.resolveRecord(id, head, base, intent)
    }

    fun chooseReport(date: String, head: String) = resolve("report", date, "choose:$head") { _, base, intent ->
        runtime.repository.resolveReport(date, head, base, intent)
    }

    fun chooseGoalEntity(kind: String, key: String, head: String) = resolve(kind, key, "choose:$head") { entity, base, intent ->
        val candidate = entity.heads.singleOrNull { it.id == head } ?: error("候选已变化，请重新查看")
        when (kind) {
            "goal" -> runtime.goals.resolveGoal(key, candidate.payload, base, intent)
            "goal-confirmation" -> runtime.goals.resolveConfirmation(key.substringBefore('/'), key.substringAfter('/'), candidate.payload, base, intent)
            "goal-review" -> runtime.goals.resolveReview(app.liferecord.core.GoalReviewKey.decode(key), head, base, intent)
            else -> error("不支持的目标冲突类型")
        }
    }

    fun integrateGoalEntity(kind: String, key: String, text: String) = resolve(kind, key, "manual:${textFingerprint(text)}") { entity, base, intent ->
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(text)
        when (kind) {
            "goal" -> {
                val edited = app.liferecord.core.GoalCodec.decodeGoal(payload)
                val oldMilestones = entity.heads.flatMap { app.liferecord.core.GoalCodec.decodeGoal(it.payload).milestones }
                val normalized = edited.copy(milestones = edited.milestones.map { milestone ->
                    if (oldMilestones.any { it.id == milestone.id && it.criterion.trim() != milestone.criterion.trim() }) {
                        milestone.copy(id = UUID.nameUUIDFromBytes("$intent/${milestone.id}/${milestone.criterion.trim()}".toByteArray(Charsets.UTF_8)).toString())
                    } else milestone
                })
                runtime.goals.resolveGoal(key, app.liferecord.core.GoalCodec.encodeGoal(normalized), base, intent)
            }
            "goal-confirmation" -> {
                val edited = app.liferecord.core.GoalCodec.decodeConfirmation(payload)
                require(entity.heads.any {
                    val old = app.liferecord.core.GoalCodec.decodeConfirmation(it.payload)
                    old.criterionHash == edited.criterionHash && old.criterionSnapshot == edited.criterionSnapshot
                }) { "整合声明不能改写旧完成标准；请到目标编辑修改标准" }
                runtime.goals.resolveConfirmation(key.substringBefore('/'), key.substringAfter('/'), payload, base, intent)
            }
            else -> error("目标回顾只能采用完整候选")
        }
    }

    fun chooseAnswer(date: String, id: String, head: String) = resolve("answer", "$date/$id", "choose:$head") { _, base, intent ->
        runtime.repository.resolveAnswer(date, id, head, base, intent)
    }

    fun integrateAnswer(date: String, id: String, text: String) = resolve("answer", "$date/$id", "manual:${textFingerprint(text)}") { entity, base, intent ->
        require(text.isNotBlank()) { "请填写整合后的回答" }
        val source = entity.heads.sortedBy { it.id }.firstOrNull()?.payload as? JsonObject
            ?: error("回答候选已变化")
        val payload = buildJsonObject {
            source.forEach { (key, value) -> put(key, value) }
            put("answer", text)
            put("answeredAt", Instant.now().toString())
            put("skipped", false)
        }
        runtime.repository.resolveAnswer(date, id, payload, base, intent)
    }

    fun chooseDraft(slot: String, head: String, copyUnselectedToNew: Boolean) = resolve("draft-slot", slot, "choose:$head:$copyUnselectedToNew") { _, base, intent ->
        runtime.store.resolveDraftConflict(slot, head, base, copyUnselectedToNew, intent)
    }

    fun discardDraftCandidate(slot: String, head: String) = resolve("draft-slot", slot, "discard:$head") { _, base, intent ->
        runtime.store.discardDraftCandidate(slot, head, base, intent)
    }

    fun forkDraftCandidate(slot: String, head: String) = resolve("draft-slot", slot, "fork:$head") { _, base, intent ->
        runtime.store.forkDraft(slot, head, base, intent)
    }

    fun integrateDraft(slot: String, head: String, text: String) = resolve("draft-slot", slot, "manual:$head:${textFingerprint(text)}") { entity, base, intent ->
        require(text.isNotBlank()) { "请填写整合后的草稿" }
        val source = entity.heads.singleOrNull { it.id == head }?.payload as? JsonObject
            ?: error("草稿候选已变化")
        require(source["mode"] == null) { "终结候选没有可编辑正文；请选择保留或放弃残留草稿" }
        val draft = source.toDraft(text)
        val session = EditSession(
            sessionId = UUID.randomUUID().toString(),
            slot = slot,
            draftId = draft.id,
            targetRecordId = draft.targetRecordId,
            baseRecordHeads = draft.baseRecordHeads,
            baseRecordSnapshot = draft.baseRecordSnapshot,
            baseDraftHeads = base,
            lastOwnDraftHead = null,
            draft = draft,
        )
        runtime.store.resolveDraft(session, draft, base, intent)
    }

    fun integrateRecord(id: String, baselineHeadId: String, text: String) = resolve("record", id, "manual:$baselineHeadId:${textFingerprint(text)}") { entity, base, intent ->
        require(text.isNotBlank()) { "请填写整合后的记录" }
        val payload = entity.heads.singleOrNull { it.id == baselineHeadId }?.payload as? JsonObject
            ?: error("记录候选已变化")
        val record = payload.toRecord(id)
        val draft = RecordTime.edit(record).copy(text = text, baseRecordHeads = base, baseRecordSnapshot = record)
        val session = EditSession(
            UUID.randomUUID().toString(),
            "edit/$id",
            draft.id,
            id,
            base,
            record,
            emptySet(),
            null,
            draft,
        )
        runtime.store.resolveRecord(session, draft, base, intent)
    }


    private fun purgeTrackers(database: SqlDatabase, peerDatabaseId: String?): List<AndroidPurgeTrackerItem> = database.transaction { db ->
        db.query(
            "SELECT p.record_id,MAX(c.commit_seq),a.record_id FROM sync_purges p LEFT JOIN sync_changes c ON c.kind='purge' AND c.entity_key=p.record_id LEFT JOIN android_purge_ack a ON a.record_id=p.record_id AND a.purge_id=p.purge_id AND a.peer_database_id=? GROUP BY p.record_id,a.record_id ORDER BY p.record_id",
            listOf(peerDatabaseId ?: ""),
        ).use { cursor ->
            buildList {
                while (cursor.next()) {
                    val seq = if (cursor.isNull(1)) null else cursor.long(1)
                    add(
                        AndroidPurgeTrackerItem(
                            recordId = cursor.string(0),
                            purgedAt = seq?.let { "本机提交 #$it" } ?: "持久删除围栏",
                            isPeerAcked = peerDatabaseId != null && !cursor.isNull(2),
                        ),
                    )
                }
            }
        }
    }


    private fun JsonObject.toRecord(fallbackId: String): Record {
        fun text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return Record(
            id = text("id").ifBlank { fallbackId },
            text = text("text"),
            time = EventTime(text("local"), text("zone"), text("offset"), text("instant").toLong()),
            created = text("created"),
            modified = text("modified").ifBlank { null },
            deleted = text("deleted").ifBlank { null },
        )
    }

    private fun JsonObject.toDraft(replacementText: String): app.liferecord.core.Draft {
        fun text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val snapshot = this["baseRecordSnapshot"] as? JsonObject
        return app.liferecord.core.Draft(
            id = text("id"),
            recordId = text("recordId").ifBlank { null },
            text = replacementText,
            local = text("local"),
            zone = text("zone"),
            offset = text("offset").ifBlank { null },
            targetRecordId = text("targetRecordId"),
            baseRecordHeads = decodeSet(text("baseRecordHeads")),
            baseRecordSnapshot = snapshot?.toRecord(text("targetRecordId")),
        )
    }

    private fun decodeSet(value: String): Set<String> = if (value.isEmpty()) emptySet() else value.split('\u0000').mapTo(linkedSetOf()) {
        String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8)
    }

    private fun numericAddress(value: String): InetAddress {
        require(value.isNotBlank() && (value.contains(':') || value.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")))) {
            "请填写有效的局域网 IP 地址"
        }
        if (!value.contains(':')) require(value.split('.').all { it.toIntOrNull() in 0..255 }) {
            "请填写有效的局域网 IP 地址"
        }
        return InetAddress.getByName(value)
    }

    private fun safeMessage(error: Exception, fallback: String): String = when (error) {
        is AndroidSyncFailure -> error.publicMessage
        is IllegalArgumentException, is IllegalStateException -> error.message?.takeIf { it.isNotBlank() && it.length <= 160 } ?: fallback
        else -> fallback
    }

    override fun onCleared() {
        pairing?.cancel()
    }

    private data class RefreshSnapshot(
        val peer: AndroidSyncPeer?,
        val commitSeq: Long,
        val conflictCount: Int,
        val localCounts: AndroidSyncCounts,
        val purgeTrackers: List<AndroidPurgeTrackerItem>,
        val ackedLocalSeq: Long,
    )
}
