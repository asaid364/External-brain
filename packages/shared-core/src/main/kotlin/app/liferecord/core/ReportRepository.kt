package app.liferecord.core

import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Durable report projections plus causal report/answer entities over the shared database. */
class ReportRepository(private val database: SqlDatabase) {
    private val sync = SyncRepository(database)

    private fun <T> transaction(block: (SqlDatabase) -> T): T = try {
        database.transaction(block)
    } catch (error: SqlFailure) {
        throw IllegalStateException("日报数据操作失败，原有内容未改变，请重试", error)
    }

    fun input(date: String): ReportInput = transaction { input(it, validDate(date)) }

    /** Captures one local-calendar day for goal analysis inside the caller's transaction. */
    internal fun analysisDay(db: SqlDatabase, date: String): GoalEvidenceDay {
        val normalized = validDate(date)
        assertNoRelatedConflicts(db, normalized)
        val active = activeRecords(db, normalized)
        val activeVersions = active.associate { it.record.id to it.version }
        val questions = loadQuestions(db, normalized, activeVersions)

        val records = active.map { versioned ->
            val state = sync.readEntity(db, "record", versioned.record.id)
            val ref = GoalEvidenceRef(
                id = "r:${versioned.record.id}",
                kind = GoalEvidenceKind.RECORD,
                entityKey = versioned.record.id,
                version = versioned.version,
                recordIds = setOf(versioned.record.id),
            )
            GoalInputEvidence(
                GoalEvidenceDependency(ref, state.heads.mapTo(linkedSetOf()) { it.id }, mapOf(versioned.record.id to versioned.version)),
                versioned.record.text,
            )
        }

        val answers = questions.asSequence()
            .filter { it.applicable && it.answer.isNotBlank() && !it.skipped }
            .map { question ->
                val key = answerKey(normalized, question.id)
                val state = sync.readEntity(db, ANSWER_KIND, key)
                if (state.unresolved) throw UnresolvedConflict(ANSWER_KIND, key)
                val head = state.heads.firstOrNull()
                    ?: throw IllegalStateException("已保存答案缺少可验证版本：$key")
                val payload = validateAnswerCandidate(normalized, question.id, head.payload)
                val sourceVersions = payload.sourceVersions.also {
                    require(it.keys == payload.recordIds.toSet()) { "适用答案来源版本不完整" }
                }
                val ref = GoalEvidenceRef(
                    id = "a:$key",
                    kind = GoalEvidenceKind.ANSWER,
                    entityKey = key,
                    version = head.payloadHash,
                    recordIds = payload.recordIds.toSet(),
                )
                GoalInputEvidence(
                    GoalEvidenceDependency(ref, state.heads.mapTo(linkedSetOf()) { it.id }, sourceVersions),
                    "问题：${payload.questionText}\n回答：${payload.answer}",
                )
            }.toList()

        val currentHash = EvidenceVersion.inputHash(
            active.map { ReportSource(it.record.id, it.version) },
            questions,
        )
        val reportState = sync.readEntity(db, REPORT_KIND, normalized)
        val report = loadReport(db, normalized)
        val reportStatus = when {
            report == null -> "MISSING"
            report.inputHash == currentHash -> "CURRENT"
            else -> "STALE"
        }
        val reportParagraphs = if (reportStatus != "CURRENT") emptyList() else {
            val head = reportState.heads.firstOrNull()
                ?: throw IllegalStateException("当前日报缺少可验证版本：$normalized")
            val answerSources = answers.associate { evidence ->
                evidence.dependency.ref.entityKey.substringAfter('/') to evidence.dependency.sourceVersions
            }
            val reportSources = requireNotNull(report).sources.associate { it.id to it.version }
            report.paragraphs.mapIndexed { position, paragraph ->
                val sourceVersions = linkedMapOf<String, String>()
                paragraph.recordIds.forEach { recordId ->
                    sourceVersions[recordId] = reportSources[recordId]
                        ?: throw IllegalStateException("当前日报引用的记录已不可用：$recordId")
                }
                paragraph.answerIds.forEach { answerId -> sourceVersions.putAll(answerSources[answerId].orEmpty()) }
                val recordIds = sourceVersions.keys.toCollection(linkedSetOf())
                val ref = GoalEvidenceRef(
                    id = "d:$normalized/$position",
                    kind = GoalEvidenceKind.REPORT,
                    entityKey = normalized,
                    version = head.payloadHash,
                    recordIds = recordIds,
                )
                GoalInputEvidence(
                    GoalEvidenceDependency(ref, reportState.heads.mapTo(linkedSetOf()) { it.id }, sourceVersions),
                    paragraph.text,
                )
            }
        }
        return GoalEvidenceDay(
            records = records,
            answers = answers,
            reportParagraphs = reportParagraphs,
            coverage = GoalCoverageDay(
                date = normalized,
                recordCount = records.size,
                reportStatus = reportStatus,
                unanswered = questions.count { it.applicable && it.answer.isBlank() && !it.skipped },
                skipped = questions.count { it.applicable && it.skipped },
                inapplicable = db.query("SELECT COUNT(*) FROM report_questions WHERE date=? AND applicable=0", listOf(normalized)).use {
                    check(it.next()); it.long(0).toInt()
                },
            ),
        )
    }

    fun day(date: String): ReportDay = transaction { db ->
        val normalized = validDate(date)
        val current = input(db, normalized)
        val report = loadReport(db, normalized)
        val run = db.query("SELECT running,message,retry_at FROM report_runs WHERE date=?", listOf(normalized)).use { cursor ->
            if (!cursor.next()) Triple(false, "", null)
            else Triple(cursor.long(0) != 0L, cursor.string(1), if (cursor.isNull(2)) null else cursor.long(2))
        }
        ReportDay(normalized, report, current.questions, current.records, report != null && report.inputHash != current.hash, run.second, run.first, run.third)
    }

    fun claim(date: String, automatic: Boolean = false): Boolean = transaction { db ->
        val normalized = validDate(date)
        val state = db.query("SELECT running,automatic_attempt FROM report_runs WHERE date=?", listOf(normalized)).use {
            if (it.next()) Pair(it.long(0) != 0L, it.long(1) != 0L) else Pair(false, false)
        }
        if (state.first || (automatic && (state.second || reportExists(db, normalized)))) return@transaction false
        val token = java.util.UUID.randomUUID().toString()
        val message = if (automatic) "正在自动生成日报" else "正在生成日报"
        val updated = if (automatic) {
            db.exec("UPDATE report_runs SET running=1,automatic_attempt=1,claim_token=?,status='running',message=?,retry_at=NULL WHERE date=?", listOf(token, message, normalized))
        } else db.exec("UPDATE report_runs SET running=1,claim_token=?,status='running',message=?,retry_at=NULL WHERE date=?", listOf(token, message, normalized))
        if (updated == 0) db.exec("INSERT INTO report_runs(date,running,automatic_attempt,claim_token,status,message,retry_at) VALUES(?,1,?,?,'running',?,NULL)", listOf(normalized, if (automatic) 1 else 0, token, message))
        true
    }

    fun progress(date: String, message: String, retryAt: Long? = null): Unit = transaction { db ->
        require(db.exec("UPDATE report_runs SET status=?,message=?,retry_at=? WHERE date=? AND running=1", listOf(if (retryAt == null) "running" else "waiting_retry", message, retryAt, validDate(date))) == 1) { "本次生成已结束" }
    }

    fun finish(date: String, message: String): Unit = transaction { db ->
        val normalized = validDate(date)
        val args = listOf(if (message.isBlank()) "idle" else "finished", message, generation(db, normalized) + 1, normalized)
        if (db.exec("UPDATE report_runs SET running=0,claim_token=NULL,status=?,message=?,retry_at=NULL,generation=? WHERE date=?", args) == 0) {
            db.exec("INSERT INTO report_runs(date,running,claim_token,status,message,retry_at,generation) VALUES(?,0,NULL,?,?,NULL,?)", listOf(normalized, args[0], args[1], args[2]))
        }
    }

    fun commit(input: ReportInput, result: GeneratedReport, service: String, model: String): Unit = transaction { db ->
        val date = validDate(input.date)
        val token = input.claimToken ?: throw IllegalArgumentException("本次生成凭据已失效，请重新生成")
        require(db.query("SELECT 1 FROM report_runs WHERE date=? AND running=1 AND claim_token=?", listOf(date, token)).use { it.next() }) { "本次生成已取消或已被新任务替代" }
        val currentSources = activeRecords(db, date).associate { it.record.id to it.version }
        require(input.sources.all { captured ->
            val capturedLife = EvidenceVersion.parseV3(captured.version)?.lifeDigest
            val currentLife = currentSources[captured.id]?.let(EvidenceVersion::parseV3)?.lifeDigest
            capturedLife != null && capturedLife == currentLife
        }) { "生成期间原始记录已删除或移动，未覆盖现有日报" }
        validateResult(input, result)
        val generatedIds = result.questions.associate { it.id to questionId(it.recordIds) }
        val now = ZonedDateTime.now().toString()
        val report = ReportPayload(
            DailyReport(date, result.paragraphs.map { p -> p.copy(answerIds = p.answerIds.map { generatedIds[it] ?: it }) }, input.sources, input.hash, now, service, model),
            result.questions.mapIndexed { index, q -> ReportDefinition(generatedIds.getValue(q.id), q.recordIds.distinct().sorted(), q.text, index) },
            cleared = false,
        )
        sync.writeLocalHead(db, REPORT_KIND, date, ReportPayloadCodec.encodeReport(report), observedBase = input.baseReportHeads, intentId = token, bundleId = token, resultKey = date)
        projectReport(db, sync.readEntity(db, REPORT_KIND, date))
        db.exec("UPDATE report_runs SET running=0,claim_token=NULL,status='complete',message='日报已生成',retry_at=NULL WHERE date=? AND claim_token=?", listOf(date, token))
    }

    fun answer(date: String, id: String, text: String, baseHeads: Set<String>, intentId: String): Unit =
        updateAnswer(validDate(date), id, text, false, baseHeads, intentId)

    fun skip(date: String, id: String, skipped: Boolean, baseHeads: Set<String>, intentId: String): Unit = transaction { db ->
        val normalized = validDate(date)
        val old = questionEvidence(db, normalized, id)
        val evidence = if (skipped) reconfirmEvidence(db, normalized, old) else old
        writeAnswer(db, normalized, id, evidence.answer, evidence.answeredAt, skipped, evidence, baseHeads, intentId)
    }

    fun clearEmpty(date: String, baseHeads: Set<String>, intentId: String): Unit = transaction { db ->
        val normalized = validDate(date)
        check(activeRecords(db, normalized).isEmpty()) { "当天仍有正式记录，未清空日报" }
        val cleared = ReportPayload(null, emptyList(), cleared = true)
        sync.writeLocalHead(db, REPORT_KIND, normalized, ReportPayloadCodec.encodeReport(cleared), observedBase = baseHeads, intentId = intentId, resultKey = normalized)
        projectReport(db, sync.readEntity(db, REPORT_KIND, normalized))
        if (db.exec("UPDATE report_runs SET message='当天没有正式记录',status='empty',running=0,claim_token=NULL,retry_at=NULL WHERE date=?", listOf(normalized)) == 0) {
            db.exec("INSERT INTO report_runs(date,message,status,running,claim_token,retry_at) VALUES(?,'当天没有正式记录','empty',0,NULL,NULL)", listOf(normalized))
        }
    }

    fun recoverInterrupted(): Unit = transaction { db ->
        db.exec("UPDATE report_runs SET running=0,claim_token=NULL,generation=generation+1,status='interrupted',message='上次生成被中断，请手动重试',retry_at=NULL WHERE running=1")
    }

    fun serviceCooldown(service: String): Long = transaction { db ->
        db.query("SELECT until_epoch FROM report_service_cooldowns WHERE service=?", listOf(service)).use { if (it.next()) it.long(0) else 0L }
    }

    fun setServiceCooldown(service: String, until: Long): Unit = transaction { db ->
        if (until <= 0L) db.exec("DELETE FROM report_service_cooldowns WHERE service=?", listOf(service))
        else if (db.exec("UPDATE report_service_cooldowns SET until_epoch=? WHERE service=?", listOf(until, service)) == 0) db.exec("INSERT INTO report_service_cooldowns(service,until_epoch) VALUES(?,?)", listOf(service, until))
    }

    /** Resolves a report conflict by selecting one complete candidate; report fragments are never mixed. */
    fun resolveReport(date: String, chosenHead: String, displayedHeads: Set<String>, intentId: String): Unit = transaction { db ->
        val normalized = validDate(date)
        if (intentCommitted(db, intentId)) return@transaction
        val state = sync.readEntity(db, REPORT_KIND, normalized)
        require(displayedHeads.isNotEmpty() && state.heads.map { it.id }.containsAll(displayedHeads)) { "日报候选已变化，请重新查看" }
        val chosen = state.heads.singleOrNull { it.id == chosenHead && it.id in displayedHeads } ?: throw IllegalArgumentException("日报候选已变化，请重新查看")
        validateReportCandidate(normalized, chosen.payload)
        sync.writeLocalHead(db, REPORT_KIND, normalized, chosen.payload, chosen.payloadHash, displayedHeads, intentId, resultKey = normalized)
        projectReport(db, sync.readEntity(db, REPORT_KIND, normalized))
    }

    /** Resolves an answer conflict by selecting an existing complete answer candidate. */
    fun resolveAnswer(date: String, id: String, chosenHead: String, displayedHeads: Set<String>, intentId: String): Unit = transaction { db ->
        val normalized = validDate(date)
        if (intentCommitted(db, intentId)) return@transaction
        val key = answerKey(normalized, id)
        val state = sync.readEntity(db, ANSWER_KIND, key)
        require(displayedHeads.isNotEmpty() && state.heads.map { it.id }.containsAll(displayedHeads)) { "答案候选已变化，请重新查看" }
        val chosen = state.heads.singleOrNull { it.id == chosenHead && it.id in displayedHeads } ?: throw IllegalArgumentException("答案候选已变化，请重新查看")
        resolveAnswerPayload(db, normalized, id, chosen.payload, displayedHeads, intentId)
    }

    /** Resolves an answer conflict with a user-integrated complete payload. */
    fun resolveAnswer(date: String, id: String, payload: JsonElement, displayedHeads: Set<String>, intentId: String): Unit = transaction { db ->
        val normalized = validDate(date)
        if (intentCommitted(db, intentId)) return@transaction
        val state = sync.readEntity(db, ANSWER_KIND, answerKey(normalized, id))
        require(displayedHeads.isNotEmpty() && state.heads.map { it.id }.containsAll(displayedHeads)) { "答案候选已变化，请重新查看" }
        resolveAnswerPayload(db, normalized, id, payload, displayedHeads, intentId)
    }

    /** Import projector passed to [SyncRepository.merge]; it never chooses an unresolved candidate. */
    fun projectMerged(db: SqlDatabase, changed: Set<Pair<String, String>>, purged: Set<String>) {
        var recordEvidenceChanged = purged.isNotEmpty()
        changed.forEach { (kind, key) ->
            when (kind) {
                REPORT_KIND -> projectReport(db, sync.readEntity(db, kind, key))
                ANSWER_KIND -> projectAnswer(db, sync.readEntity(db, kind, key))
                "record" -> if (!sync.hasUnresolved(db, kind, key)) recordEvidenceChanged = true
            }
        }
        if (recordEvidenceChanged) refreshAllApplicability(db)
        projectAnswerHistories(db)
    }

    private fun updateAnswer(date: String, id: String, text: String, skipped: Boolean, baseHeads: Set<String>, intentId: String): Unit = transaction { db ->
        val old = questionEvidence(db, date, id)
        val answer = text.takeUnless(String::isBlank) ?: ""
        val answeredAt = if (answer.isEmpty()) null else ZonedDateTime.now().toString()
        val evidence = if (answer.isNotEmpty() || skipped) reconfirmEvidence(db, date, old) else old
        writeAnswer(db, date, id, answer, answeredAt, skipped, evidence, baseHeads, intentId)
    }

    private fun writeAnswer(db: SqlDatabase, date: String, id: String, answer: String, answeredAt: String?, skipped: Boolean, evidence: AnswerPayload, baseHeads: Set<String>, intentId: String) {
        val payload = evidence.copy(answer = answer, answeredAt = answeredAt, skipped = skipped)
        val result = sync.writeLocalHead(db, ANSWER_KIND, answerKey(date, id), ReportPayloadCodec.encodeAnswer(payload), observedBase = baseHeads, intentId = intentId, resultKey = id)
        if (result.changed) addAnswerHistory(db, payload, baseHeads, intentId)
        projectAnswer(db, sync.readEntity(db, ANSWER_KIND, answerKey(date, id)))
    }


    private fun reconfirmEvidence(db: SqlDatabase, date: String, evidence: AnswerPayload): AnswerPayload {
        val current = activeRecords(db, date).associate { it.record.id to it.version }
        if (!evidence.recordIds.all(current::containsKey)) return evidence
        return evidence.copy(sourceVersions = evidence.recordIds.associateWith(current::getValue))
    }
    private fun resolveAnswerPayload(db: SqlDatabase, date: String, id: String, payloadJson: JsonElement, displayedHeads: Set<String>, intentId: String) {
        val payload = validateAnswerCandidate(date, id, payloadJson)
        val result = sync.writeLocalHead(db, ANSWER_KIND, answerKey(date, id), payloadJson, SyncCodec.hash(payloadJson), displayedHeads, intentId, resultKey = id)
        if (result.changed) addAnswerHistory(db, payload, displayedHeads, intentId)
        projectAnswer(db, sync.readEntity(db, ANSWER_KIND, answerKey(date, id)))
    }

    private fun validateReportCandidate(date: String, payloadJson: JsonElement): ReportPayload {
        val payload = ReportPayloadCodec.decodeReport(payloadJson)
        if (payload.cleared) {
            require(payload.report == null && payload.questions.isEmpty()) { "清空日报候选包含正文" }
            return payload
        }
        val report = requireNotNull(payload.report) { "日报候选缺少正文" }
        require(report.date == date) { "日报候选日期不一致" }
        val sourceIds = report.sources.map { it.id }
        val sourceSet = sourceIds.toSet()
        require(sourceSet.isNotEmpty() && sourceIds.size == sourceSet.size && report.sources.all { it.id.isNotBlank() && EvidenceVersion.isSupportedSourceToken(it.version) }) { "日报来源无效" }
        require(report.generatedAt.isNotBlank() && report.service.isNotBlank() && report.model.isNotBlank()) { "日报生成证据无效" }
        ZonedDateTime.parse(report.generatedAt)
        require(report.paragraphs.isNotEmpty() && report.paragraphs.all { it.text.isNotBlank() && it.recordIds.isNotEmpty() && it.recordIds.all(sourceSet::contains) }) { "日报正文引用无效" }
        require(report.paragraphs.flatMap { it.recordIds }.toSet() == sourceSet) { "日报候选未覆盖全部来源" }
        require(payload.questions.map { it.id }.distinct().size == payload.questions.size && payload.questions.map { it.position }.distinct().size == payload.questions.size && payload.questions.all { it.position >= 0 }) { "日报问题重复" }
        require(payload.questions.all { it.text.isNotBlank() && it.recordIds.isNotEmpty() && it.recordIds.all(sourceSet::contains) && questionId(it.recordIds) == it.id }) { "日报问题引用无效" }
        return payload
    }

    private fun validateAnswerCandidate(date: String, id: String, payloadJson: JsonElement): AnswerPayload {
        val payload = ReportPayloadCodec.decodeAnswer(payloadJson)
        require(payload.date == date && payload.id == id) { "答案候选身份不一致" }
        require(payload.questionText.isNotBlank() && payload.recordIds.isNotEmpty() && payload.recordIds.distinct().size == payload.recordIds.size) { "答案题面证据无效" }
        require(questionId(payload.recordIds) == id && payload.sourceVersions.keys == payload.recordIds.toSet() && payload.sourceVersions.values.all(EvidenceVersion::isSupportedSourceToken)) { "答案来源证据无效" }
        require((payload.answer.isEmpty()) == (payload.answeredAt == null)) { "答案时间证据无效" }
        payload.answeredAt?.let { ZonedDateTime.parse(it) }
        return payload
    }

    private fun intentCommitted(db: SqlDatabase, intentId: String): Boolean {
        require(intentId.isNotBlank()) { "intentId不能为空" }
        return db.query("SELECT 1 FROM sync_intents WHERE intent_id=?", listOf(intentId)).use { it.next() }
    }

    private fun addAnswerHistory(db: SqlDatabase, payload: AnswerPayload, parentHeads: Set<String>, intentId: String) {
        val syncId = sync.allocateVersionPart(db, intentId, "answer-history")
        val changedAt = ZonedDateTime.now().toString()
        db.exec("INSERT INTO report_answer_history(date,question_id,answer,skipped,changed_at,sync_id,parent_heads) VALUES(?,?,?,?,?,?,?)", listOf(payload.date, payload.id, payload.answer, if (payload.skipped) 1 else 0, changedAt, syncId, SyncCodec.encodeSet(parentHeads)))
        val history = buildJsonObject { put("date", payload.date); put("questionId", payload.id); put("answer", payload.answer); put("skipped", payload.skipped); put("changedAt", changedAt); put("questionText", payload.questionText); put("parentHeads", SyncCodec.encodeSet(parentHeads)) }
        db.exec("INSERT INTO sync_history(table_name,sync_id,payload_hash,payload_json) VALUES(?,?,?,?)", listOf("report_answer_history", syncId, SyncCodec.hash(history), SyncCodec.canonical(history)))
    }

    private fun input(db: SqlDatabase, date: String): ReportInput {
        assertNoRelatedConflicts(db, date)
        val active = activeRecords(db, date)
        val sources = active.map { ReportSource(it.record.id, it.version) }
        val questions = loadQuestions(db, date, sources.associate { it.id to it.version })
        val token = db.query("SELECT claim_token FROM report_runs WHERE date=? AND running=1", listOf(date)).use { if (it.next() && !it.isNull(0)) it.string(0) else null }
        return ReportInput(date, active.map { it.record }, questions, EvidenceVersion.inputHash(sources, questions), sources, token, sync.currentHeadIds(db, REPORT_KIND, date))
    }

    private data class VersionedRecord(val record: Record, val version: String)

    private fun activeRecords(db: SqlDatabase, date: String): List<VersionedRecord> =
        db.query("SELECT id,body,local,zone,offset,epoch,created,modified,deleted FROM records WHERE deleted IS NULL AND substr(local,1,10)=? ORDER BY epoch ASC,id ASC", listOf(date)).use { cursor ->
            buildList {
                while (cursor.next()) {
                    val record = cursor.record()
                    val entity = sync.readEntity(db, "record", record.id)
                    if (entity.unresolved) throw UnresolvedConflict("record", record.id)
                    if (entity.heads.isEmpty()) continue
                    add(VersionedRecord(record, EvidenceVersion.source(record, entity.heads.map { it.id }, sync.lifeEventIds(db, record.id))))
                }
            }
        }

    private fun loadQuestions(db: SqlDatabase, date: String, activeSources: Map<String, String>): List<ReportQuestion> {
        val aliases = sourceAliases(db)
        return db.query("SELECT id,text,answer,answered_at,skipped,applicable,position FROM report_questions WHERE date=? ORDER BY position ASC,id ASC", listOf(date)).use { cursor ->
            buildList {
                while (cursor.next()) {
                    val id = cursor.string(0)
                    val evidence = questionSourceEvidence(db, date, id)
                    val applicable = cursor.long(5) != 0L && evidence.isNotEmpty() && evidence.all { (recordId, version) ->
                        activeSources[recordId]?.let { EvidenceVersion.exactMatch(version, it, aliases, recordId) } == true
                    }
                    val answer = cursor.string(2)
                    val skipped = cursor.long(4) != 0L
                    val state = sync.readEntity(db, ANSWER_KIND, answerKey(date, id))
                    if (applicable || answer.isNotEmpty() || skipped) add(ReportQuestion(id, evidence.map { it.first }, cursor.string(1), answer, cursor.optional("answered_at"), skipped, applicable, state.heads.mapTo(linkedSetOf()) { it.id }))
            }
        }
    }
    }

    private fun loadReport(db: SqlDatabase, date: String): DailyReport? {
        if (sync.hasUnresolved(db, REPORT_KIND, date)) return null
        val metadata = db.query("SELECT input_hash,generated_at,service,model FROM report_days WHERE date=?", listOf(date)).use { if (!it.next()) return null else listOf(it.string(0), it.string(1), it.string(2), it.string(3)) }
        val paragraphs = db.query("SELECT position,text FROM report_paragraphs WHERE date=? ORDER BY position", listOf(date)).use { cursor -> buildList { while (cursor.next()) { val p = cursor.long(0).toInt(); add(ReportParagraph(cursor.string(1), ids(db,"report_paragraph_records","record_id",date,"paragraph_position",p),ids(db,"report_paragraph_answers","answer_id",date,"paragraph_position",p))) } } }
        val sources = db.query("SELECT record_id,version FROM report_sources WHERE date=? ORDER BY position", listOf(date)).use { c -> buildList { while(c.next()) add(ReportSource(c.string(0),c.string(1))) } }
        return DailyReport(date, paragraphs, sources, metadata[0], metadata[1], metadata[2], metadata[3])
    }

    private fun projectReport(db: SqlDatabase, state: EntityState) {
        if (state.unresolved || state.heads.isEmpty()) return
        val date = state.key
        val payload = validateReportCandidate(date, state.heads.first().payload)
        if (payload.cleared) {
            db.exec("DELETE FROM report_days WHERE date=?", listOf(date))
            db.exec("UPDATE report_questions SET applicable=0 WHERE date=?", listOf(date))
            return
        }
        val report = requireNotNull(payload.report)
        if (db.exec("UPDATE report_days SET input_hash=?,generated_at=?,service=?,model=? WHERE date=?", listOf(report.inputHash, report.generatedAt, report.service, report.model, date)) == 0) db.exec("INSERT INTO report_days(date,input_hash,generated_at,service,model) VALUES(?,?,?,?,?)", listOf(date,report.inputHash,report.generatedAt,report.service,report.model))
        db.exec("DELETE FROM report_paragraphs WHERE date=?", listOf(date)); db.exec("DELETE FROM report_sources WHERE date=?", listOf(date))
        report.sources.forEachIndexed { i,s -> db.exec("INSERT INTO report_sources(date,position,record_id,version) VALUES(?,?,?,?)", listOf(date,i,s.id,s.version)) }
        report.paragraphs.forEachIndexed { i,p ->
            db.exec("INSERT INTO report_paragraphs(date,position,text) VALUES(?,?,?)", listOf(date,i,p.text))
            p.recordIds.distinct().forEachIndexed { j,id -> db.exec("INSERT INTO report_paragraph_records(date,paragraph_position,position,record_id) VALUES(?,?,?,?)", listOf(date,i,j,id)) }
            p.answerIds.distinct().forEachIndexed { j,id -> db.exec("INSERT INTO report_paragraph_answers(date,paragraph_position,position,answer_id) VALUES(?,?,?,?)", listOf(date,i,j,id)) }
        }
        db.exec("UPDATE report_questions SET applicable=0 WHERE date=?", listOf(date))
        payload.questions.forEach { q ->
            val old = db.query("SELECT answer,answered_at,skipped FROM report_questions WHERE date=? AND id=?", listOf(date,q.id)).use { if(it.next()) Triple(it.string(0),it.optional("answered_at"),it.long(2)) else null }
            if (db.exec("UPDATE report_questions SET text=?,applicable=1,position=? WHERE date=? AND id=?", listOf(q.text,q.position,date,q.id)) == 0) db.exec("INSERT INTO report_questions(date,id,text,answer,answered_at,skipped,applicable,position) VALUES(?,?,?,?,?,?,1,?)", listOf(date,q.id,q.text,old?.first ?: "",old?.second,old?.third ?: 0,q.position))
            db.exec("DELETE FROM report_question_records WHERE date=? AND question_id=?", listOf(date,q.id))
            q.recordIds.forEachIndexed { i,id -> db.exec("INSERT INTO report_question_records(date,question_id,position,record_id,source_version) VALUES(?,?,?,?,?)", listOf(date,q.id,i,id,report.sources.single { it.id==id }.version)) }
        }
        refreshApplicability(db,date)
    }

    private fun projectAnswer(db: SqlDatabase, state: EntityState) {
        if (state.unresolved || state.heads.isEmpty()) return
        val separator = state.key.indexOf('/')
        require(separator > 0) { "答案实体键无效" }
        val value = validateAnswerCandidate(state.key.substring(0, separator), state.key.substring(separator + 1), state.heads.first().payload)
        val existing = db.query("SELECT 1 FROM report_questions WHERE date=? AND id=?", listOf(value.date,value.id)).use { it.next() }
        if (existing) db.exec("UPDATE report_questions SET text=?,answer=?,answered_at=?,skipped=? WHERE date=? AND id=?", listOf(value.questionText,value.answer,value.answeredAt,if(value.skipped)1 else 0,value.date,value.id))
        else db.exec("INSERT INTO report_questions(date,id,text,answer,answered_at,skipped,applicable,position) VALUES(?,?,?,?,?,?,0,?)", listOf(value.date,value.id,value.questionText,value.answer,value.answeredAt,if(value.skipped)1 else 0,Int.MAX_VALUE))
        db.exec("DELETE FROM report_question_records WHERE date=? AND question_id=?", listOf(value.date,value.id))
        value.recordIds.forEachIndexed { i,id -> db.exec("INSERT INTO report_question_records(date,question_id,position,record_id,source_version) VALUES(?,?,?,?,?)", listOf(value.date,value.id,i,id,value.sourceVersions[id] ?: "")) }
        refreshApplicability(db,value.date)
    }

    private fun questionEvidence(db: SqlDatabase, date: String, id: String): AnswerPayload {
        val row = db.query("SELECT text,answer,answered_at,skipped FROM report_questions WHERE date=? AND id=?", listOf(date,id)).use {
            require(it.next()) { "问题已不在当前日报中" }
            AnswerPayload(date, id, it.string(0), emptyList(), emptyMap(), it.string(1), it.optional("answered_at"), it.long(3) != 0L)
        }
        val evidence = questionSourceEvidence(db, date, id)
        return row.copy(recordIds = evidence.map { it.first }, sourceVersions = evidence.associate { it.first to it.second })
    }

    private fun projectAnswerHistories(db: SqlDatabase) {
        db.query("SELECT sync_id,payload_json FROM sync_history WHERE table_name='report_answer_history' ORDER BY sync_id").use { cursor ->
            while (cursor.next()) {
                val syncId = cursor.string(0)
                val exists = db.query("SELECT 1 FROM report_answer_history WHERE sync_id=?", listOf(syncId)).use { it.next() }
                if (exists) continue
                val value = SyncCodec.parse(cursor.string(1)) as? JsonObject ?: throw ContentMismatch("答案历史格式无效")
                require(value.keys.containsAll(setOf("date", "questionId", "answer", "skipped", "changedAt"))) { "答案历史字段缺失" }
                db.exec("INSERT INTO report_answer_history(date,question_id,answer,skipped,changed_at,sync_id,parent_heads) VALUES(?,?,?,?,?,?,?)", listOf(value.str("date"), value.str("questionId"), value.str("answer"), if (value.bool("skipped")) 1 else 0, value.str("changedAt"), syncId, ""))
            }
        }
    }

    private fun refreshAllApplicability(db: SqlDatabase) { db.query("SELECT DISTINCT date FROM report_questions").use { c -> while(c.next()) refreshApplicability(db,c.string(0)) } }
    private fun refreshApplicability(db: SqlDatabase, date: String) {
        val sources = try { activeRecords(db,date).associate { it.record.id to it.version } } catch (_: UnresolvedConflict) { emptyMap() }
        val aliases = sourceAliases(db)
        val reportState = sync.readEntity(db, REPORT_KIND, date)
        val selected = if (reportState.unresolved || reportState.heads.isEmpty()) emptySet() else {
            ReportPayloadCodec.decodeReport(reportState.heads.first().payload).questions.mapTo(linkedSetOf()) { it.id }
        }
        db.query("SELECT id,answer,skipped FROM report_questions WHERE date=?", listOf(date)).use { cursor ->
            while (cursor.next()) {
                val id = cursor.string(0)
                val retainedEvidence = id in selected || cursor.string(1).isNotEmpty() || cursor.long(2) != 0L
                val evidence = questionSourceEvidence(db, date, id)
                val exact = retainedEvidence && evidence.isNotEmpty() && evidence.all { (recordId, version) ->
                    sources[recordId]?.let { EvidenceVersion.exactMatch(version, it, aliases, recordId) } == true
                }
                db.exec("UPDATE report_questions SET applicable=? WHERE date=? AND id=?", listOf(if (exact) 1 else 0, date, id))
            }
        }
    }

    private fun assertNoRelatedConflicts(db: SqlDatabase, date: String) {
        if (sync.hasUnresolved(db, REPORT_KIND, date)) throw UnresolvedConflict(REPORT_KIND,date)
        db.query("SELECT DISTINCT entity_key FROM sync_heads WHERE kind='answer' AND entity_key LIKE ?", listOf("$date/%")).use { c -> while(c.next()) if(sync.hasUnresolved(db,ANSWER_KIND,c.string(0))) throw UnresolvedConflict(ANSWER_KIND,c.string(0)) }
        db.query("SELECT DISTINCT entity_key FROM sync_heads WHERE kind='record'").use { c -> while(c.next()) { val id=c.string(0); val state=sync.readEntity(db,"record",id); if(state.unresolved && state.heads.any { head -> (head.payload as? JsonObject)?.get("local")?.stringValue()?.startsWith(date)==true }) throw UnresolvedConflict("record",id) } }
    }

    private fun sourceAliases(db: SqlDatabase): List<SourceAlias> = db.query("SELECT origin_id,record_id,legacy_token,new_token FROM sync_source_alias").use { c -> buildList { while(c.next()) add(SourceAlias(c.string(0),c.string(1),c.string(2),c.string(3))) } }
    private fun questionSourceEvidence(db:SqlDatabase,date:String,id:String)=db.query("SELECT record_id,source_version FROM report_question_records WHERE date=? AND question_id=? ORDER BY position", listOf(date,id)).use { c->buildList{while(c.next())add(c.string(0) to c.string(1))} }
    private fun ids(db:SqlDatabase,table:String,column:String,date:String,parent:String,position:Int)=db.query("SELECT $column FROM $table WHERE date=? AND $parent=? ORDER BY position", listOf(date,position)).use{c->buildList{while(c.next())add(c.string(0))}}
    private fun validateResult(input: ReportInput, result: GeneratedReport) { val ids=input.sources.mapTo(hashSetOf()){it.id}; require(result.questions.size<=3); require(result.paragraphs.all{it.text.isNotBlank()&&it.recordIds.isNotEmpty()&&it.recordIds.all(ids::contains)}); require(result.paragraphs.flatMap{it.recordIds}.toSet()==ids); require(result.questions.all{it.text.isNotBlank()&&it.recordIds.isNotEmpty()&&it.recordIds.all(ids::contains)}); require(result.questions.map{questionId(it.recordIds)}.distinct().size==result.questions.size); val answers=input.questions.mapTo(hashSetOf()){it.id}+result.questions.map{it.id}; require(result.paragraphs.flatMap{it.answerIds}.all(answers::contains)) }
    private fun reportExists(db:SqlDatabase,date:String)=db.query("SELECT 1 FROM report_days WHERE date=?",listOf(date)).use{it.next()}
    private fun generation(db:SqlDatabase,date:String)=db.query("SELECT generation FROM report_runs WHERE date=?",listOf(date)).use{if(it.next())it.long(0)else 0L}
    private fun validDate(date:String)=LocalDate.parse(date).toString()
    private fun questionId(ids:List<String>)=AiClient.questionId(ids)
    private fun answerKey(date:String,id:String)="$date/$id"

    companion object { const val REPORT_KIND="report"; const val ANSWER_KIND="answer" }
}

internal data class ReportDefinition(val id:String,val recordIds:List<String>,val text:String,val position:Int)
internal data class ReportPayload(val report:DailyReport?,val questions:List<ReportDefinition>,val cleared:Boolean)
internal data class AnswerPayload(val date:String,val id:String,val questionText:String,val recordIds:List<String>,val sourceVersions:Map<String,String>,val answer:String,val answeredAt:String?,val skipped:Boolean)

internal object ReportPayloadCodec {
    fun encodeReport(value:ReportPayload)=buildJsonObject { put("cleared",value.cleared); if(value.report==null) put("report",JsonNull) else put("report",buildJsonObject { val r=value.report; put("date",r.date);put("inputHash",r.inputHash);put("generatedAt",r.generatedAt);put("service",r.service);put("model",r.model);put("sources",buildJsonArray{r.sources.forEach{s->add(buildJsonObject{put("id",s.id);put("version",s.version)})}});put("paragraphs",buildJsonArray{r.paragraphs.forEach{p->add(buildJsonObject{put("text",p.text);put("recordIds",strings(p.recordIds));put("answerIds",strings(p.answerIds))})}}) }); put("questions",buildJsonArray{value.questions.forEach{q->add(buildJsonObject{put("id",q.id);put("recordIds",strings(q.recordIds));put("text",q.text);put("position",q.position)})}}) }
    fun decodeReport(e:JsonElement):ReportPayload { val o=e.obj(setOf("cleared","report","questions")); val cleared=o.bool("cleared"); val report=if(o["report"]===JsonNull)null else { val r=o.getValue("report").obj(setOf("date","inputHash","generatedAt","service","model","sources","paragraphs")); DailyReport(r.str("date"),r.array("paragraphs").map{p->val x=p.obj(setOf("text","recordIds","answerIds"));ReportParagraph(x.str("text"),x.strings("recordIds"),x.strings("answerIds"))},r.array("sources").map{s->val x=s.obj(setOf("id","version"));ReportSource(x.str("id"),x.str("version"))},r.str("inputHash"),r.str("generatedAt"),r.str("service"),r.str("model")) }; val q=o.array("questions").map{v->val x=v.obj(setOf("id","recordIds","text","position"));ReportDefinition(x.str("id"),x.strings("recordIds"),x.str("text"),x.long("position").toInt())}; require(cleared==(report==null)); return ReportPayload(report,q,cleared) }
    fun encodeAnswer(v:AnswerPayload)=buildJsonObject{put("date",v.date);put("id",v.id);put("questionText",v.questionText);put("recordIds",strings(v.recordIds));put("sourceVersions",buildJsonObject{v.sourceVersions.toSortedMap().forEach{(k,x)->put(k,x)}});put("answer",v.answer);if(v.answeredAt==null)put("answeredAt",JsonNull)else put("answeredAt",v.answeredAt);put("skipped",v.skipped)}
    fun decodeAnswer(e:JsonElement):AnswerPayload{val o=e.obj(setOf("date","id","questionText","recordIds","sourceVersions","answer","answeredAt","skipped"));val versions=o.getValue("sourceVersions") as? JsonObject?:error("sourceVersions");return AnswerPayload(o.str("date"),o.str("id"),o.str("questionText"),o.strings("recordIds"),versions.mapValues{(_,v)->v.stringValue()},o.str("answer"),o.nullableString("answeredAt"),o.bool("skipped"))}
    private fun strings(v:Collection<String>)=buildJsonArray{v.forEach{add(JsonPrimitive(it))}}
}

private fun SqlCursor.record()=Record(string("id"),string("body"),EventTime(string("local"),string("zone"),string("offset"),long("epoch")),string("created"),optional("modified"),optional("deleted"))
private fun JsonElement.obj(keys:Set<String>):JsonObject=(this as? JsonObject)?.also{require(it.keys==keys)}?:throw IllegalArgumentException("JSON对象无效")
private fun JsonObject.str(k:String)=getValue(k).stringValue()
private fun JsonObject.nullableString(k:String)=getValue(k).let{if(it===JsonNull)null else it.stringValue()}
private fun JsonObject.bool(k:String)=(getValue(k) as? JsonPrimitive)?.booleanOrNull?:throw IllegalArgumentException(k)
private fun JsonObject.long(k:String)=(getValue(k) as? JsonPrimitive)?.longOrNull?:throw IllegalArgumentException(k)
private fun JsonObject.array(k:String)=getValue(k) as? JsonArray?:throw IllegalArgumentException(k)
private fun JsonObject.strings(k:String)=array(k).map{it.stringValue()}
private fun JsonElement.stringValue()=(this as? JsonPrimitive)?.takeIf{it.isString}?.content?:throw IllegalArgumentException("JSON字符串无效")
