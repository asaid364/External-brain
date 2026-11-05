package app.liferecord.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Mandatory schema-2 to schema-3 report evidence migration, executed inside the schema transaction. */
object ReportV3Migration {
    fun migrate(db: SqlDatabase, originId: String, sync: SyncRepository) {
        val current = currentRecords(db, sync)
        current.values.forEach { record ->
            db.exec(
                "INSERT OR IGNORE INTO sync_source_alias(origin_id,record_id,legacy_token,new_token) VALUES(?,?,?,?)",
                listOf(originId, record.record.id, record.legacy, record.v3),
            )
        }

        val dates = db.query("SELECT date FROM report_days ORDER BY date").use { cursor ->
            buildList { while (cursor.next()) add(cursor.string(0)) }
        }
        dates.forEach { date -> migrateReport(db, date, current, sync) }
        migrateAnswers(db, sync)
        migrateAnswerHistory(db, originId)
    }

    private data class MigratedRecord(val record: Record, val legacy: String, val v3: String)

    private fun currentRecords(db: SqlDatabase, sync: SyncRepository): Map<String, MigratedRecord> =
        db.query("SELECT id,body,local,zone,offset,epoch,created,modified,deleted,report_revision,report_lifecycle FROM records ORDER BY id").use { cursor ->
            buildMap {
                while (cursor.next()) {
                    val record = record(cursor)
                    val entity = sync.readEntity(db, "record", record.id)
                    require(!entity.unresolved && entity.heads.isNotEmpty()) { "旧记录无法建立唯一迁移证据" }
                    val legacy = EvidenceVersion.legacyV2Source(record, cursor.long("report_revision"), cursor.long("report_lifecycle"))
                    val v3 = EvidenceVersion.source(record, entity.heads.map { it.id }, sync.lifeEventIds(db, record.id))
                    put(record.id, MigratedRecord(record, legacy, v3))
                }
            }
        }

    private fun migrateReport(db: SqlDatabase, date: String, records: Map<String, MigratedRecord>, sync: SyncRepository) {
        val active = records.values.filter { it.record.deleted == null && it.record.time.local.startsWith(date) }.sortedWith(compareBy({ it.record.time.instant }, { it.record.id }))
        val legacySources = active.map { ReportSource(it.record.id, it.legacy) }
        val v3Sources = active.map { ReportSource(it.record.id, it.v3) }
        val legacySourceMap = legacySources.associate { it.id to it.version }
        val questions = legacyVisibleQuestions(db, date, legacySourceMap)
        val storedHash = db.query("SELECT input_hash FROM report_days WHERE date=?", listOf(date)).use { check(it.next()); it.string(0) }
        val legacyCurrent = EvidenceVersion.legacyV2InputHash(legacySources, questions)
        val v3Current = EvidenceVersion.inputHash(v3Sources, questions)
        val migratedHash = EvidenceVersion.preserveLegacyInputHash(storedHash, legacyCurrent, v3Current)
        db.exec("UPDATE report_days SET input_hash=? WHERE date=?", listOf(migratedHash, date))

        val metadata = db.query("SELECT generated_at,service,model FROM report_days WHERE date=?", listOf(date)).use { check(it.next()); Triple(it.string(0),it.string(1),it.string(2)) }
        val persistedSources = db.query("SELECT record_id,version FROM report_sources WHERE date=? ORDER BY position", listOf(date)).use { c -> buildList { while(c.next()) add(ReportSource(c.string(0),c.string(1))) } }
        val paragraphs = db.query("SELECT position,text FROM report_paragraphs WHERE date=? ORDER BY position", listOf(date)).use { c -> buildList { while(c.next()) { val p=c.long(0).toInt(); add(ReportParagraph(c.string(1),ids(db,"report_paragraph_records","record_id",date,"paragraph_position",p),ids(db,"report_paragraph_answers","answer_id",date,"paragraph_position",p))) } } }
        val definitions = db.query("SELECT id,text,position FROM report_questions WHERE date=? AND applicable=1 ORDER BY position,id", listOf(date)).use { c -> buildList { while(c.next()) { val id=c.string(0); add(ReportDefinition(id,questionEvidence(db,date,id).map{it.first},c.string(1),c.long(2).toInt())) } } }
        val payload = ReportPayload(DailyReport(date,paragraphs,persistedSources,migratedHash,metadata.first,metadata.second,metadata.third),definitions,false)
        sync.seedHead(db, ReportRepository.REPORT_KIND, date, ReportPayloadCodec.encodeReport(payload))
    }

    private fun migrateAnswers(db: SqlDatabase, sync: SyncRepository) {
        db.query("SELECT date,id,text,answer,answered_at,skipped FROM report_questions WHERE answer<>'' OR skipped<>0 ORDER BY date,id").use { c ->
            while(c.next()) {
                val date=c.string(0); val id=c.string(1); val evidence=questionEvidence(db,date,id)
                val payload=AnswerPayload(date,id,c.string(2),evidence.map{it.first},evidence.associate{it.first to it.second},c.string(3),c.optional("answered_at"),c.long(5)!=0L)
                sync.seedHead(db, ReportRepository.ANSWER_KIND, "$date/$id", ReportPayloadCodec.encodeAnswer(payload))
            }
        }
    }

    private fun migrateAnswerHistory(db: SqlDatabase, originId: String) {
        db.query("SELECT id,date,question_id,answer,skipped,changed_at FROM report_answer_history ORDER BY id").use { c ->
            while(c.next()) {
                val localId=c.long(0); val syncId="$originId/legacy-answer/$localId"
                db.exec("UPDATE report_answer_history SET sync_id=? WHERE id=?", listOf(syncId,localId))
                val payload=buildJsonObject{put("date",c.string(1));put("questionId",c.string(2));put("answer",c.string(3));put("skipped",c.long(4)!=0L);put("changedAt",c.string(5))}
                db.exec("INSERT INTO sync_history(table_name,sync_id,payload_hash,payload_json) VALUES(?,?,?,?)", listOf("report_answer_history",syncId,SyncCodec.hash(payload),SyncCodec.canonical(payload)))
            }
        }
    }

    private fun legacyVisibleQuestions(db:SqlDatabase,date:String,sources:Map<String,String>):List<ReportQuestion> =
        db.query("SELECT id,text,answer,answered_at,skipped,applicable FROM report_questions WHERE date=? ORDER BY position,id", listOf(date)).use { c -> buildList { while(c.next()) { val id=c.string(0); val evidence=questionEvidence(db,date,id); val applicable=c.long(5)!=0L&&evidence.isNotEmpty()&&evidence.all{sources[it.first]==it.second}; val answer=c.string(2); val skipped=c.long(4)!=0L; if(applicable||answer.isNotEmpty()||skipped)add(ReportQuestion(id,evidence.map{it.first},c.string(1),answer,c.optional("answered_at"),skipped,applicable)) } } }
    private fun questionEvidence(db:SqlDatabase,date:String,id:String)=db.query("SELECT record_id,source_version FROM report_question_records WHERE date=? AND question_id=? ORDER BY position", listOf(date,id)).use{c->buildList{while(c.next())add(c.string(0) to c.string(1))}}
    private fun ids(db:SqlDatabase,table:String,column:String,date:String,parent:String,position:Int)=db.query("SELECT $column FROM $table WHERE date=? AND $parent=? ORDER BY position", listOf(date,position)).use{c->buildList{while(c.next())add(c.string(0))}}
    private fun record(c:SqlCursor)=Record(c.string("id"),c.string("body"),EventTime(c.string("local"),c.string("zone"),c.string("offset"),c.long("epoch")),c.string("created"),c.optional("modified"),c.optional("deleted"))
}
