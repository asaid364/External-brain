package app.liferecord

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.liferecord.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.util.UUID

/** Local form/session owner; form text and AI permissions are never sync drafts. */
class GoalViewModel @JvmOverloads constructor(application: Application,
    private val runtime: AppRuntime = AppRuntime.get(application)) : AndroidViewModel(application) {
    var state by mutableStateOf(GoalUiState(isLoading = true)); private set
    private var gateDecision: GoalGenerationConfirmation? = null
    private var activeEvidenceRef: GoalEvidenceRef? = null
    private var evidenceLoadJob: Job? = null
    init {
        viewModelScope.launch {
            try {
                runtime.ready.await()
                refreshGoals()
                launch { runtime.goalEngine.changes.collect { refreshGoals() } }
                launch { runtime.sync.changes.collect { refreshGoals() } }
                (runtime.database as? AndroidDatabase)?.let { db -> launch { db.contentChanges.collect { refreshGoals() } } }
                runtime.goalGeneration?.let { gate -> launch { gate.confirmations.collect { decisions ->
                    gateDecision = decisions.firstOrNull()
                    state = state.copy(generationConfirmation = gateDecision?.message ?: pendingGoalRequest?.let { requestText(it.first, it.second) })
                } } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { state = state.copy(isLoading = false, message = failure.message ?: "目标读取失败，请重试") }
        }
    }
    private fun operation(block: suspend () -> Unit) = viewModelScope.launch {
        try { runtime.ready.await(); block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { state = state.copy(isLoading = false, message = if (failure is AiFailure) failure.userMessage else failure.message ?: "本机操作未成功，原内容保留") }
    }
    private val goalIntents = mutableMapOf<String, String>()
    private var goalRefreshVersion = 0L
    private var pendingGoalNavigation: (() -> Unit)? = null
    private var pendingGoalRequest: Pair<GoalReviewKey, Boolean>? = null
    private fun goalIntent(key: String) = goalIntents.getOrPut(key) { UUID.randomUUID().toString() }

    private fun guardGoalNavigation(next: () -> Unit) {
        if (state.editor?.dirty == true) {
            pendingGoalNavigation = next
            state = state.copy(discardConfirmation = true)
        } else next()
    }
    fun requestGoalNavigation(next: () -> Unit) = guardGoalNavigation(next)
    fun confirmDiscardGoalEditor() {
        val next = pendingGoalNavigation
        pendingGoalNavigation = null
        state = state.copy(editor = null, discardConfirmation = false)
        next?.invoke()
    }
    fun dismissGoalDiscard() { pendingGoalNavigation = null; state = state.copy(discardConfirmation = false) }
    fun hasDirtyGoalEditor() = state.editor?.dirty == true
    fun updateGoalEditor(value: GoalEditorState) {
        val old = state.editor ?: return
        if (value.session != old.session) return
        state = state.copy(editor = value.copy(dirty = true))
    }
    fun selectGoal(id: String?) = guardGoalNavigation {
        state = state.copy(selectedGoalId = id, detail = null, editor = null, selectedPeriodKey = null, currentPeriodEvidence = emptyList())
        refreshGoals()
    }
    fun showArchivedGoals(value: Boolean) = guardGoalNavigation {
        state = state.copy(includeArchived = value)
        refreshGoals()
    }
    fun openGoalEditor(id: String? = null) = guardGoalNavigation {
        operation {
            val session = withContext(Dispatchers.IO) { runtime.goals.openEditor(id) }
            val initial = session.initial
            val editor = GoalEditorState(session = session,
                title = TextFieldValue(initial?.title.orEmpty()), desiredResult = TextFieldValue(initial?.desiredResult.orEmpty()),
                startDate = TextFieldValue(initial?.startDate ?: LocalDate.now(runtime.goalClock).toString()),
                endDate = TextFieldValue(initial?.endDate.orEmpty()),
                reviewZone = TextFieldValue(initial?.reviewZone ?: runtime.goalClock.zone.id),
                cadenceDays = TextFieldValue(initial?.cadenceDays?.toString() ?: "7"),
                milestones = initial?.milestones?.map { GoalMilestoneField(it.id, TextFieldValue(it.criterion)) }
                    ?: listOf(GoalMilestoneField(UUID.randomUUID().toString(), TextFieldValue())))
            state = state.copy(selectedGoalId = id, editor = editor, message = null)
            refreshGoals()
        }
    }
    fun closeGoalEditor() = guardGoalNavigation {
        state = state.copy(editor = null)
    }
    fun saveGoal(session: GoalEditSession, edited: GoalSpec) {
        if (state.editor?.session != session) return
        val value = edited.copy(milestones = edited.milestones.map { milestone ->
            val old = session.initial?.milestones?.firstOrNull { it.id == milestone.id }
            if (old != null && old.criterion.trim() != milestone.criterion.trim()) {
                val key = "milestone:${session.goalId}:${milestone.id}:${milestone.criterion.trim()}"
                milestone.copy(id = goalIntent(key))
            } else milestone
        })
        operation {
            val intent = goalIntent("save:${session.goalId}:${session.baseHeads.sorted()}:${GoalCodec.encodeGoal(GoalCodec.validateGoal(value))}")
            withContext(Dispatchers.IO) { runtime.goals.save(session, value, intent) }
            state = state.copy(selectedGoalId = value.id, editor = null,
                message = "目标已保存；标准变化会重算进度，旧确认与旧标准保留")
            refreshGoals()
        }
    }
    fun archiveGoal(id: String, archived: Boolean) {
        val baseline = state.detail?.takeIf { state.selectedGoalId == id }?.summary?.heads ?: return
        val intent = goalIntent("archive:$id:$archived:$baseline")
        operation {
            withContext(Dispatchers.IO) { runtime.goals.setArchived(id, archived, baseline, intent) }
            if (archived) withContext(Dispatchers.IO) { runtime.goalPermissionsChanged(id) }
            else runtime.rearmGoalReviews()
            refreshGoals()
        }
    }
    fun selectGoalPeriod(key: GoalReviewKey) {
        state = state.copy(selectedPeriodKey = key, currentPeriodEvidence = emptyList())
        refreshGoals()
    }
    fun confirmGoalMilestone(id: String, milestoneId: String, hash: String, confirmed: Boolean, note: String, evidence: List<GoalEvidenceRef>) {
        val detail = state.detail?.takeIf { state.selectedGoalId == id } ?: return
        val base = detail.confirmations.firstOrNull { it.value.milestoneId == milestoneId }?.heads ?: emptySet()
        val intent = goalIntent("confirm:$id:$milestoneId:$hash:$confirmed:$note:$evidence:$base")
        operation {
            withContext(Dispatchers.IO) { runtime.goals.confirmMilestone(id, milestoneId, hash, confirmed, note, evidence, base, intent) }
            refreshGoals()
        }
    }
    fun refreshGoals() {
        val version = ++goalRefreshVersion
        // A data refresh must revalidate the open source, not silently dismiss it.
        // Replace its old body immediately so purge/delete cannot leave cached text visible.
        if (activeEvidenceRef != null) reloadGoalEvidence()
        operation {
            val selected = state.selectedGoalId
            val include = state.includeArchived
            val result = withContext(Dispatchers.IO) {
                val list = runtime.goals.list(include)
                val detail = selected?.let { runtime.goals.detail(it) }
                val goal = detail?.summary?.spec
                val latest = goal?.let { GoalReviewPeriods.latestCompleted(it, runtime.goalClock.instant()) }
                val periods = (listOfNotNull(latest) + detail?.reviews.orEmpty().map { it.key } +
                    state.availablePeriods.filter { goal != null && GoalReviewPeriods.contains(goal, it) }).distinct()
                val key = state.selectedPeriodKey?.takeIf { it.goalId == selected } ?: latest
                val config = runtime.settings.load()
                val review = detail?.reviews?.firstOrNull { it.key == key }
                val status = if (detail?.conflicts?.any { it.kind == "goal-review" && it.key == key?.encoded } == true) "CONFLICT"
                    else review?.let { runtime.goals.reviewStatus(it) }
                GoalUiState(goals = list, includeArchived = include, selectedGoalId = selected, detail = detail,
                    permission = selected?.let { runtime.goals.permission(it) }?.takeIf { permission ->
                        goal != null && permission.specHash == GoalCodec.specHash(goal) &&
                            permission.endpoint == config.endpoint && permission.model == config.model
                    }, localAiEndpoint = config.endpoint, localAiModel = config.model,
                    localAiConfigured = runCatching { AiSettings.validateCredentials(config) }.isSuccess,
                    availablePeriods = periods, selectedPeriodKey = key,
                    currentPeriodRun = key?.let { runtime.goals.run(it) }, currentPeriodKnown = key?.let { runtime.goals.known(it) },
                    currentPeriodStatus = status?.lowercase(),
                    reviewStatuses = detail?.reviews.orEmpty().associate { it.key.encoded to runtime.goals.reviewStatus(it).lowercase() },
                    canRetryLocalSave = key?.let { runtime.goals.run(it)?.state == GoalRunState.RESULT_READY } == true)
            }
            if (version != goalRefreshVersion || selected != state.selectedGoalId) return@operation
            val old = state
            val editor = old.editor?.let { it.copy(remoteChanged = it.remoteChanged ||
                (it.session.initial != null && result.detail?.summary?.heads != it.session.baseHeads)) }
            state = result.copy(editor = editor,
                message = old.message, discardConfirmation = old.discardConfirmation, generationConfirmation = old.generationConfirmation,
                evidenceText = old.evidenceText, currentPeriodEvidence = emptyList())
        }
    }
    fun loadGoalPeriods() = operation {
        val goal = state.detail?.summary?.spec ?: return@operation
        val periods = withContext(Dispatchers.IO) { GoalReviewPeriods.completed(goal, runtime.goalClock.instant()) }
        state = state.copy(availablePeriods = periods)
    }
    fun previewGoalPeriod() = operation {
        val key = state.selectedPeriodKey ?: return@operation
        val input = withContext(Dispatchers.IO) { runtime.goals.snapshot(key) }
        state = state.copy(currentPeriodEvidence = input.evidence,
            message = "${key.fromDate} 至 ${key.throughDate}：${input.coverage.sumOf { it.recordCount }}条正式记录；缺失日期保持未知。按记录保存的当地日期纳入。")
    }
    fun showGoalEvidence(ref: GoalEvidenceRef): Job {
        activeEvidenceRef = ref
        return reloadGoalEvidence()
    }
    private fun reloadGoalEvidence(): Job {
        evidenceLoadJob?.cancel()
        val ref = checkNotNull(activeEvidenceRef)
        state = state.copy(evidenceText = "正在重新核对来源…")
        return operation {
            val text = try {
                withContext(Dispatchers.IO) { readGoalEvidence(ref) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "来源读取失败，请关闭后重新打开；当前无法核对内容"
            }
            currentCoroutineContext().ensureActive()
            if (activeEvidenceRef == ref) state = state.copy(evidenceText = text)
        }.also { evidenceLoadJob = it }
    }
    private fun readGoalEvidence(ref: GoalEvidenceRef): String =
        runtime.database.transaction { db ->
                val kind = when (ref.kind) { GoalEvidenceKind.RECORD -> "record"; GoalEvidenceKind.ANSWER -> "answer"; GoalEvidenceKind.REPORT -> "report" }
                val entity = runtime.store.sync.readEntity(kind, ref.entityKey)
                if (entity.unresolved) return@transaction "来源待解决，无法选择权威正文"
                if (ref.recordIds.any { runtime.store.sync.isPurged(it) }) return@transaction "来源已不可用；本应用未另存生成时原文"
                val payload = entity.heads.firstOrNull()?.payload?.jsonObject ?: return@transaction "来源已不可用；本应用未另存生成时原文"
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
    fun dismissGoalEvidence() {
        activeEvidenceRef = null
        evidenceLoadJob?.cancel()
        evidenceLoadJob = null
        state = state.copy(evidenceText = null)
    }

    fun grantGoalPermission(id: String, hash: String, endpoint: String, model: String, send: Boolean, automatic: Boolean) = operation {
        withContext(Dispatchers.IO) {
            val config = runtime.settings.load()
            if (send) AiSettings.validateCredentials(config)
            require(config.endpoint == endpoint && config.model == model) { "AI服务已变化，请刷新后重新授权" }
            runtime.goals.grantAnalysis(id, hash, endpoint, model, send, automatic)
        }
        withContext(Dispatchers.IO) { runtime.goalPermissionsChanged(id) }
        refreshGoals()
    }
    private fun requestText(key: GoalReviewKey, force: Boolean) =
        "将发送 ${key.fromDate} 至 ${key.throughDate} 的全部正式记录、适用答案、可用日报、目标和用户声明，未经本地相关性筛选。AI服务：${state.localAiEndpoint}；模型：${state.localAiModel}。" +
            if (force) "此前调用可能已计费；重新分析可能重复计费。仅授权本次请求。" else "将调用AI并可能计费，是否继续？"
    fun requestGoalAnalysis(key: GoalReviewKey, force: Boolean = false) = operation {
        if (withContext(Dispatchers.IO) { runtime.goals.run(key)?.state } == GoalRunState.RESULT_READY) {
            runtime.goalEngine.retryLocalSave(key); refreshGoals(); return@operation
        }
        val freeStatus = withContext(Dispatchers.IO) {
            val input = runtime.goals.snapshot(key)
            val existing = runtime.goals.detail(key.goalId).reviews.singleOrNull { it.key == key }
            when {
                input.evidence.none { it.dependency.ref.kind == GoalEvidenceKind.RECORD } -> "证据不足，未调用AI"
                !force && existing != null && existing.inputHash == input.hash && runtime.goals.reviewStatus(existing) == GoalEvidence.CURRENT -> "无变化，未调用AI"
                else -> null
            }
        }
        if (freeStatus != null) { state = state.copy(message = freeStatus); return@operation }
        val risk = withContext(Dispatchers.IO) { force || runtime.goals.externalAttempted(key) || runtime.goals.known(key)?.state == "UNKNOWN" }
        pendingGoalRequest = key to risk
        state = state.copy(generationConfirmation = requestText(key, risk))
    }
    fun confirmGoalAnalysis() {
        gateDecision?.let { decision -> runtime.goalGeneration?.resolveConfirmation(decision.id, true); return }
        val request = pendingGoalRequest ?: return
        pendingGoalRequest = null
        state = state.copy(generationConfirmation = null)
        operation {
            runtime.goalEngine.request(request.first, force = request.second)
            runtime.goalEngine.awaitCompletion(request.first)
            val run = withContext(Dispatchers.IO) { runtime.goals.run(request.first) }
            state = state.copy(message = if (run?.state == GoalRunState.SUCCEEDED) "本周期回顾已更新" else run?.message)
            refreshGoals()
        }
    }
    fun dismissGoalAnalysis() {
        gateDecision?.let { runtime.goalGeneration?.resolveConfirmation(it.id, false) }
        pendingGoalRequest = null
        state = state.copy(generationConfirmation = null)
    }
    fun cancelGoalAnalysis(key: GoalReviewKey) = operation { runtime.goalEngine.cancel(key); refreshGoals() }
    fun retryGoalSave(key: GoalReviewKey) = operation { runtime.goalEngine.retryLocalSave(key); refreshGoals() }
    fun goalActions(onNavigateToAiSettings: () -> Unit = {}, onNavigateToSource: (GoalEvidenceRef) -> Unit = {},
        onResolveConflict: (String, String) -> Unit = { _, _ -> }) = GoalActions(
        onSelectGoal = ::selectGoal, onToggleIncludeArchived = ::showArchivedGoals,
        onOpenCreate = { openGoalEditor() }, onOpenEdit = ::openGoalEditor,
        onSaveGoal = ::saveGoal, onCloseEditor = ::closeGoalEditor, onUpdateEditor = ::updateGoalEditor,
        onToggleArchive = ::archiveGoal, onSelectPeriod = ::selectGoalPeriod,
        onConfirmMilestone = ::confirmGoalMilestone,
        onGrantPermission = { id, hash, endpoint, model, send, automatic -> grantGoalPermission(id, hash, endpoint, model, send, automatic); Unit },
        onRequestAnalysis = { key, force -> requestGoalAnalysis(key, force); Unit },
        onCancelAnalysis = { cancelGoalAnalysis(it); Unit }, onRetryLocalSave = { retryGoalSave(it); Unit },
        onTakeoverUnknown = { requestGoalAnalysis(it, true); Unit },
        onNavigateToAiSettings = { guardGoalNavigation(onNavigateToAiSettings) },
        onNavigateToSource = { showGoalEvidence(it) }, onResolveConflict = { kind, id -> guardGoalNavigation { onResolveConflict(kind, id) } },
        onRefresh = ::refreshGoals, onDirtyChange = { dirty -> state = state.copy(editor = state.editor?.copy(dirty = dirty)) },
        onConfirmDiscard = ::confirmDiscardGoalEditor, onDismissDiscard = ::dismissGoalDiscard,
        onConfirmAnalysis = ::confirmGoalAnalysis, onDismissAnalysis = ::dismissGoalAnalysis,
        onDismissEvidence = ::dismissGoalEvidence, onLoadPeriods = { loadGoalPeriods(); Unit }, onPreviewPeriod = { previewGoalPeriod(); Unit })
}
