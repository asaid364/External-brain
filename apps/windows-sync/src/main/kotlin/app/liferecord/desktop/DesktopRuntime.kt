package app.liferecord.desktop

import app.liferecord.core.*
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One owner for SQLite, the editor sessions, generation and the selected-interface listener. */
class DesktopRuntime(
    val dataDirectory: Path,
    private val clock: Clock = Clock.systemDefaultZone(),
    goalAi: GoalAnalysisAi = GoalAnalysisClient(),
    private val goalClock: Clock = Clock.systemDefaultZone(),
) {
    private val database = WindowsDatabase(dataDirectory.resolve("records.db"))
    val store = RecordStore(database, clock)
    val reports = ReportRepository(database)
    val exchange = SyncExchange(database)
    val coordinator = GenerationCoordinator(database, reports, clock)
    val goals = GoalRepository(database, goalClock)
    val goalCoordinator = GoalReviewCoordinator(database, goals, goalClock)
    private val settings = AiSettings(WindowsAiStorage(dataDirectory))
    private val secrets = WindowsSecrets(dataDirectory)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gate = Mutex()
    private val serviceGate = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val activeGoalKeys = ConcurrentHashMap<String, GoalReviewKey>()
    private val goalTakeovers = ConcurrentHashMap<String, Boolean>()
    private val _goalMessage = MutableStateFlow("")
    val goalMessage = _goalMessage.asStateFlow()
    private val _goalState = MutableStateFlow(GoalUiState(isLoading = true))
    val goalState = _goalState.asStateFlow()
    private var goalUi: GoalUiState
        get() = _goalState.value
        set(value) { _goalState.value = value }
    private val _records = MutableStateFlow(RecordsScreenUiState(isLoading = true))
    val records = _records.asStateFlow()
    private val _reports = MutableStateFlow(ReportsScreenUiState(isLoading = true))
    val reportState = _reports.asStateFlow()
    private val _sync = MutableStateFlow(DesktopSyncUiState())
    val syncState = _sync.asStateFlow()
    private val _ai = MutableStateFlow(DesktopAiSettingsUiState())
    val aiState = _ai.asStateFlow()
    private var editor: EditSession? = null
    private var displayedRecordHeads: Set<String> = emptySet()
    private var snapshot = Snapshot(emptyList(), emptyList(), emptyList())
    private var projectionHeads: Map<String, Set<String>> = emptyMap()
    private data class LoadedRecords(val snapshot: Snapshot, val conflicts: Set<String>, val commit: Long, val heads: Map<String, Set<String>>)
    private var selectedInterface = ""
    private var discovery: WindowsDiscovery? = null
    private var server: WindowsSyncServer? = null
    private var discoveryState = DiscoveryState()
    private var fingerprint = ""
    private var poll: Job? = null
    private var autosave: Job? = null
    private var closed = false
    private var savedBuffer: DesktopEditingBuffer? = null
    private var saveIntent: String? = null
    private var persistedDraft: Draft? = null
    private val activeDates = linkedSetOf<String>()
    private var lastCommit = -1L
    private val generationLeases = ConcurrentHashMap<String, GenerationLease>()
    private val takeovers = ConcurrentHashMap<String, Boolean>()
    private val aiClient = AiClient()
    private val engine = ReportEngine(reports, settings::load, object : ReportAi {
        override suspend fun generate(configuration: AiConfiguration, input: ReportInput): GeneratedReport {
            val runId = requireNotNull(input.claimToken)
            val lease = generationLeases[input.date] ?: coordinator.claim(input.date, runId, store.sync.identity().databaseId, input.hash, takeovers.remove(input.date) == true).also { generationLeases[input.date] = it }
            require(lease.runId == runId) { "生成任务已被替换" }
            // Retries use the same lease/run and never request a second distributed claim.
            return aiClient.generate(configuration, input)
        }
        override suspend fun test(configuration: AiConfiguration) = aiClient.test(configuration)
    }, clock, serviceGate = serviceGate)
    val goalEngine = GoalAnalysisEngine(goals, settings::load, goalAi, goalClock,
        serviceGate = serviceGate, generationGate = object : GoalReviewGenerationGate {
            override suspend fun beforeCapture(key: GoalReviewKey, automatic: Boolean) {
                require(!automatic) { "Windows仅支持手动目标分析" }
                require(!closed) { "应用正在关闭" }
            }

            override suspend fun run(input: GoalAnalysisInput, automatic: Boolean, generate: suspend () -> Unit) {
                require(!automatic) { "Windows仅支持手动目标分析" }
                val lease = withContext(Dispatchers.IO) {
                    goalCoordinator.claim(input.key, requireNotNull(input.runId), store.sync.identity().databaseId,
                        input.hash, goalTakeovers.remove(input.key.encoded) == true)
                }
                coroutineScope {
                    val heartbeat = launch(Dispatchers.IO) {
                        while (isActive) {
                            delay(GoalReviewCoordinator.HEARTBEAT_MILLIS)
                            // Coordination loss must not discard an already paid local result.
                            runCatching { goalCoordinator.heartbeat(input.key, lease.runId, lease.token) }
                        }
                    }
                    try { generate() }
                    finally {
                        withContext(NonCancellable) {
                            heartbeat.cancelAndJoin()
                            withContext(Dispatchers.IO) {
                                val state = goals.run(input.key)?.state
                                if (state in setOf(GoalRunState.SUCCEEDED, GoalRunState.RESULT_READY, GoalRunState.FAILED)) {
                                    runCatching { goalCoordinator.finish(input.key, lease.runId, lease.token) }
                                }
                            }
                        }
                    }
                }
            }
        })

    private var started = false
    fun start() {
        if (started || closed) return
        started = true
        action("records") {
            try {
                engine.recover()
                withContext(Dispatchers.IO) { goalEngine.recover() }
            } catch (failure: Exception) {
                ready.completeExceptionally(failure)
                throw failure
            }
            ready.complete(Unit)
            withContext(Dispatchers.IO) { exchange.cleanup() }
            loadAi()
            refreshContent(resetQuestions = true)
            refreshGoals()
            scope.launch { goalEngine.changes.collect { refreshGoals() } }
            poll = scope.launch {
                var heartbeatAt = clock.millis()
                while (isActive) {
                    delay(1_000)
                    gate.withLock {
                        try {
                            val identity = withContext(Dispatchers.IO) { store.sync.identity() }
                            if (identity.commitSeq != lastCommit) {
                                refreshContent()
                                refreshGoals()
                            }
                            refreshSync()
                            refreshReport()
                            if (clock.millis() - heartbeatAt >= GenerationCoordinator.HEARTBEAT_MILLIS) {
                                withContext(Dispatchers.IO) {
                                    generationLeases.values.forEach { lease ->
                                        runCatching { coordinator.heartbeat(lease.date, lease.runId, lease.token) }
                                    }
                                }
                                heartbeatAt = clock.millis()
                            }
                        } catch (failure: Exception) { _sync.value = _sync.value.copy(errorMessage = message(failure)) }
                    }
                }
            }
        }
    }

    private fun action(surface: String = "records", block: suspend () -> Unit): Job = scope.launch {
        gate.withLock {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val text = message(failure)
                when (surface) {
                    "reports" -> _reports.value = _reports.value.copy(message = text, isLoading = false)
                    "sync" -> _sync.value = _sync.value.copy(errorMessage = text)
                    "ai" -> _ai.value = _ai.value.copy(errorMessage = text, isSaving = false, isTesting = false)
                    else -> _records.value = _records.value.copy(message = text, isLoading = false)
                }
            }
        }
    }

    private fun message(failure: Exception): String = when (failure) {
        is AiFailure -> failure.userMessage
        is IllegalArgumentException, is IllegalStateException -> failure.message ?: "本机操作失败，原内容保留"
        else -> "本机操作失败，原内容保留；请重试"
    }

    private suspend fun refreshContent(resetQuestions: Boolean = false) {
        val loaded = withContext(Dispatchers.IO) {
            database.transaction {
                val snapshot = store.snapshot()
                LoadedRecords(snapshot, store.sync.unresolvedKeys("record"), store.sync.identity().commitSeq, snapshot.records.associate { it.id to store.sync.headIds("record", it.id) })
            }
        }
        snapshot = loaded.snapshot
        projectionHeads = loaded.heads
        lastCommit = loaded.commit
        _records.value = _records.value.copy(records = snapshot.records, drafts = snapshot.drafts.filter { it.recordId != null }, newDraft = snapshot.drafts.singleOrNull { it.recordId == null }, revisions = snapshot.revisions, conflictRecordIds = loaded.conflicts, isLoading = false)
        val active = editor
        if (active != null) {
            if (withContext(Dispatchers.IO) { store.sync.isPurged(active.targetRecordId) }) {
                autosave?.cancel()
                editor = null
                _records.value = _records.value.copy(editingBuffer = null, isEditing = false, selectedRecordId = null, message = "记录已彻底删除，本机编辑缓冲已清除")
            } else {
                val state = withContext(Dispatchers.IO) { store.sync.readEntity("draft-slot", active.slot) }
                val seen = active.baseDraftHeads + listOfNotNull(active.lastOwnDraftHead)
                val remote = state.heads.firstOrNull { it.id !in seen && it.payload.jsonObject["text"] != null }
                val buffer = _records.value.editingBuffer
                if (remote != null && buffer != null) {
                    if (!buffer.isDirty && buffer.textValue.composition == null && !state.unresolved) {
                        openSession(withContext(Dispatchers.IO) { if (active.slot == "new") store.beginNew() else store.beginEdit(active.targetRecordId) })
                    } else {
                        val payload = remote.payload.jsonObject
                        _records.value = _records.value.copy(editingBuffer = buffer.copy(remoteIncomingHead = RemoteDraftCandidateUiModel(remote.id, remote.id.substringBefore('/'), payload["modified"]?.jsonPrimitive?.content.orEmpty(), payload.getValue("text").jsonPrimitive.content, payload.getValue("local").jsonPrimitive.content)))
                    }
                }
            }
        }
        // Detail actions retire only the versions represented by the currently displayed projection.
        _records.value.selectedRecordId?.let { id ->
            if (editor == null) displayedRecordHeads = projectionHeads[id].orEmpty()
        }
        refreshReport(resetQuestions)
        refreshSync()
    }

    private suspend fun refreshReport(resetQuestions: Boolean = false) {
        val date = _reports.value.date
        try {
            val (day, lease) = withContext(Dispatchers.IO) { reports.day(date) to coordinator.current(date) }
            val local = withContext(Dispatchers.IO) { store.sync.identity().databaseId }
            val status = when (lease?.state) {
                "RUNNING" -> if (lease.holder == local) DesktopCoordinationStatus.RUNNING_LOCAL else DesktopCoordinationStatus.RUNNING_REMOTE
                "UNKNOWN" -> DesktopCoordinationStatus.UNKNOWN_TIMEOUT
                "FINISHED" -> DesktopCoordinationStatus.FINISHED
                else -> if (day.running) DesktopCoordinationStatus.RUNNING_LOCAL else DesktopCoordinationStatus.IDLE
            }
            val previous = _reports.value
            _reports.value = previous.copy(report = day.report, questions = day.questions, records = day.records, isChanged = day.changed, isRunning = day.running, coordinationStatus = status, leaseSecondsRemaining = ((lease?.expiresAt?.minus(clock.millis()) ?: 0L) / 1_000).coerceIn(0, 180).toInt(), remoteHolderName = lease?.holder, canRetryLocalSave = engine.hasPending(date), message = day.message, isLoading = false)
        } catch (failure: UnresolvedConflict) {
            _reports.value = _reports.value.copy(report = null, records = emptyList(), message = "该日期有待解决冲突，请到数据与同步处理：${failure.kind}/${failure.entityKey}", isLoading = false)
        }
    }

    private suspend fun refreshSync() {
        val running = server?.state() ?: WindowsSyncServerState()
        val pair = withContext(Dispatchers.IO) { secrets.pair() }
        val identity = withContext(Dispatchers.IO) { store.sync.identity() }
        val status = pair?.let { withContext(Dispatchers.IO) { exchange.status(it.pairId) } }
        val count = withContext(Dispatchers.IO) { store.sync.conflicts().size }
        val interfaces = withContext(Dispatchers.IO) { discovery?.availableInterfaces().orEmpty() }
        val purgeRows = withContext(Dispatchers.IO) {
            database.query("SELECT p.record_id,MAX(c.commit_seq) FROM sync_purges p LEFT JOIN sync_changes c ON c.entity_key=p.record_id AND c.kind='purge' GROUP BY p.record_id").use { c ->
                buildList { while (c.next()) add(PurgeTrackerItem(c.string(0), "", pair != null && (if (c.isNull(1)) identity.commitSeq else c.long(1)) <= (status?.ackedLocalSeq ?: 0L))) }
            }
        }
        val active = running.pairing?.takeIf { clock.instant().isBefore(it.expiresAt) }
        _sync.value = _sync.value.copy(serverHostName = if(fingerprint.isNotBlank()) withContext(Dispatchers.IO) { secrets.identity().stableHostName } else "")
        _sync.value = _sync.value.copy(isServerRunning = running.running, serverPort = if (running.port > 0) running.port else 8443, selectedInterfaceId = selectedInterface, availableInterfaces = interfaces.map { NetworkInterfaceItem(it.id, it.displayName, it.address.hostAddress) }, certificateFingerprintSha256 = fingerprint, isMdnsActive = discoveryState.active, isPairingActive = active != null, pairingCode = active?.code.orEmpty(), pairingSecondsRemaining = ((active?.expiresAt?.toEpochMilli()?.minus(clock.millis()) ?: 0L) / 1_000).coerceAtLeast(0).toInt(), pairingAttemptsRemaining = active?.attemptsRemaining ?: 0, incomingRequest = if (active == null) null else running.pendingRequest?.let { IncomingPairRequest(it.requestId, it.deviceName, it.clientId, it.requestedAt.toString()) }, isPaired = pair != null, pairedDeviceName = pair?.deviceName.orEmpty(), pairedPeerId = pair?.pairId.orEmpty(), spaceId = identity.spaceId.orEmpty(), localCounts = status?.counts?.takeIf { pair?.peerSendConsent == true && pair.peerReceiveConsent }, needsScopeConsent = pair != null && (status?.sendConsent != true || status.receiveConsent != true), localCommitSeq = identity.commitSeq, remoteAckedSeq = status?.ackedLocalSeq ?: 0L, localReceivedSeq = status?.receivedRemoteSeq ?: 0L, unresolvedConflictCount = count, purgeTrackers = purgeRows, errorMessage = running.error ?: discoveryState.error ?: _sync.value.errorMessage)
    }

    private fun openSession(session: EditSession) {
        editor = session
        savedBuffer = null
        saveIntent = null
        persistedDraft = session.draft
        _records.value = _records.value.copy(isEditing = true, editingBuffer = buffer(session.draft), selectedRecordId = session.draft.recordId, message = "")
    }
    private fun buffer(draft: Draft) = DesktopEditingBuffer(TextFieldValue(draft.text), draft.local, draft.zone, draft.offset, draft.recordId)
    private fun draft(session: EditSession, buffer: DesktopEditingBuffer) = session.draft.copy(text = buffer.textValue.text, local = buffer.local, zone = buffer.zone, offset = buffer.offset)

    fun selectRecord(id: String) = action {
        retainEditor()
        editor = null
        displayedRecordHeads = projectionHeads[id].orEmpty()
        _records.value = _records.value.copy(selectedRecordId = id, isEditing = false, editingBuffer = null)
    }
    fun newRecord() = action { retainEditor(); openSession(withContext(Dispatchers.IO) { store.beginNew() }) }
    fun editRecord(record: Record) = action { retainEditor(); openSession(withContext(Dispatchers.IO) { store.beginEdit(record.id) }) }
    fun continueDraft(draft: Draft) = action { retainEditor(); openSession(withContext(Dispatchers.IO) { draft.recordId?.let(store::beginEdit) ?: store.beginNew() }) }
    fun changeBuffer(value: DesktopEditingBuffer) {
        if (editor == null) return
        val changed = value.copy(isDirty = true)
        _records.value = _records.value.copy(editingBuffer = changed)
        autosave?.cancel()
        val sessionId = editor?.sessionId
        autosave = scope.launch {
            delay(350)
            gate.withLock {
                if (editor?.sessionId == sessionId) {
                    try { retainEditor(); refreshContent() }
                    catch (failure: Exception) { _records.value = _records.value.copy(message = message(failure)) }
                }
            }
        }
    }
    private suspend fun retainEditor() {
        val active = editor ?: return
        val value = _records.value.editingBuffer ?: return
        if (!value.isDirty) return
        val content = draft(active, value)
        if (content == persistedDraft) return
        withContext(NonCancellable) {
            editor = withContext(Dispatchers.IO) { store.keepDraft(active, content) }
            persistedDraft = content
        }
        // Dirty tracks local input ownership, not whether SQLite has caught up. Do not clear it on autosave.
    }
    fun saveBuffer(value: DesktopEditingBuffer) = action {
        autosave?.cancel()
        val active = requireNotNull(editor) { "编辑会话已结束" }
        if (savedBuffer != value || saveIntent == null) { savedBuffer = value; saveIntent = UUID.randomUUID().toString() }
        val intent = requireNotNull(saveIntent)
        val id = withContext(Dispatchers.IO) { store.save(active, draft(active, value), intent) }
        editor = null
        _records.value = _records.value.copy(isEditing = false, editingBuffer = null, selectedRecordId = id, message = "已保存到本机；同步状态独立显示")
        refreshContent()
    }
    fun cancelEdit() = action { retainEditor(); editor = null; _records.value = _records.value.copy(isEditing = false, editingBuffer = null, message = "草稿已保留在本机"); refreshContent() }
    fun discardEditingDraft() = action {
        autosave?.cancel()
        editor?.let { active -> withContext(Dispatchers.IO) { store.discardDraft(active, UUID.randomUUID().toString()) } }
        editor = null
        _records.value = _records.value.copy(isEditing = false, editingBuffer = null)
        refreshContent()
    }
    fun discardNewDraft() = action {
        val active = withContext(Dispatchers.IO) { store.beginNew() }
        withContext(Dispatchers.IO) { store.discardDraft(active, UUID.randomUUID().toString()) }
        refreshContent()
    }
    fun applyRemoteDraft(headId: String) = action {
        val active = requireNotNull(editor)
        val state = withContext(Dispatchers.IO) { store.sync.readEntity("draft-slot", active.slot) }
        require(state.heads.any { it.id == headId }) { "远端草稿候选已变化" }
        require(DesktopRuntimeDialogs.confirm("采用远端草稿", "将采用选中的远端输入；已显示的其他草稿候选不再作为当前版本。继续？")) { "已保留当前输入" }
        openSession(withContext(Dispatchers.IO) { store.resolveDraft(active.slot, headId, state.heads.mapTo(linkedSetOf()) { it.id }, UUID.randomUUID().toString()) })
        refreshContent()
    }
    fun deleteRecord(record: Record) = life(record, false)
    fun restoreRecord(record: Record) = life(record, true)
    private fun life(record: Record, restore: Boolean) = action {
        val heads = displayedRecordHeads
        withContext(Dispatchers.IO) { if (restore) store.restore(record.id, heads, UUID.randomUUID().toString()) else store.moveToTrash(record.id, heads, UUID.randomUUID().toString()) }
        refreshContent()
    }
    fun purgeRecord(record: Record) = action { withContext(Dispatchers.IO) { store.purge(record.id, UUID.randomUUID().toString()) }; refreshContent() }
    fun filter(value: RecordListFilter) { _records.value = _records.value.copy(filter = value) }
    fun search(value: String) { _records.value = _records.value.copy(searchQuery = value) }

    fun selectDate(date: String) = action("reports") {
        LocalDate.parse(date)
        _reports.value = ReportsScreenUiState(date = date, isLoading = true)
        refreshReport(resetQuestions = true)
    }
    fun answer(id: String, text: String, baseHeads: Set<String>, intentId: String) = action("reports") {
        val date = _reports.value.date
        withContext(Dispatchers.IO) { reports.answer(date, id, text, baseHeads, intentId) }
        refreshAnsweredQuestion(id)
    }
    private suspend fun refreshAnsweredQuestion(id: String) {
        val current = withContext(Dispatchers.IO) { reports.day(_reports.value.date) }
        _reports.value = _reports.value.copy(questions = _reports.value.questions.map { old -> if (old.id == id) current.questions.singleOrNull { it.id == id } ?: old else old })
        refreshReport()
    }
    fun skip(id: String, value: Boolean, baseHeads: Set<String>, intentId: String) = action("reports") {
        val date = _reports.value.date
        withContext(Dispatchers.IO) { reports.skip(date, id, value, baseHeads, intentId) }
        refreshAnsweredQuestion(id)
    }
    fun generate(force: Boolean) = action("reports") {
        val date = _reports.value.date
        val day = withContext(Dispatchers.IO) { reports.day(date) }
        if (engine.hasPending(date)) { engine.request(date); refreshReport(); return@action }
        if (day.records.isEmpty() && day.report != null) {
            if (DesktopRuntimeDialogs.confirm("移除失效日报", "当天已无正式记录，是否移除旧正文？答案证据仍保留；不会调用 AI。")) engine.request(date, clearEmpty = true)
            refreshReport(); return@action
        }
        if (day.records.isEmpty() || (!force && day.report?.inputHash == withContext(Dispatchers.IO) { reports.input(date).hash })) {
            if (day.records.isEmpty()) { engine.request(date); refreshReport(); return@action }
            if (!DesktopRuntimeDialogs.confirm("重新生成日报", "输入没有变化。仍重新发送所选日期记录和已保存答案？将再次计费。")) return@action
        }
        if (!DesktopRuntimeDialogs.confirm("发送所选日期给 Windows AI 服务", "将发送 $date 的正式记录及适用的已保存答案，使用本机独立配置并可能计费。当前尚未确认与另一端完成生成前双向同步，可能重复调用/计费。继续？")) return@action
        val lease = withContext(Dispatchers.IO) { coordinator.current(date) }
        if (lease?.state == "UNKNOWN") {
            if (!DesktopRuntimeDialogs.confirm("确认重试，可能再次计费", "上一任务状态未知，不能证明服务端已停止。明确接管并重新调用？")) return@action
            takeovers[date] = true
        }
        engine.request(date, force = true)
        activeDates.add(date)
        refreshReport()
        scope.launch {
            engine.awaitCompletion(date)
            gate.withLock {
                generationLeases.remove(date)?.let { finished -> withContext(Dispatchers.IO) { coordinator.finish(date, finished.runId, finished.token) } }
                takeovers.remove(date)
                activeDates.remove(date)
                refreshContent(resetQuestions = true)
            }
        }
    }
    fun cancelGeneration() = action("reports") { engine.cancel(_reports.value.date); refreshReport() }
    fun retryLocalSave() = action("reports") { engine.request(_reports.value.date); refreshReport() }

    private suspend fun loadAi() {
        try { _ai.value = DesktopAiSettingsUiState(configuration = withContext(Dispatchers.IO) { settings.display() }) }
        catch (failure: Exception) { _ai.value = DesktopAiSettingsUiState(errorMessage = message(failure)) }
    }
    fun saveAi(configuration: AiConfiguration) = action("ai") {
        _ai.value = _ai.value.copy(isSaving = true)
        withContext(Dispatchers.IO) { settings.save(configuration.copy(automatic = false)) }
        loadAi()
    }
    fun testAi(configuration: AiConfiguration) = action("ai") {
        _ai.value = _ai.value.copy(isTesting = true, testMessage = "", errorMessage = null)
        try { engine.test(configuration.copy(automatic = false)); _ai.value = _ai.value.copy(testMessage = "连接测试成功", isTestError = false) }
        catch (failure: Exception) { _ai.value = _ai.value.copy(testMessage = message(failure), isTestError = true) }
        finally { _ai.value = _ai.value.copy(isTesting = false) }
    }

    private suspend fun ensureServer() {
        if (server != null) return
        val identity = withContext(Dispatchers.IO) { secrets.identity() }
        fingerprint = identity.fingerprintSha256
        server = WindowsSyncServer(exchange, secrets, coordinator = coordinator, goalCoordinator = goalCoordinator)
        discovery = WindowsDiscovery(identity, stateChanged = { state -> scope.launch { discoveryState = state } }, interfaceLost = { action("sync") { withContext(Dispatchers.IO) { server?.stop() }; _sync.value = _sync.value.copy(errorMessage = "所选接口已失效，同步已暂停；请重新选择可信接口") } })
    }
    fun selectInterface(id: String) = action("sync") {
        ensureServer()
        val candidate = withContext(Dispatchers.IO) { discovery!!.availableInterfaces().single { it.id == id } }
        withContext(Dispatchers.IO) {
            discovery!!.stop()
            val port = server!!.start(candidate, 8443)
            // mDNS failure must not tear down a valid pinned/manual-address HTTPS listener.
            runCatching { discovery!!.bind(id, port) }.onFailure { discoveryState = DiscoveryState(error = "mDNS不可用，可使用已核对证书指纹的手填地址") }
        }
        selectedInterface = id
        _sync.value = _sync.value.copy(errorMessage = null)
        refreshSync()
    }
    fun openSync() = action("sync") {
        ensureServer()
        refreshSync()
        if (_sync.value.needsScopeConsent) _sync.value = _sync.value.copy(showMergeConsentDialog = true)
    }
    fun leaveSync() = action("sync") { withContext(Dispatchers.IO) { server?.cancelPairing() }; refreshSync() }
    fun startPairing() = action("sync") { withContext(Dispatchers.IO) { server?.startPairing() ?: error("请先选择局域网接口") }; refreshSync() }
    fun cancelPairing() = action("sync") { withContext(Dispatchers.IO) { server?.cancelPairing() }; refreshSync() }
    fun acceptPair(requestId: String) = action("sync") { withContext(Dispatchers.IO) { server!!.approvePairing(requestId) }; _sync.value = _sync.value.copy(showMergeConsentDialog = true); refreshSync() }
    fun rejectPair(requestId: String) = action("sync") { withContext(Dispatchers.IO) { server!!.rejectPairing(requestId) }; refreshSync() }
    fun confirmMerge() = action("sync") { withContext(Dispatchers.IO) { server!!.setLocalConsent(true, true) }; _sync.value = _sync.value.copy(showMergeConsentDialog = false); refreshSync() }
    fun dismissMerge() = action("sync") { _sync.value = _sync.value.copy(showMergeConsentDialog = false) }
    fun triggerSync() = action("sync") {
        ensureServer()
        refreshSync()
        if (_sync.value.needsScopeConsent) _sync.value = _sync.value.copy(showMergeConsentDialog = true)
        else _sync.value = _sync.value.copy(syncProgressMessage = "电脑等待手机发起双向交换；请在手机同步页立即同步。HTTP监听不代表另一端已提交。")
    }
    fun unpair() = action("sync") {
        val pair = withContext(Dispatchers.IO) { secrets.pair() } ?: return@action
        withContext(Dispatchers.IO) { server?.revoke(pair.pairId) ?: secrets.revokePair(pair.pairId) }
        refreshSync()
    }
    fun pauseSync() = action("sync") { withContext(Dispatchers.IO) { discovery?.stop(); server?.stop() }; refreshSync() }
    fun conflicts(recordId: String? = null) = action("sync") {
        val conflicts = withContext(Dispatchers.IO) { store.sync.conflicts().filter { recordId == null || it.key == recordId || it.key == "edit/$recordId" } }
        DesktopRuntimeDialogs.resolveConflicts(conflicts, store, reports, goals)
        refreshContent(resetQuestions = true)
    }

    /** Goal actions deliberately bypass the UI mutex while waiting for AI/service coordination. */
    private fun goalAction(block: suspend () -> Unit): Job = scope.launch {
        try {
            ready.await()
            check(!closed) { "应用正在关闭" }
            block()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            _goalMessage.value = message(failure)
            goalUi = goalUi.copy(isLoading = false, message = message(failure))
        }
    }

    fun grantGoalAnalysis(goalId: String, specHash: String, sendAllowed: Boolean,
        automaticAllowed: Boolean = false) = goalAction {
        require(!automaticAllowed) { "Windows仅支持手动目标分析" }
        val config = withContext(Dispatchers.IO) { settings.load() }
        if (sendAllowed) {
            AiSettings.validateCredentials(config)
            if (!DesktopRuntimeDialogs.confirm("授权此目标发送给AI服务",
                    "将发送所选完整周期的全部正式记录、适用答案、可用日报及目标和用户声明（未经本地相关性筛选）。服务：${config.endpoint}；模型：${config.model}。可能计费；此许可独立于日报及同步许可。")) return@goalAction
        }
        withContext(Dispatchers.IO) {
            goals.grantAnalysis(goalId, specHash, config.endpoint, config.model, sendAllowed, false)
        }
        if (!sendAllowed) activeGoalKeys.values.filter { it.goalId == goalId }.forEach { goalEngine.cancel(it) }
        _goalMessage.value = if (sendAllowed) "本机目标外发已授权；Windows不会自动分析" else "已撤销外发；已发送内容不能保证撤回"
        goalUi = goalUi.copy(message = _goalMessage.value)
        refreshGoals()
    }

    fun requestGoalReview(key: GoalReviewKey, force: Boolean = false) = goalAction {
        // A durable pending result is a free local save, never a new paid request.
        if (withContext(Dispatchers.IO) { goals.run(key)?.state } == GoalRunState.RESULT_READY) {
            goalEngine.retryLocalSave(key)
            return@goalAction
        }
        val freeStatus = withContext(Dispatchers.IO) {
            val input = goals.snapshot(key)
            val existing = goals.detail(key.goalId).reviews.singleOrNull { it.key == key }
            when {
                input.evidence.none { it.dependency.ref.kind == GoalEvidenceKind.RECORD } -> "证据不足，未调用AI"
                !force && existing != null && existing.inputHash == input.hash && goals.reviewStatus(existing) == GoalEvidence.CURRENT -> "无变化，未调用AI"
                else -> null
            }
        }
        if (freeStatus != null) { goalUi = goalUi.copy(message = freeStatus); return@goalAction }
        val lease = withContext(Dispatchers.IO) { goalCoordinator.current(key) }
        require(lease?.state != "RUNNING") { "本周期已有任务运行，不能抢占；请等待或核对另一端" }
        val attempted = withContext(Dispatchers.IO) { goals.externalAttempted(key) }
        val duplicateRisk = force || attempted || lease?.state == "UNKNOWN"
        if (!DesktopRuntimeDialogs.confirm(if (duplicateRisk) "确认重新分析，可能重复计费" else "分析本周期目标",
                "将发送 ${key.fromDate} 至 ${key.throughDate} 的全部正式记录、适用答案、可用日报及目标/用户声明。基于本机数据，手机尚未同步内容不在范围内。" +
                    if (duplicateRisk) "上一调用可能已经计费或仍在处理；明确重新调用可能再次计费。继续？" else "将调用本机配置的AI服务并可能计费。继续？")) return@goalAction
        activeGoalKeys[key.encoded] = key
        if (lease?.state == "UNKNOWN") goalTakeovers[key.encoded] = true
        try {
            goalEngine.request(key, force = duplicateRisk, automatic = false)
            goalEngine.awaitCompletion(key)
            val run = withContext(Dispatchers.IO) { goals.run(key) }
            _goalMessage.value = if (run?.state == GoalRunState.SUCCEEDED) "本周期回顾已更新" else run?.message.orEmpty()
            goalUi = goalUi.copy(message = _goalMessage.value)
            refreshGoals()
        } finally {
            activeGoalKeys.remove(key.encoded)
            goalTakeovers.remove(key.encoded)
        }
    }

    fun cancelGoalReview(key: GoalReviewKey) = goalAction { goalEngine.cancel(key); refreshGoals() }
    fun retryGoalLocalSave(key: GoalReviewKey) = goalAction { goalEngine.retryLocalSave(key); refreshGoals() }
    private val goalIntents = mutableMapOf<String, String>()
    private var goalRefreshVersion = 0L
    private var pendingGoalNavigation: (() -> Unit)? = null
    private fun goalIntent(key: String) = goalIntents.getOrPut(key) { UUID.randomUUID().toString() }

    private fun guardGoalNavigation(next: () -> Unit) {
        if (goalUi.editor?.dirty == true) {
            pendingGoalNavigation = next
            goalUi = goalUi.copy(discardConfirmation = true)
        } else next()
    }
    fun requestGoalNavigation(next: () -> Unit) = guardGoalNavigation(next)
    fun confirmDiscardGoalEditor() {
        val next = pendingGoalNavigation
        pendingGoalNavigation = null
        goalUi = goalUi.copy(editor = null, discardConfirmation = false)
        next?.invoke()
    }
    fun dismissGoalDiscard() { pendingGoalNavigation = null; goalUi = goalUi.copy(discardConfirmation = false) }
    fun hasDirtyGoalEditor() = goalUi.editor?.dirty == true
    fun updateGoalEditor(value: GoalEditorState) {
        val old = goalUi.editor ?: return
        if (value.session != old.session) return
        goalUi = goalUi.copy(editor = value.copy(dirty = true))
    }
    fun selectGoal(id: String?) = guardGoalNavigation {
        goalUi = goalUi.copy(selectedGoalId = id, detail = null, editor = null, selectedPeriodKey = null, currentPeriodEvidence = emptyList())
        refreshGoals()
    }
    fun showArchivedGoals(value: Boolean) = guardGoalNavigation {
        goalUi = goalUi.copy(includeArchived = value)
        refreshGoals()
    }
    fun openGoalEditor(id: String? = null) = guardGoalNavigation {
        goalAction {
            val session = withContext(Dispatchers.IO) { goals.openEditor(id) }
            val initial = session.initial
            val editor = GoalEditorState(session = session,
                title = TextFieldValue(initial?.title.orEmpty()), desiredResult = TextFieldValue(initial?.desiredResult.orEmpty()),
                startDate = TextFieldValue(initial?.startDate ?: LocalDate.now(goalClock).toString()),
                endDate = TextFieldValue(initial?.endDate.orEmpty()),
                reviewZone = TextFieldValue(initial?.reviewZone ?: goalClock.zone.id),
                cadenceDays = TextFieldValue(initial?.cadenceDays?.toString() ?: "7"),
                milestones = initial?.milestones?.map { GoalMilestoneField(it.id, TextFieldValue(it.criterion)) }
                    ?: listOf(GoalMilestoneField(UUID.randomUUID().toString(), TextFieldValue())))
            goalUi = goalUi.copy(selectedGoalId = id, editor = editor, message = null)
            refreshGoals()
        }
    }
    fun closeGoalEditor() = guardGoalNavigation {
        goalUi = goalUi.copy(editor = null)
    }
    fun saveGoal(session: GoalEditSession, edited: GoalSpec) {
        if (goalUi.editor?.session != session) return
        val value = edited.copy(milestones = edited.milestones.map { milestone ->
            val old = session.initial?.milestones?.firstOrNull { it.id == milestone.id }
            if (old != null && old.criterion.trim() != milestone.criterion.trim()) {
                val key = "milestone:${session.goalId}:${milestone.id}:${milestone.criterion.trim()}"
                milestone.copy(id = goalIntent(key))
            } else milestone
        })
        goalAction {
            val intent = goalIntent("save:${session.goalId}:${session.baseHeads.sorted()}:${GoalCodec.encodeGoal(GoalCodec.validateGoal(value))}")
            withContext(Dispatchers.IO) { goals.save(session, value, intent) }
            goalUi = goalUi.copy(selectedGoalId = value.id, editor = null,
                message = "目标已保存；标准变化会重算进度，旧确认与旧标准保留")
            refreshGoals()
        }
    }
    fun archiveGoal(id: String, archived: Boolean) {
        val baseline = goalUi.detail?.takeIf { goalUi.selectedGoalId == id }?.summary?.heads ?: return
        val intent = goalIntent("archive:$id:$archived:$baseline")
        goalAction {
            withContext(Dispatchers.IO) { goals.setArchived(id, archived, baseline, intent) }
            if (archived) activeGoalKeys.values.filter { it.goalId == id }.forEach { goalEngine.cancel(it) }
            refreshGoals()
        }
    }
    fun selectGoalPeriod(key: GoalReviewKey) {
        goalUi = goalUi.copy(selectedPeriodKey = key, currentPeriodEvidence = emptyList())
        refreshGoals()
    }
    fun confirmGoalMilestone(id: String, milestoneId: String, hash: String, confirmed: Boolean, note: String, evidence: List<GoalEvidenceRef>) {
        val detail = goalUi.detail?.takeIf { goalUi.selectedGoalId == id } ?: return
        val base = detail.confirmations.firstOrNull { it.value.milestoneId == milestoneId }?.heads ?: emptySet()
        val intent = goalIntent("confirm:$id:$milestoneId:$hash:$confirmed:$note:$evidence:$base")
        goalAction {
            withContext(Dispatchers.IO) { goals.confirmMilestone(id, milestoneId, hash, confirmed, note, evidence, base, intent) }
            refreshGoals()
        }
    }
    fun refreshGoals() {
        val version = ++goalRefreshVersion
        goalAction {
            val selected = goalUi.selectedGoalId
            val include = goalUi.includeArchived
            val result = withContext(Dispatchers.IO) {
                val list = goals.list(include)
                val detail = selected?.let { goals.detail(it) }
                val goal = detail?.summary?.spec
                val latest = goal?.let { GoalReviewPeriods.latestCompleted(it, goalClock.instant()) }
                val periods = (listOfNotNull(latest) + detail?.reviews.orEmpty().map { it.key } +
                    goalUi.availablePeriods.filter { goal != null && GoalReviewPeriods.contains(goal, it) }).distinct()
                val key = goalUi.selectedPeriodKey?.takeIf { it.goalId == selected } ?: latest
                val config = settings.load()
                val review = detail?.reviews?.firstOrNull { it.key == key }
                val status = if (detail?.conflicts?.any { it.kind == "goal-review" && it.key == key?.encoded } == true) "CONFLICT"
                    else review?.let { goals.reviewStatus(it) }
                GoalUiState(goals = list, includeArchived = include, selectedGoalId = selected, detail = detail,
                    permission = selected?.let { goals.permission(it) }?.takeIf { permission ->
                        goal != null && permission.specHash == GoalCodec.specHash(goal) &&
                            permission.endpoint == config.endpoint && permission.model == config.model
                    }, localAiEndpoint = config.endpoint, localAiModel = config.model,
                    localAiConfigured = runCatching { AiSettings.validateCredentials(config) }.isSuccess,
                    availablePeriods = periods, selectedPeriodKey = key,
                    currentPeriodRun = key?.let { goals.run(it) }, currentPeriodKnown = key?.let { goals.known(it) },
                    currentPeriodStatus = status?.lowercase(),
                    reviewStatuses = detail?.reviews.orEmpty().associate { it.key.encoded to goals.reviewStatus(it).lowercase() },
                    canRetryLocalSave = key?.let { goals.run(it)?.state == GoalRunState.RESULT_READY } == true)
            }
            if (version != goalRefreshVersion || selected != goalUi.selectedGoalId) return@goalAction
            val old = goalUi
            val editor = old.editor?.let { it.copy(remoteChanged = it.remoteChanged ||
                (it.session.initial != null && result.detail?.summary?.heads != it.session.baseHeads)) }
            goalUi = result.copy(editor = editor,
                message = old.message, discardConfirmation = old.discardConfirmation, generationConfirmation = old.generationConfirmation,
                evidenceText = null, currentPeriodEvidence = emptyList())
        }
    }
    fun loadGoalPeriods() = goalAction {
        val goal = goalUi.detail?.summary?.spec ?: return@goalAction
        val periods = withContext(Dispatchers.IO) { GoalReviewPeriods.completed(goal, goalClock.instant()) }
        goalUi = goalUi.copy(availablePeriods = periods)
    }
    fun previewGoalPeriod() = goalAction {
        val key = goalUi.selectedPeriodKey ?: return@goalAction
        val input = withContext(Dispatchers.IO) { goals.snapshot(key) }
        goalUi = goalUi.copy(currentPeriodEvidence = input.evidence,
            message = "${key.fromDate} 至 ${key.throughDate}：${input.coverage.sumOf { it.recordCount }}条正式记录；缺失日期保持未知。按记录保存的当地日期纳入。")
    }
    fun showGoalEvidence(ref: GoalEvidenceRef) = goalAction {
        val text = withContext(Dispatchers.IO) {
            database.transaction { db ->
                val kind = when (ref.kind) { GoalEvidenceKind.RECORD -> "record"; GoalEvidenceKind.ANSWER -> "answer"; GoalEvidenceKind.REPORT -> "report" }
                val entity = store.sync.readEntity(kind, ref.entityKey)
                if (entity.unresolved) return@transaction "来源待解决，无法选择权威正文"
                if (ref.recordIds.any { store.sync.isPurged(it) }) return@transaction "来源已不可用；本应用未另存生成时原文"
                val payload = entity.heads.singleOrNull()?.payload?.jsonObject ?: return@transaction "来源已不可用；本应用未另存生成时原文"
                if (ref.kind == GoalEvidenceKind.RECORD && payload["deleted"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true)
                    return@transaction "来源已不可用；本应用未另存生成时原文"
                val exact = runCatching { GoalEvidence.validateRef(db, ref) }.isSuccess
                val body = when (ref.kind) {
                    GoalEvidenceKind.RECORD -> payload["text"]?.jsonPrimitive?.contentOrNull
                    GoalEvidenceKind.ANSWER -> payload["answer"]?.jsonPrimitive?.contentOrNull
                    GoalEvidenceKind.REPORT -> payload["report"]?.jsonObject?.get("paragraphs")?.jsonArray
                        ?.getOrNull(ref.id.substringAfterLast('/').toIntOrNull() ?: -1)?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                }
                (if (exact) "当前来源\n" else "当前内容已改变，本应用未另存生成时原文\n") + (body ?: "来源已不可用")
            }
        }
        goalUi = goalUi.copy(evidenceText = text)
    }
    fun dismissGoalEvidence() { goalUi = goalUi.copy(evidenceText = null) }
    fun grantGoalPermission(id: String, hash: String, endpoint: String, model: String, send: Boolean, automatic: Boolean) = goalAction {
        val config = withContext(Dispatchers.IO) { settings.load() }
        require(config.endpoint == endpoint && config.model == model) { "AI服务已变化，请刷新并重新授权" }
        grantGoalAnalysis(id, hash, send, automatic).join()
        refreshGoals()
    }
    fun goalActions(onNavigateToAiSettings: () -> Unit = {}, onNavigateToSource: (GoalEvidenceRef) -> Unit = {},
        onResolveConflict: (String, String) -> Unit = { _, _ -> }) = GoalActions(
        onSelectGoal = ::selectGoal, onToggleIncludeArchived = ::showArchivedGoals,
        onOpenCreate = { openGoalEditor() }, onOpenEdit = ::openGoalEditor,
        onSaveGoal = ::saveGoal, onCloseEditor = ::closeGoalEditor, onUpdateEditor = ::updateGoalEditor,
        onToggleArchive = ::archiveGoal, onSelectPeriod = ::selectGoalPeriod,
        onConfirmMilestone = ::confirmGoalMilestone,
        onGrantPermission = { id, hash, endpoint, model, send, automatic -> grantGoalPermission(id, hash, endpoint, model, send, automatic); Unit },
        onRequestAnalysis = { key, force -> requestGoalReview(key, force); Unit },
        onCancelAnalysis = { cancelGoalReview(it); Unit }, onRetryLocalSave = { retryGoalLocalSave(it); Unit },
        onTakeoverUnknown = { requestGoalReview(it, true); Unit },
        onNavigateToAiSettings = { guardGoalNavigation(onNavigateToAiSettings) },
        onNavigateToSource = { showGoalEvidence(it) }, onResolveConflict = { kind, id -> guardGoalNavigation { onResolveConflict(kind, id) } },
        onRefresh = ::refreshGoals, onDirtyChange = { dirty -> goalUi = goalUi.copy(editor = goalUi.editor?.copy(dirty = dirty)) },
        onConfirmDiscard = ::confirmDiscardGoalEditor, onDismissDiscard = ::dismissGoalDiscard,
        onDismissEvidence = ::dismissGoalEvidence, onLoadPeriods = { loadGoalPeriods(); Unit }, onPreviewPeriod = { previewGoalPeriod(); Unit })

    suspend fun close() {
        if (closed) return
        closed = true
        activeGoalKeys.values.toList().forEach { goalEngine.cancel(it) }
        poll?.cancelAndJoin()
        autosave?.cancelAndJoin()
        scope.coroutineContext[Job]?.cancelAndJoin()
        gate.withLock {
            retainEditor()
            withContext(Dispatchers.IO) { discovery?.close(); server?.close() }
            activeDates.toList().forEach { engine.cancel(it) }
            withContext(Dispatchers.IO) {
                generationLeases.values.forEach { coordinator.finish(it.date, it.runId, it.token) }
                generationLeases.clear()
                database.close()
            }
        }
        scope.cancel()
    }
}
