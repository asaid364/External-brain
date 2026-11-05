package app.liferecord.core

import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Causal goal definitions and user-owned milestone confirmations. */
class GoalRepository(
    private val database: SqlDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val sync = SyncRepository(database)
    private val reports = ReportRepository(database)

    fun openEditor(goalId: String? = null): GoalEditSession = transaction { db ->
        if (goalId == null) return@transaction GoalEditSession(UUID.randomUUID().toString(), emptySet(), null)
        val state = sync.readEntity(db, GOAL_KIND, goalId)
        if (state.unresolved) throw UnresolvedConflict(GOAL_KIND, goalId)
        val spec = state.heads.firstOrNull()?.payload?.let(GoalCodec::decodeGoal)
            ?: throw IllegalArgumentException("目标不存在")
        GoalEditSession(goalId, state.heads.mapTo(linkedSetOf()) { it.id }, spec)
    }

    fun save(session: GoalEditSession, edited: GoalSpec, intentId: String): String = transaction { db ->
        val value = GoalCodec.validateGoal(edited)
        require(value.id == session.goalId) { "目标身份不一致" }
        if (session.initial != null && value == session.initial) return@transaction session.goalId
        session.initial?.milestones?.associateBy { it.id }?.let { original ->
            value.milestones.forEach { milestone ->
                original[milestone.id]?.let { prior ->
                    require(prior.criterion == milestone.criterion) { "修改完成标准必须分配新的里程碑ID" }
                }
            }
        }
        val result = sync.writeLocalHead(
            db, GOAL_KIND, value.id, GoalCodec.encodeGoal(value), observedBase = session.baseHeads,
            intentId = intentId, resultKey = value.id,
        )
        projectGoal(db, sync.readEntity(db, GOAL_KIND, value.id))
        if (value.archived) disableAutomatic(db, value.id)
        result.resultKey
    }

    fun setArchived(goalId: String, archived: Boolean, baseHeads: Set<String>, intentId: String): Unit = transaction { db ->
        if (intentCommitted(db, intentId)) return@transaction
        val state = sync.readEntity(db, GOAL_KIND, goalId)
        require(!state.unresolved && state.heads.isNotEmpty()) { "目标候选已变化，请重新查看" }
        require(baseHeads.isNotEmpty() && state.heads.map { it.id }.toSet() == baseHeads) { "目标候选已变化，请重新查看" }
        val current = GoalCodec.decodeGoal(state.heads.first().payload)
        if (current.archived != archived) {
            sync.writeLocalHead(db, GOAL_KIND, goalId, GoalCodec.encodeGoal(current.copy(archived = archived)),
                observedBase = baseHeads, intentId = intentId, resultKey = goalId)
            projectGoal(db, sync.readEntity(db, GOAL_KIND, goalId))
        }
        if (archived) disableAutomatic(db, goalId)
    }

    fun confirmMilestone(
        goalId: String,
        milestoneId: String,
        criterionHash: String,
        confirmed: Boolean,
        note: String,
        evidence: List<GoalEvidenceRef>,
        baseHeads: Set<String>,
        intentId: String,
    ): Unit = transaction { db ->
        if (intentCommitted(db, intentId)) return@transaction
        val goalState = sync.readEntity(db, GOAL_KIND, goalId)
        if (goalState.unresolved) throw UnresolvedConflict(GOAL_KIND, goalId)
        val goal = goalState.heads.firstOrNull()?.payload?.let(GoalCodec::decodeGoal)
            ?: throw IllegalArgumentException("目标不存在")
        val milestone = goal.milestones.singleOrNull { it.id == milestoneId }
            ?: throw IllegalArgumentException("完成标准已变化，请重新核对")
        val snapshot = milestone.criterion.trim()
        require(GoalCodec.criterionHash(snapshot) == criterionHash) { "完成标准已变化，请重新核对" }
        val key = confirmationKey(goalId, milestoneId)
        val state = sync.readEntity(db, CONFIRMATION_KIND, key)
        val value = GoalCodec.validateConfirmation(GoalMilestoneConfirmation(
            goalId, milestoneId, criterionHash, snapshot, confirmed, note, evidence,
            clock.instant().toString(),
        ))
        value.evidence.forEach { validateEvidence(db, it) }
        val existing = state.takeUnless { it.unresolved }?.heads?.firstOrNull()?.payload?.let(GoalCodec::decodeConfirmation)
        if (existing != null && existing.copy(confirmedAt = value.confirmedAt) == value) return@transaction
        sync.writeLocalHead(db, CONFIRMATION_KIND, key, GoalCodec.encodeConfirmation(value),
            observedBase = baseHeads, intentId = intentId, resultKey = key)
        projectConfirmation(db, sync.readEntity(db, CONFIRMATION_KIND, key))
    }

    fun list(includeArchived: Boolean = false): List<GoalSummary> = transaction { db ->
        goalIds(db).map { summary(db, it) }
            .filter { includeArchived || it.spec?.archived != true }
            .sortedWith(compareBy<GoalSummary> { it.spec?.archived == true }.thenBy { it.spec?.startDate ?: "" }.thenBy { it.spec?.title ?: "" })
    }

    fun detail(goalId: String): GoalDetail = transaction { db ->
        val summary = summary(db, goalId)
        val confirmations = entityKeys(db, CONFIRMATION_KIND, "$goalId/").mapNotNull { key ->
            val state = sync.readEntity(db, CONFIRMATION_KIND, key)
            if (state.unresolved || state.heads.isEmpty()) null
            else GoalConfirmationSnapshot(GoalCodec.decodeConfirmation(state.heads.first().payload), state.heads.mapTo(linkedSetOf()) { it.id })
        }
        val reviews = entityKeys(db, REVIEW_KIND).mapNotNull { key ->
            val state = sync.readEntity(db, REVIEW_KIND, key)
            if (state.unresolved || state.heads.isEmpty()) null else GoalCodec.decodeReview(state.heads.first().payload).takeIf { it.key.goalId == goalId }
        }
        val conflicts = buildList {
            val goal = sync.readEntity(db, GOAL_KIND, goalId)
            if (goal.unresolved) add(goal)
            entityKeys(db, CONFIRMATION_KIND, "$goalId/").forEach { key -> sync.readEntity(db, CONFIRMATION_KIND, key).takeIf { it.unresolved }?.let(::add) }
            entityKeys(db, REVIEW_KIND).forEach { key -> sync.readEntity(db, REVIEW_KIND, key).takeIf { state -> state.unresolved && state.heads.any { runCatching { GoalCodec.decodeReview(it.payload).key.goalId == goalId }.getOrDefault(false) } }?.let(::add) }
        }
        GoalDetail(summary, confirmations, conflicts, reviews)
    }

    /** Captures a completed review window and all of its current evidence atomically. */
    fun snapshot(key: GoalReviewKey): GoalAnalysisInput = transaction { db ->
        val normalized = GoalCodec.validateReviewKey(key)
        val goalState = sync.readEntity(db, GOAL_KIND, normalized.goalId)
        if (goalState.unresolved) throw UnresolvedConflict(GOAL_KIND, normalized.goalId)
        val goalHead = goalState.heads.firstOrNull() ?: throw IllegalArgumentException("目标不存在")
        val goal = GoalCodec.decodeGoal(goalHead.payload)
        require(!goal.archived) { "已归档目标不能生成回顾" }
        val confirmations = goal.milestones.mapNotNull { milestone ->
            val confirmationKey = confirmationKey(goal.id, milestone.id)
            val state = sync.readEntity(db, CONFIRMATION_KIND, confirmationKey)
            if (state.unresolved) throw UnresolvedConflict(CONFIRMATION_KIND, confirmationKey)
            state.heads.firstOrNull()?.let { head ->
                GoalConfirmationSnapshot(
                    GoalCodec.decodeConfirmation(head.payload),
                    state.heads.mapTo(linkedSetOf()) { it.id },
                )
            }
        }
        GoalEvidence.snapshot(db, goal, goalState.heads.mapTo(linkedSetOf()) { it.id }, confirmations, normalized, clock)
    }

    /** Query-time validity of a retained historical review. */
    fun reviewStatus(review: GoalReviewPayload): String = transaction { db -> GoalEvidence.status(review, db) }

    fun permission(goalId: String): GoalAnalysisPermission? = transaction { db -> permission(db, goalId) }

    fun serviceCooldown(service: String): Long = reports.serviceCooldown(service)
    fun setServiceCooldown(service: String, until: Long): Unit = reports.setServiceCooldown(service, until)
    fun grantAnalysis(
        goalId: String,
        specHash: String,
        endpoint: String,
        model: String,
        sendAllowed: Boolean,
        automaticAllowed: Boolean,
    ): Unit = transaction { db ->
        val state = sync.readEntity(db, GOAL_KIND, goalId)
        require(!state.unresolved && state.heads.size == 1) { "目标候选待核对" }
        val goal = GoalCodec.decodeGoal(state.heads.single().payload)
        require(!goal.archived || !sendAllowed) { "已归档目标不能授权分析" }
        require(specHash == GoalCodec.specHash(goal)) { "目标内容已变化，请重新授权" }
        require(sendAllowed || !automaticAllowed) { "未允许外发时不能开启自动分析" }
        val normalizedEndpoint = ChatCompletionClient.normalizeEndpoint(endpoint).toString()
        val normalizedModel = model.trim().also { require(it.isNotEmpty()) { "AI模型不能为空" } }
        val args = listOf(specHash, normalizedEndpoint, normalizedModel, if (sendAllowed) 1 else 0, if (automaticAllowed) 1 else 0, goalId)
        if (db.exec("UPDATE goal_analysis_permissions SET spec_hash=?,endpoint=?,model=?,send_allowed=?,automatic_allowed=? WHERE goal_id=?", args) == 0) {
            db.exec("INSERT INTO goal_analysis_permissions(spec_hash,endpoint,model,send_allowed,automatic_allowed,goal_id) VALUES(?,?,?,?,?,?)", args)
        }
    }

    fun run(key: GoalReviewKey): GoalRunInfo? = transaction { db -> run(db, GoalCodec.validateReviewKey(key)) }

    fun externalAttempted(key: GoalReviewKey): Boolean = transaction { db ->
        db.query("SELECT external_attempted FROM goal_review_runs WHERE review_key=?", listOf(GoalCodec.validateReviewKey(key).encoded))
            .use { it.next() && it.long(0) != 0L }
    }

    /** Atomically captures the paid request snapshot and persists PREPARING. */
    fun claim(key: GoalReviewKey, automatic: Boolean, service: String, model: String): GoalAnalysisInput? = transaction { db ->
        val normalized = GoalCodec.validateReviewKey(key)
        val captured = snapshotInTransaction(db, normalized)
        val permission = requirePermission(db, captured.goal, service, model, automatic)
        if (automatic) {
            require(clock.instant() >= GoalReviewPeriods.due(normalized, captured.goal)) { "自动回顾尚未到执行时间" }
            if (run(db, normalized)?.externalAttempted == true) return@transaction null
        }
        val previous = run(db, normalized)
        if (previous?.state in setOf(GoalRunState.PREPARING, GoalRunState.RUNNING, GoalRunState.RESULT_READY)) return@transaction null
        val runId = UUID.randomUUID().toString()
        val input = captured.copy(runId = runId)
        val context = context(input, permission.endpoint, permission.model)
        val stickyAttempt = previous?.externalAttempted == true
        val args = listOf(
            runId, GoalRunState.PREPARING.name, input.hash, GoalCodec.encodeContextString(context),
            if (stickyAttempt) 1 else 0, clock.millis(), normalized.encoded,
        )
        if (db.exec("UPDATE goal_review_runs SET run_id=?,state=?,input_hash=?,context_json=?,pending_json=NULL,request_sent=0,external_attempted=?,message='',retry_at=NULL,started_at=? WHERE review_key=?", args) == 0) {
            db.exec("INSERT INTO goal_review_runs(run_id,state,input_hash,context_json,pending_json,request_sent,external_attempted,message,retry_at,started_at,review_key) VALUES(?,?,?,?,NULL,0,?,'',NULL,?,?)", args)
        }
        input
    }

    fun prepare(key: GoalReviewKey, automatic: Boolean, service: String, model: String): GoalAnalysisInput? =
        claim(key, automatic, service, model)

    /** The sole paid-boundary transition; sticky external_attempted is never cleared. */
    fun markSent(runId: String): Unit = transaction { db ->
        val row = runById(db, runId) ?: throw IllegalArgumentException("目标回顾任务已失效")
        require(row.state == GoalRunState.PREPARING && !row.requestSent) { "目标回顾任务不能发送" }
        val context = contextByRun(db, runId)
        validatePublishLifetime(db, context, requirePermission = true)
        require(db.exec("UPDATE goal_review_runs SET state=?,request_sent=1,external_attempted=1,message='正在分析' WHERE run_id=? AND state=? AND request_sent=0",
            listOf(GoalRunState.RUNNING.name, runId, GoalRunState.PREPARING.name)) == 1) { "目标回顾任务已变化" }
    }

    fun stage(runId: String, result: GeneratedGoalReview): Unit = transaction { db ->
        val row = runById(db, runId) ?: throw IllegalArgumentException("目标回顾任务已失效")
        require(row.state == GoalRunState.RUNNING && row.requestSent) { "目标回顾任务不能暂存结果" }
        val context = contextByRun(db, runId)
        val validated = GoalCodec.decodeResult(GoalCodec.encodeResult(result), inputForValidation(context))
        require(db.exec("UPDATE goal_review_runs SET state=?,pending_json=?,message='AI结果待保存',retry_at=NULL WHERE run_id=? AND state=?",
            listOf(GoalRunState.RESULT_READY.name, GoalCodec.encodeResultString(validated), runId, GoalRunState.RUNNING.name)) == 1) { "目标回顾任务已变化" }
    }

    fun context(runId: String): GoalReviewContext? = transaction { db ->
        runById(db, runId)?.let { contextByRun(db, runId) }
    }

    fun context(key: GoalReviewKey): GoalReviewContext? = transaction { db ->
        run(db, GoalCodec.validateReviewKey(key))?.let { contextByRun(db, it.runId) }
    }

    fun publish(context: GoalReviewContext, result: GeneratedGoalReview): GoalReviewPayload = transaction { db ->
        val current = runById(db, context.runId) ?: throw IllegalArgumentException("目标回顾任务已失效")
        require(current.key == context.key && current.state == GoalRunState.RESULT_READY) { "目标回顾任务不能发布" }
        validatePublishLifetime(db, context, requirePermission = true)
        val validated = GoalCodec.decodeResult(GoalCodec.encodeResult(result), inputForValidation(context))
        val payload = GoalCodec.decodeReview(GoalCodec.encodeReview(GoalReviewPayload(
            key = context.key,
            goalSnapshot = context.goalSnapshot,
            goalHeads = context.goalHeads,
            confirmationSnapshot = context.confirmationSnapshot,
            inputHash = context.inputHash,
            dependencies = context.dependencies,
            coverage = context.coverage,
            direction = validated.direction,
            assessment = validated.assessment,
            observations = validated.observations,
            achievements = validated.achievements,
            recurringProblems = validated.recurringProblems,
            positiveFeedback = validated.positiveFeedback,
            milestoneSuggestions = validated.milestoneSuggestions,
            nextActions = validated.nextActions,
            unknowns = validated.unknowns,
            generatedAt = clock.instant().toString(),
            service = serviceName(context.service),
            model = context.model,
        )))
        sync.writeLocalHead(db, REVIEW_KIND, context.key.encoded, GoalCodec.encodeReview(payload),
            observedBase = context.baseReviewHeads, intentId = context.runId, resultKey = context.key.encoded)
        projectReview(db, sync.readEntity(db, REVIEW_KIND, context.key.encoded))
        require(db.exec("UPDATE goal_review_runs SET state=?,pending_json=NULL,message='已保存到本机',retry_at=NULL WHERE run_id=? AND state=?",
            listOf(GoalRunState.SUCCEEDED.name, context.runId, GoalRunState.RESULT_READY.name)) == 1) { "目标回顾任务已变化" }
        payload
    }

    fun publishPending(key: GoalReviewKey): GoalReviewPayload? {
        val pending = transaction { db ->
            val row = run(db, GoalCodec.validateReviewKey(key)) ?: return@transaction null
            if (row.state != GoalRunState.RESULT_READY) return@transaction null
            val context = contextByRun(db, row.runId)
            val result = db.query("SELECT pending_json FROM goal_review_runs WHERE run_id=?", listOf(row.runId)).use { c ->
                require(c.next() && !c.isNull(0)) { "待保存AI结果缺失" }
                GoalCodec.decodeResultString(c.string(0), inputForValidation(context))
            }
            context to result
        } ?: return null
        return publish(pending.first, pending.second)
    }

    fun finishRun(runId: String, state: GoalRunState, message: String, retryAt: Long? = null): Unit = transaction { db ->
        require(state in setOf(GoalRunState.FAILED, GoalRunState.UNKNOWN, GoalRunState.CANCELLED, GoalRunState.INVALIDATED)) { "非法终态" }
        db.exec("UPDATE goal_review_runs SET state=?,pending_json=NULL,message=?,retry_at=? WHERE run_id=? AND state NOT IN (?,?)",
            listOf(state.name, message, retryAt, runId, GoalRunState.SUCCEEDED.name, GoalRunState.CANCELLED.name))
    }

    fun cancelRun(key: GoalReviewKey, message: String): Unit = transaction { db ->
        db.exec("UPDATE goal_review_runs SET state=?,pending_json=NULL,message=?,retry_at=NULL WHERE review_key=? AND state NOT IN (?,?)",
            listOf(GoalRunState.CANCELLED.name, message, GoalCodec.validateReviewKey(key).encoded, GoalRunState.SUCCEEDED.name, GoalRunState.CANCELLED.name))
    }

    /** Called inside the same local/imported purge transaction; successful history is retained. */
    fun invalidatePendingForRecord(db: SqlDatabase, recordId: String) {
        val affected = db.query("SELECT run_id,context_json FROM goal_review_runs WHERE state IN ('PREPARING','RUNNING','RESULT_READY')").use { cursor ->
            buildList {
                while (cursor.next()) {
                    val context = GoalCodec.decodeContextString(cursor.string(1))
                    if (context.dependencies.any { recordId in it.ref.recordIds || recordId in it.sourceVersions }) add(cursor.string(0))
                }
            }
        }
        affected.forEach { runId ->
            db.exec("UPDATE goal_review_runs SET state='INVALIDATED',pending_json=NULL,retry_at=NULL,message='原始记录已彻删，未发布结果已失效' WHERE run_id=?", listOf(runId))
        }
    }

    fun recoverInterrupted(): Unit {
        val pendingKeys = transaction { db ->
            db.exec("UPDATE goal_review_runs SET state=?,message='上次请求已发送但结果未知，请确认可能重复计费后再试',retry_at=NULL WHERE state=? AND request_sent=1",
                listOf(GoalRunState.UNKNOWN.name, GoalRunState.RUNNING.name))
            db.exec("UPDATE goal_review_runs SET state=?,message='上次任务在发送前中断，可重新尝试',retry_at=NULL WHERE state=? AND request_sent=0",
                listOf(GoalRunState.FAILED.name, GoalRunState.PREPARING.name))
            db.query("SELECT review_key FROM goal_review_runs WHERE state=? AND pending_json IS NOT NULL ORDER BY review_key", listOf(GoalRunState.RESULT_READY.name))
                .use { c -> buildList { while (c.next()) add(GoalReviewKey.decode(c.string(0))) } }
        }
        pendingKeys.forEach { key ->
            try {
                publishPending(key)
            } catch (failure: IllegalArgumentException) {
                run(key)?.let { finishRun(it.runId, GoalRunState.INVALIDATED, failure.message ?: "来源已变化，结果未发布") }
            } catch (_: Exception) {
                // Keep durable RESULT_READY for an explicit local-save retry.
            }
        }
    }

    fun known(key: GoalReviewKey): GoalKnownReview? = transaction { db ->
        db.query("SELECT state,peer_database_id FROM goal_review_known WHERE review_key=?", listOf(GoalCodec.validateReviewKey(key).encoded)).use { c ->
            if (!c.next()) null else GoalKnownReview(key, c.string(0), c.string(1))
        }
    }

    fun rememberKnown(key: GoalReviewKey, state: String, peerDatabaseId: String): Unit = transaction { db ->
        require(state in setOf("RUNNING", "UNKNOWN", "FINISHED")) { "未知协调状态" }
        require(peerDatabaseId.isNotBlank()) { "对端数据库身份不能为空" }
        val encoded = GoalCodec.validateReviewKey(key).encoded
        val args = listOf(state, peerDatabaseId, encoded)
        if (db.exec("UPDATE goal_review_known SET state=?,peer_database_id=? WHERE review_key=?", args) == 0)
            db.exec("INSERT INTO goal_review_known(state,peer_database_id,review_key) VALUES(?,?,?)", args)
    }

    fun clearKnownFinished(key: GoalReviewKey, peerDatabaseId: String): Unit = transaction { db ->
        val normalized = GoalCodec.validateReviewKey(key)
        val review = sync.readEntity(db, REVIEW_KIND, normalized.encoded)
        require(!review.unresolved && review.heads.isNotEmpty()) { "尚未同步取得完整目标回顾" }
        db.exec("DELETE FROM goal_review_known WHERE review_key=? AND peer_database_id=? AND state='FINISHED'",
            listOf(normalized.encoded, peerDatabaseId))
    }

    /** Projects imported causal entities without ever selecting among conflicting candidates. */
    fun projectMerged(db: SqlDatabase, changed: Set<Pair<String, String>>, purged: Set<String> = emptySet()) {
        changed.forEach { (kind, key) ->
            if (kind !in setOf(GOAL_KIND, CONFIRMATION_KIND, REVIEW_KIND)) return@forEach
            val state = sync.readEntity(db, kind, key)
            state.heads.forEach { GoalCodec.validatePayload(kind, key, it.payload) }
            when (kind) {
                GOAL_KIND -> projectGoal(db, state)
                CONFIRMATION_KIND -> projectConfirmation(db, state)
                REVIEW_KIND -> projectReview(db, state)
            }
        }
    }

    fun resolveGoal(goalId: String, payload: JsonElement, displayedHeads: Set<String>, intentId: String): Unit =
        resolvePayload(GOAL_KIND, goalId, payload, displayedHeads, intentId, ::projectGoal)

    fun resolveConfirmation(goalId: String, milestoneId: String, payload: JsonElement, displayedHeads: Set<String>, intentId: String): Unit =
        resolvePayload(CONFIRMATION_KIND, confirmationKey(goalId, milestoneId), payload, displayedHeads, intentId, ::projectConfirmation)

    fun resolveReview(key: GoalReviewKey, chosenHeadId: String, displayedHeads: Set<String>, intentId: String): Unit = transaction { db ->
        if (intentCommitted(db, intentId)) return@transaction
        val state = sync.readEntity(db, REVIEW_KIND, key.encoded)
        require(displayedHeads.isNotEmpty() && state.heads.map { it.id }.containsAll(displayedHeads)) { "目标回顾候选已变化，请重新查看" }
        val chosen = state.heads.singleOrNull { it.id == chosenHeadId && it.id in displayedHeads }
            ?: throw IllegalArgumentException("目标回顾候选已变化，请重新查看")
        GoalCodec.validatePayload(REVIEW_KIND, key.encoded, chosen.payload)
        sync.writeLocalHead(db, REVIEW_KIND, key.encoded, chosen.payload, chosen.payloadHash, displayedHeads, intentId, resultKey = key.encoded)
        projectReview(db, sync.readEntity(db, REVIEW_KIND, key.encoded))
    }

    private fun resolvePayload(kind: String, key: String, payload: JsonElement, displayedHeads: Set<String>, intentId: String, projector: (SqlDatabase, EntityState) -> Unit): Unit = transaction { db ->
        if (intentCommitted(db, intentId)) return@transaction
        val state = sync.readEntity(db, kind, key)
        require(displayedHeads.isNotEmpty() && state.heads.map { it.id }.containsAll(displayedHeads)) { "目标候选已变化，请重新查看" }
        val normalized = GoalCodec.validatePayload(kind, key, payload)
        sync.writeLocalHead(db, kind, key, normalized, observedBase = displayedHeads, intentId = intentId, resultKey = key)
        projector(db, sync.readEntity(db, kind, key))
    }

    private fun snapshotInTransaction(db: SqlDatabase, key: GoalReviewKey): GoalAnalysisInput {
        val goalState = sync.readEntity(db, GOAL_KIND, key.goalId)
        if (goalState.unresolved) throw UnresolvedConflict(GOAL_KIND, key.goalId)
        val head = goalState.heads.singleOrNull() ?: throw IllegalArgumentException("目标不存在")
        val goal = GoalCodec.decodeGoal(head.payload)
        require(!goal.archived) { "已归档目标不能生成回顾" }
        val confirmations = goal.milestones.mapNotNull { milestone ->
            val entityKey = confirmationKey(goal.id, milestone.id)
            val state = sync.readEntity(db, CONFIRMATION_KIND, entityKey)
            if (state.unresolved) throw UnresolvedConflict(CONFIRMATION_KIND, entityKey)
            state.heads.firstOrNull()?.let {
                GoalConfirmationSnapshot(GoalCodec.decodeConfirmation(it.payload), state.heads.mapTo(linkedSetOf()) { value -> value.id })
            }
        }
        return GoalEvidence.snapshot(db, goal, goalState.heads.mapTo(linkedSetOf()) { it.id }, confirmations, key, clock)
    }

    private fun permission(db: SqlDatabase, goalId: String): GoalAnalysisPermission? =
        db.query("SELECT spec_hash,endpoint,model,send_allowed,automatic_allowed FROM goal_analysis_permissions WHERE goal_id=?", listOf(goalId)).use { c ->
            if (!c.next()) null else GoalAnalysisPermission(goalId, c.string(0), c.string(1), c.string(2), c.long(3) != 0L, c.long(4) != 0L)
        }

    private fun requirePermission(db: SqlDatabase, goal: GoalSpec, endpoint: String, model: String, automatic: Boolean): GoalAnalysisPermission {
        val value = permission(db, goal.id) ?: throw IllegalArgumentException("请先授权此目标发送给AI服务")
        require(value.sendAllowed) { "此目标未允许发送给AI服务" }
        require(!automatic || value.automaticAllowed) { "此目标未允许自动周期分析" }
        require(value.specHash == GoalCodec.specHash(goal)) { "目标内容已变化，请重新授权" }
        require(value.endpoint == ChatCompletionClient.normalizeEndpoint(endpoint).toString() && value.model == model.trim()) {
            "AI服务或模型已变化，请重新授权"
        }
        return value
    }

    private fun run(db: SqlDatabase, key: GoalReviewKey): GoalRunInfo? =
        db.query("SELECT run_id,state,input_hash,request_sent,external_attempted,message,retry_at,started_at FROM goal_review_runs WHERE review_key=?", listOf(key.encoded)).use { c ->
            if (!c.next()) null else GoalRunInfo(
                key, c.string(0), GoalRunState.valueOf(c.string(1)), c.string(2), c.long(3) != 0L,
                c.long(4) != 0L, c.string(5), if (c.isNull(6)) null else c.long(6), c.long(7),
            )
        }

    private fun runById(db: SqlDatabase, runId: String): GoalRunInfo? =
        db.query("SELECT review_key,run_id,state,input_hash,request_sent,external_attempted,message,retry_at,started_at FROM goal_review_runs WHERE run_id=?", listOf(runId)).use { c ->
            if (!c.next()) null else GoalRunInfo(
                GoalReviewKey.decode(c.string(0)), c.string(1), GoalRunState.valueOf(c.string(2)), c.string(3), c.long(4) != 0L,
                c.long(5) != 0L, c.string(6), if (c.isNull(7)) null else c.long(7), c.long(8),
            )
        }

    private fun contextByRun(db: SqlDatabase, runId: String): GoalReviewContext =
        db.query("SELECT context_json FROM goal_review_runs WHERE run_id=?", listOf(runId)).use { c ->
            require(c.next()) { "目标回顾任务不存在" }
            GoalCodec.decodeContextString(c.string(0))
        }

    private fun context(input: GoalAnalysisInput, endpoint: String, model: String): GoalReviewContext = GoalReviewContext(
        runId = requireNotNull(input.runId), key = input.key, goalSnapshot = input.goal, goalHeads = input.goalHeads,
        confirmationSnapshot = input.confirmations, inputHash = input.hash,
        dependencies = input.evidence.map { it.dependency }.distinctBy { it.ref.id }, coverage = input.coverage,
        baseReviewHeads = input.baseReviewHeads, service = endpoint, model = model,
    )

    private fun inputForValidation(context: GoalReviewContext) = GoalAnalysisInput(
        context.key, context.goalSnapshot, context.goalHeads, context.confirmationSnapshot,
        context.dependencies.map { GoalInputEvidence(it, "") }, context.coverage, context.inputHash,
        context.baseReviewHeads, context.runId,
    )

    private fun validatePublishLifetime(db: SqlDatabase, context: GoalReviewContext, requirePermission: Boolean) {
        val goalState = sync.readEntity(db, GOAL_KIND, context.key.goalId)
        require(!goalState.unresolved && goalState.heads.mapTo(linkedSetOf()) { it.id } == context.goalHeads) { "目标定义已变化，结果未发布" }
        val goal = goalState.heads.singleOrNull()?.payload?.let(GoalCodec::decodeGoal)
            ?: throw IllegalArgumentException("目标已不可用，结果未发布")
        require(goal == context.goalSnapshot && !goal.archived) { "目标定义或归档状态已变化，结果未发布" }
        if (requirePermission) requirePermission(db, goal, context.service, context.model, automatic = false)
        val from = LocalDate.parse(context.key.fromDate)
        val through = LocalDate.parse(context.key.throughDate)
        context.dependencies.flatMapTo(linkedSetOf()) { it.ref.recordIds }.forEach { recordId ->
            require(!sync.isPurged(db, recordId)) { "分析来源已彻底删除，结果未发布" }
            val state = sync.readEntity(db, "record", recordId)
            if (state.unresolved) throw UnresolvedConflict("record", recordId)
            val payload = state.heads.singleOrNull()?.payload?.jsonObject
                ?: throw IllegalArgumentException("分析来源已不可用，结果未发布")
            val deleted = payload["deleted"]?.jsonPrimitive?.contentOrNull
            require(deleted.isNullOrBlank()) { "分析来源已删除，结果未发布" }
            val local = payload["local"]?.jsonPrimitive?.contentOrNull?.take(10)
                ?: throw IllegalArgumentException("分析来源日期无效，结果未发布")
            val date = LocalDate.parse(local)
            require(!date.isBefore(from) && !date.isAfter(through)) { "分析来源已移出本周期，结果未发布" }
        }
    }

    private fun summary(db: SqlDatabase, goalId: String): GoalSummary {
        val state = sync.readEntity(db, GOAL_KIND, goalId)
        val spec = if (state.unresolved || state.heads.isEmpty()) null else GoalCodec.decodeGoal(state.heads.first().payload)
        if (spec == null) return GoalSummary(null, state.heads.mapTo(linkedSetOf()) { it.id }, 0, 0, null, state.unresolved)
        var conflicted = false
        var completed = 0
        spec.milestones.forEach { milestone ->
            val confirmation = sync.readEntity(db, CONFIRMATION_KIND, confirmationKey(goalId, milestone.id))
            if (confirmation.unresolved) conflicted = true
            else confirmation.heads.firstOrNull()?.payload?.let(GoalCodec::decodeConfirmation)?.let { value ->
                if (value.confirmed && value.criterionHash == GoalCodec.criterionHash(milestone.criterion)) completed++
            }
        }
        val total = spec.milestones.size
        return GoalSummary(spec, state.heads.mapTo(linkedSetOf()) { it.id }, completed, total,
            if (conflicted || total == 0) null else (completed * 100 / total), conflicted)
    }

    private fun projectGoal(db: SqlDatabase, state: EntityState) {
        if (state.unresolved || state.heads.isEmpty()) {
            db.exec("DELETE FROM goals WHERE id=?", listOf(state.key))
            return
        }
        val payload = state.heads.first().payload
        GoalCodec.validatePayload(GOAL_KIND, state.key, payload)
        upsert(db, "goals", "id", state.key, GoalCodec.encodeGoal(GoalCodec.decodeGoal(payload)))
        val current = GoalCodec.decodeGoal(payload)
        db.exec("UPDATE goal_analysis_permissions SET automatic_allowed=0 WHERE goal_id=? AND spec_hash<>?", listOf(state.key, GoalCodec.specHash(current)))
    }

    private fun projectConfirmation(db: SqlDatabase, state: EntityState) {
        val separator = state.key.indexOf('/')
        require(separator > 0 && separator < state.key.lastIndex) { "里程碑确认实体键无效" }
        val goalId = state.key.substring(0, separator)
        val milestoneId = state.key.substring(separator + 1)
        if (state.unresolved || state.heads.isEmpty()) {
            db.exec("DELETE FROM goal_confirmations WHERE goal_id=? AND milestone_id=?", listOf(goalId, milestoneId))
            return
        }
        val payload = state.heads.first().payload
        GoalCodec.validatePayload(CONFIRMATION_KIND, state.key, payload)
        val canonical = SyncCodec.canonical(GoalCodec.encodeConfirmation(GoalCodec.decodeConfirmation(payload)))
        val args = listOf(canonical, goalId, milestoneId)
        if (db.exec("UPDATE goal_confirmations SET payload_json=? WHERE goal_id=? AND milestone_id=?", args) == 0)
            db.exec("INSERT INTO goal_confirmations(payload_json,goal_id,milestone_id) VALUES(?,?,?)", args)
    }

    private fun projectReview(db: SqlDatabase, state: EntityState) {
        if (state.unresolved || state.heads.isEmpty()) {
            db.exec("DELETE FROM goal_reviews WHERE review_key=?", listOf(state.key))
            return
        }
        val payload = state.heads.first().payload
        GoalCodec.validatePayload(REVIEW_KIND, state.key, payload)
        val value = GoalCodec.decodeReview(payload)
        val canonical = SyncCodec.canonical(GoalCodec.encodeReview(value))
        val args = listOf(value.key.goalId, value.key.fromDate, value.key.throughDate, canonical, state.key)
        if (db.exec("UPDATE goal_reviews SET goal_id=?,from_date=?,through_date=?,payload_json=? WHERE review_key=?", args) == 0)
            db.exec("INSERT INTO goal_reviews(goal_id,from_date,through_date,payload_json,review_key) VALUES(?,?,?,?,?)", args)
    }

    private fun validateEvidence(db: SqlDatabase, ref: GoalEvidenceRef) = GoalEvidence.validateRef(db, ref)

    private fun goalIds(db: SqlDatabase): List<String> {
        val ids = linkedSetOf<String>()
        db.query("SELECT DISTINCT entity_key FROM sync_heads WHERE kind=? ORDER BY entity_key", listOf(GOAL_KIND)).use { c -> while (c.next()) ids += c.string(0) }
        entityKeys(db, CONFIRMATION_KIND).forEach { ids += it.substringBefore('/') }
        entityKeys(db, REVIEW_KIND).forEach { key ->
            sync.readEntity(db, REVIEW_KIND, key).heads.firstOrNull()?.payload?.let { runCatching { GoalCodec.decodeReview(it).key.goalId }.getOrNull() }?.let(ids::add)
        }
        return ids.toList()
    }

    private fun entityKeys(db: SqlDatabase, kind: String, prefix: String? = null): List<String> {
        val sql = if (prefix == null) "SELECT DISTINCT entity_key FROM sync_heads WHERE kind=? ORDER BY entity_key"
            else "SELECT DISTINCT entity_key FROM sync_heads WHERE kind=? AND entity_key LIKE ? ORDER BY entity_key"
        val args = if (prefix == null) listOf(kind) else listOf(kind, "$prefix%")
        return db.query(sql, args).use { c -> buildList { while (c.next()) add(c.string(0)) } }
    }

    private fun upsert(db: SqlDatabase, table: String, keyColumn: String, key: String, payload: JsonElement) {
        val canonical = SyncCodec.canonical(payload)
        if (db.exec("UPDATE $table SET payload_json=? WHERE $keyColumn=?", listOf(canonical, key)) == 0)
            db.exec("INSERT INTO $table(payload_json,$keyColumn) VALUES(?,?)", listOf(canonical, key))
    }

    private fun disableAutomatic(db: SqlDatabase, goalId: String) {
        db.exec("UPDATE goal_analysis_permissions SET automatic_allowed=0 WHERE goal_id=?", listOf(goalId))
    }

    private fun intentCommitted(db: SqlDatabase, intentId: String): Boolean {
        require(intentId.isNotBlank()) { "intentId不能为空" }
        return db.query("SELECT 1 FROM sync_intents WHERE intent_id=?", listOf(intentId)).use { it.next() }
    }

    private fun serviceName(endpoint: String): String = URI(endpoint).let {
        "${it.scheme.lowercase()}://${it.host.lowercase()}:${if (it.port < 0) 443 else it.port}"
    }

    private fun <T> transaction(block: (SqlDatabase) -> T): T = try {
        database.transaction(block)
    } catch (failure: SqlFailure) {
        throw IllegalStateException("目标数据操作失败，原有内容未改变，请重试", failure)
    }

    companion object {
        const val GOAL_KIND = "goal"
        const val CONFIRMATION_KIND = "goal-confirmation"
        const val REVIEW_KIND = "goal-review"
        fun confirmationKey(goalId: String, milestoneId: String) = "$goalId/$milestoneId"
        fun reviewKey(key: GoalReviewKey) = "${key.goalId}/${key.fromDate}/${key.throughDate}"
    }
}
