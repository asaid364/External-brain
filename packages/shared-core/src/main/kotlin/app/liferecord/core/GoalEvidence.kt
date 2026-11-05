package app.liferecord.core

import java.time.Clock
import java.time.LocalDate

/** Builds and validates the transient evidence used by a goal review. */
object GoalEvidence {
    const val CURRENT = "CURRENT"
    const val STALE = "STALE"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val CONFLICT = "CONFLICT"

    fun snapshot(
        database: SqlDatabase,
        goal: GoalSpec,
        goalHeads: Set<String>,
        confirmations: List<GoalConfirmationSnapshot>,
        key: GoalReviewKey,
        clock: Clock,
    ): GoalAnalysisInput {
        val normalizedGoal = GoalCodec.validateGoal(goal)
        val normalizedKey = GoalCodec.validateReviewKey(key)
        require(normalizedKey.goalId == normalizedGoal.id) { "回顾窗口属于其他目标" }
        require(!normalizedGoal.archived) { "已归档目标不能生成回顾" }
        require(GoalReviewPeriods.contains(normalizedGoal, normalizedKey)) { "回顾窗口不符合当前目标周期" }
        val normalizedConfirmations = confirmations.map { snapshot ->
            val value = GoalCodec.validateConfirmation(snapshot.value)
            require(value.goalId == normalizedGoal.id) { "确认快照属于其他目标" }
            require(snapshot.heads.isNotEmpty() && snapshot.heads.none { it.isBlank() }) { "确认版本不能为空" }
            GoalConfirmationSnapshot(value, snapshot.heads.toSortedSet().toCollection(linkedSetOf()))
        }
        require(normalizedConfirmations.map { it.value.milestoneId }.toSet().size == normalizedConfirmations.size) {
            "同一里程碑不能有多个确认快照"
        }
        val today = LocalDate.now(clock.withZone(java.time.ZoneId.of(normalizedGoal.reviewZone)))
        require(LocalDate.parse(normalizedKey.throughDate).isBefore(today)) { "当天尚未结束，不能生成回顾" }
        val canonicalConfirmations = normalizedConfirmations.sortedBy { it.value.milestoneId }
        return database.transaction { db -> buildSnapshot(db, normalizedGoal, goalHeads, canonicalConfirmations, normalizedKey) }
    }

    fun hash(input: GoalAnalysisInput): String {
        val fields = mutableListOf("goal-analysis-input-v1")
        val key = input.key
        val goal = input.goal
        fields += listOf("key", key.goalId, key.fromDate, key.throughDate)
        fields += listOf(
            "goal", goal.id, goal.title, goal.desiredResult, goal.startDate, goal.endDate,
            goal.reviewZone, goal.cadenceDays.toString(),
        )
        goal.milestones.forEachIndexed { index, milestone ->
            fields += listOf("milestone", index.toString(), milestone.id, milestone.criterion)
        }
        input.goalHeads.sorted().forEach { fields += listOf("goal-head", it) }
        input.confirmations.sortedBy { it.value.milestoneId }.forEach { snapshot ->
            val value = snapshot.value
            fields += listOf(
                "confirmation", value.goalId, value.milestoneId, value.criterionHash,
                value.criterionSnapshot, value.confirmed.toString(), value.note, value.confirmedAt,
            )
            value.evidence.forEachIndexed { evidenceIndex, ref ->
                fields += listOf("confirmation-ref", evidenceIndex.toString())
                appendRef(fields, ref)
            }
            snapshot.heads.sorted().forEach { fields += listOf("confirmation-head", it) }
        }
        input.evidence.sortedBy { it.dependency.ref.id }.forEach { evidence ->
            fields += "evidence"
            appendRef(fields, evidence.dependency.ref)
            evidence.dependency.heads.sorted().forEach { fields += listOf("evidence-head", it) }
            evidence.dependency.sourceVersions.toSortedMap().forEach { (recordId, version) ->
                fields += listOf("source-version", recordId, version)
            }
        }
        input.coverage.sortedBy { it.date }.forEach { day ->
            fields += listOf(
                "coverage", day.date, day.recordCount.toString(), day.reportStatus,
                day.unanswered.toString(), day.skipped.toString(), day.inapplicable.toString(),
            )
        }
        return EvidenceVersion.nulDigest(fields)
    }

    /** Verifies that a reference still resolves to the exact current causal payload. */
    fun validateRef(db: SqlDatabase, ref: GoalEvidenceRef) {
        when (dependencyState(db, GoalEvidenceDependency(ref, currentHeads(db, ref), capturedSourceVersions(db, ref)))) {
            CURRENT -> Unit
            CONFLICT -> throw UnresolvedConflict(entityKind(ref), ref.entityKey)
            STALE -> throw IllegalArgumentException("关联证据已变化，请重新核对")
            else -> throw IllegalArgumentException("关联证据已不可用")
        }
    }

    /** Derives validity from current causal state; historical review text is never rewritten. */
    fun status(review: GoalReviewPayload, db: SqlDatabase): String {
        val sync = SyncRepository(db)
        val goalState = sync.readEntity(db, GoalRepository.GOAL_KIND, review.key.goalId)
        if (goalState.unresolved) return CONFLICT
        if (goalState.heads.isEmpty()) return UNAVAILABLE
        val currentGoal = GoalCodec.decodeGoal(goalState.heads.first().payload)
        val currentGoalHeads = goalState.heads.mapTo(linkedSetOf()) { it.id }
        var stale = currentGoalHeads != review.goalHeads || currentGoal != review.goalSnapshot

        val confirmations = currentGoal.milestones.mapNotNull { milestone ->
            val state = sync.readEntity(db, GoalRepository.CONFIRMATION_KIND,
                GoalRepository.confirmationKey(currentGoal.id, milestone.id))
            if (state.unresolved) return CONFLICT
            state.heads.firstOrNull()?.let { head ->
                GoalConfirmationSnapshot(
                    GoalCodec.decodeConfirmation(head.payload),
                    state.heads.mapTo(linkedSetOf()) { it.id },
                )
            }
        }.sortedBy { it.value.milestoneId }
        val capturedConfirmations = review.confirmationSnapshot.sortedBy { it.value.milestoneId }
        stale = stale || confirmations != capturedConfirmations

        var unavailable = false
        for (dependency in review.dependencies) {
            when (dependencyState(db, dependency)) {
                CONFLICT -> return CONFLICT
                UNAVAILABLE -> unavailable = true
                STALE -> stale = true
            }
        }
        if (unavailable) return UNAVAILABLE
        if (stale) return STALE
        if (!GoalReviewPeriods.contains(currentGoal, review.key)) return STALE
        return try {
            val current = buildSnapshot(db, currentGoal, currentGoalHeads, confirmations, review.key)
            if (current.hash == review.inputHash) CURRENT else STALE
        } catch (_: UnresolvedConflict) {
            CONFLICT
        } catch (_: EvidenceUnavailable) {
            UNAVAILABLE
        } catch (_: IllegalArgumentException) {
            STALE
        }
    }

    internal fun buildSnapshot(
        database: SqlDatabase,
        goal: GoalSpec,
        goalHeads: Set<String>,
        confirmations: List<GoalConfirmationSnapshot>,
        key: GoalReviewKey,
    ): GoalAnalysisInput {
        require(goalHeads.isNotEmpty() && goalHeads.none { it.isBlank() }) { "目标版本不能为空" }
        val reports = ReportRepository(database)
        val days = buildList {
            var date = LocalDate.parse(key.fromDate)
            val through = LocalDate.parse(key.throughDate)
            while (!date.isAfter(through)) {
                add(reports.analysisDay(database, date.toString()))
                date = date.plusDays(1)
            }
        }
        val evidence = days.flatMap { it.records + it.answers + it.reportParagraphs }
        val input = GoalAnalysisInput(
            key = key,
            goal = goal,
            goalHeads = goalHeads.toSortedSet().toCollection(linkedSetOf()),
            confirmations = confirmations,
            evidence = evidence,
            coverage = days.map { it.coverage },
            hash = "",
            baseReviewHeads = SyncRepository(database).currentHeadIds(database, GoalRepository.REVIEW_KIND, key.encoded),
        )
        return input.copy(hash = hash(input))
    }

    private fun dependencyState(db: SqlDatabase, dependency: GoalEvidenceDependency): String {
        val ref = dependency.ref
        val sync = SyncRepository(db)
        var unavailable = false
        var stale = false
        for ((recordId, capturedVersion) in dependency.sourceVersions) {
            if (sync.isPurged(db, recordId)) {
                unavailable = true
                continue
            }
            val state = sync.readEntity(db, "record", recordId)
            if (state.unresolved) return CONFLICT
            val current = currentRecordVersion(db, recordId, state)
            if (current == null) {
                unavailable = true
                continue
            }
            if (current != capturedVersion) {
                val capturedLife = EvidenceVersion.parseV3(capturedVersion)?.lifeDigest
                val currentLife = EvidenceVersion.parseV3(current)?.lifeDigest
                if (capturedLife == null || currentLife == null || capturedLife != currentLife) unavailable = true
                else stale = true
            }
        }
        val state = sync.readEntity(db, entityKind(ref), ref.entityKey)
        if (state.unresolved) return CONFLICT
        val head = state.heads.firstOrNull() ?: return UNAVAILABLE
        if (state.heads.mapTo(linkedSetOf()) { it.id } != dependency.heads ||
            ref.kind != GoalEvidenceKind.RECORD && head.payloadHash != ref.version) stale = true
        val payloadState = try {
            when (ref.kind) {
                GoalEvidenceKind.RECORD -> {
                    if (ref.id != "r:${ref.entityKey}" || ref.recordIds != setOf(ref.entityKey)) STALE
                    else when (currentRecordVersion(db, ref.entityKey, state)) {
                        null -> UNAVAILABLE
                        ref.version -> CURRENT
                        else -> STALE
                    }
                }
                GoalEvidenceKind.ANSWER -> {
                    val value = ReportPayloadCodec.decodeAnswer(head.payload)
                    if (ref.id != "a:${ref.entityKey}" || value.answer.isBlank() || value.skipped ||
                        value.recordIds.toSet() != ref.recordIds) STALE else CURRENT
                }
                GoalEvidenceKind.REPORT -> run {
                    val position = ref.id.removePrefix("d:${ref.entityKey}/").toIntOrNull()
                        ?: return@run STALE
                    val paragraph = ReportPayloadCodec.decodeReport(head.payload).report?.paragraphs?.getOrNull(position)
                        ?: return@run UNAVAILABLE
                    var nestedUnavailable = false
                    val expected = linkedSetOf<String>().apply {
                        addAll(paragraph.recordIds)
                        paragraph.answerIds.forEach { answerId ->
                            val answerState = sync.readEntity(db, ReportRepository.ANSWER_KIND, "${ref.entityKey}/$answerId")
                            if (answerState.unresolved) return CONFLICT
                            val answerHead = answerState.heads.firstOrNull()
                            if (answerHead == null) nestedUnavailable = true
                            else addAll(ReportPayloadCodec.decodeAnswer(answerHead.payload).recordIds)
                        }
                    }
                    when {
                        nestedUnavailable -> UNAVAILABLE
                        expected == ref.recordIds -> CURRENT
                        else -> STALE
                    }
                }
            }
        } catch (_: IllegalArgumentException) {
            STALE
        }
        return when {
            payloadState == CONFLICT -> CONFLICT
            unavailable || payloadState == UNAVAILABLE -> UNAVAILABLE
            stale || payloadState == STALE -> STALE
            else -> CURRENT
        }
    }

    private fun currentHeads(db: SqlDatabase, ref: GoalEvidenceRef): Set<String> =
        SyncRepository(db).currentHeadIds(db, entityKind(ref), ref.entityKey)

    private fun capturedSourceVersions(db: SqlDatabase, ref: GoalEvidenceRef): Map<String, String> {
        val sync = SyncRepository(db)
        val state = sync.readEntity(db, entityKind(ref), ref.entityKey)
        if (state.unresolved) throw UnresolvedConflict(entityKind(ref), ref.entityKey)
        val head = state.heads.firstOrNull() ?: throw EvidenceUnavailable()
        return when (ref.kind) {
            GoalEvidenceKind.RECORD -> mapOf(ref.entityKey to ref.version)
            GoalEvidenceKind.ANSWER -> ReportPayloadCodec.decodeAnswer(head.payload).sourceVersions
                .filterKeys { it in ref.recordIds }
            GoalEvidenceKind.REPORT -> {
                val report = ReportPayloadCodec.decodeReport(head.payload).report ?: throw EvidenceUnavailable()
                report.sources.associate { it.id to it.version }.filterKeys { it in ref.recordIds }
            }
        }.also { require(it.keys == ref.recordIds) { "关联证据的底层来源不完整" } }
    }

    private fun currentRecordVersion(db: SqlDatabase, recordId: String, state: EntityState): String? {
        if (state.heads.isEmpty()) return null
        val record = db.query(
            "SELECT id,body,local,zone,offset,epoch,created,modified,deleted FROM records WHERE id=? AND deleted IS NULL",
            listOf(recordId),
        ).use { cursor ->
            if (!cursor.next()) null else Record(
                cursor.string(0), cursor.string(1),
                EventTime(cursor.string(2), cursor.string(3), cursor.string(4), cursor.long(5)),
                cursor.string(6), if (cursor.isNull(7)) null else cursor.string(7),
                if (cursor.isNull(8)) null else cursor.string(8),
            )
        } ?: return null
        return EvidenceVersion.source(record, state.heads.map { it.id }, SyncRepository(db).lifeEventIds(db, recordId))
    }

    private fun entityKind(ref: GoalEvidenceRef): String = when (ref.kind) {
        GoalEvidenceKind.RECORD -> "record"
        GoalEvidenceKind.ANSWER -> ReportRepository.ANSWER_KIND
        GoalEvidenceKind.REPORT -> ReportRepository.REPORT_KIND
    }

    private fun appendRef(fields: MutableList<String>, ref: GoalEvidenceRef) {
        fields += listOf("ref", ref.id, ref.kind.name, ref.entityKey, ref.version)
        ref.recordIds.sorted().forEach { fields += listOf("record-id", it) }
    }

    private class EvidenceUnavailable : IllegalArgumentException()
}
