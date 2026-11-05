package app.liferecord

import app.liferecord.core.*

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReportRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var store: RecordStore
    private lateinit var database: AndroidDatabase
    private lateinit var repository: ReportRepository
    private val clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"))

    @Before fun setup() {
        name = "report-${UUID.randomUUID()}.db"
        database = AndroidDatabase(context, name)
        store = RecordStore(database, clock)
        repository = ReportRepository(database)
    }

    @After fun cleanup() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun add(text: String, local: String = "2026-01-02 09:30"): String {
        val session = store.beginNew()
        return store.save(session, session.draft.copy(text = text, local = local, zone = "Asia/Shanghai", offset = "+08:00"), UUID.randomUUID().toString())
    }

    private fun edit(id: String, transform: (Draft) -> Draft) {
        val session = store.beginEdit(id)
        store.save(session, transform(session.draft), UUID.randomUUID().toString())
    }

    private fun trash(id: String) = store.moveToTrash(id, store.sync.headIds("record", id), UUID.randomUUID().toString())
    private fun restore(id: String) = store.restore(id, store.sync.headIds("record", id), UUID.randomUUID().toString())
    private fun answer(date: String, id: String, text: String) {
        val heads = repository.day(date).questions.single { it.id == id }.heads
        repository.answer(date, id, text, heads, UUID.randomUUID().toString())
    }
    private fun skip(date: String, id: String, skipped: Boolean) {
        val heads = repository.day(date).questions.single { it.id == id }.heads
        repository.skip(date, id, skipped, heads, UUID.randomUUID().toString())
    }
    private fun clearEmpty(date: String) {
        repository.clearEmpty(date, repository.input(date).baseReportHeads, UUID.randomUUID().toString())
    }

    private fun result(input: ReportInput, questionText: String? = null, questionRecords: List<String> = input.records.map { it.id }): GeneratedReport {
        val question = questionText?.let { ReportQuestion("untrusted-model-id-${UUID.randomUUID()}", questionRecords, it) }
        return GeneratedReport(
            listOf(ReportParagraph("当天完成了合成事实。", input.records.map { it.id })),
            listOfNotNull(question)
        )
    }

    private fun generate(date: String, questionText: String? = null, questionRecords: List<String>? = null): ReportDay {
        assertTrue(repository.claim(date))
        val input = repository.input(date)
        repository.commit(input, result(input, questionText, questionRecords ?: input.records.map { it.id }), "synthetic", "test-model")
        return repository.day(date)
    }

    private fun createSchemaTwo(db: SQLiteDatabase) {
        listOf(
            "CREATE TABLE records(id TEXT PRIMARY KEY, body TEXT NOT NULL, local TEXT NOT NULL, zone TEXT NOT NULL, offset TEXT NOT NULL, epoch INTEGER NOT NULL, created TEXT NOT NULL, modified TEXT, deleted TEXT, report_revision INTEGER NOT NULL DEFAULT 0, report_lifecycle INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE drafts(id TEXT PRIMARY KEY, record_id TEXT UNIQUE REFERENCES records(id) ON DELETE CASCADE, new_slot INTEGER UNIQUE CHECK(new_slot=1), body TEXT NOT NULL, local TEXT NOT NULL, zone TEXT NOT NULL, offset TEXT)",
            "CREATE TABLE revisions(id INTEGER PRIMARY KEY AUTOINCREMENT, record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE, before_body TEXT NOT NULL, before_local TEXT NOT NULL, before_zone TEXT NOT NULL, before_offset TEXT NOT NULL, before_epoch INTEGER NOT NULL, after_body TEXT NOT NULL, after_local TEXT NOT NULL, after_zone TEXT NOT NULL, after_offset TEXT NOT NULL, after_epoch INTEGER NOT NULL, modified TEXT NOT NULL)",
            "CREATE TABLE report_days(date TEXT PRIMARY KEY, input_hash TEXT NOT NULL, generated_at TEXT NOT NULL, service TEXT NOT NULL, model TEXT NOT NULL)",
            "CREATE TABLE report_paragraphs(date TEXT NOT NULL, position INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(date,position))",
            "CREATE TABLE report_paragraph_records(date TEXT NOT NULL, paragraph_position INTEGER NOT NULL, position INTEGER NOT NULL, record_id TEXT NOT NULL, PRIMARY KEY(date,paragraph_position,position))",
            "CREATE TABLE report_paragraph_answers(date TEXT NOT NULL, paragraph_position INTEGER NOT NULL, position INTEGER NOT NULL, answer_id TEXT NOT NULL, PRIMARY KEY(date,paragraph_position,position))",
            "CREATE TABLE report_sources(date TEXT NOT NULL, position INTEGER NOT NULL, record_id TEXT NOT NULL, version TEXT NOT NULL, PRIMARY KEY(date,position), UNIQUE(date,record_id))",
            "CREATE TABLE report_questions(date TEXT NOT NULL, id TEXT NOT NULL, text TEXT NOT NULL, answer TEXT NOT NULL DEFAULT '', answered_at TEXT, skipped INTEGER NOT NULL DEFAULT 0, applicable INTEGER NOT NULL DEFAULT 1, position INTEGER NOT NULL, PRIMARY KEY(date,id))",
            "CREATE TABLE report_question_records(date TEXT NOT NULL, question_id TEXT NOT NULL, position INTEGER NOT NULL, record_id TEXT NOT NULL, source_version TEXT NOT NULL, PRIMARY KEY(date,question_id,position))",
            "CREATE TABLE report_answer_history(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, question_id TEXT NOT NULL, answer TEXT NOT NULL, skipped INTEGER NOT NULL, changed_at TEXT NOT NULL)",
            "CREATE TABLE report_runs(date TEXT PRIMARY KEY, running INTEGER NOT NULL DEFAULT 0, automatic_attempt INTEGER NOT NULL DEFAULT 0, generation INTEGER NOT NULL DEFAULT 0, claim_token TEXT, status TEXT NOT NULL DEFAULT '', message TEXT NOT NULL DEFAULT '', retry_at INTEGER)",
            "CREATE TABLE report_service_cooldowns(service TEXT PRIMARY KEY, until_epoch INTEGER NOT NULL)",
        ).forEach { db.execSQL(it) }
    }

    @Test fun schemaTwoMigrationPromotesOnlyExactCurrentEvidenceAndKeepsHiddenAnswers() {
        database.close()
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        createSchemaTwo(db)
        val currentRecord = Record("current", "当前事实", EventTime("2026-01-02T09:00", "Asia/Shanghai", "+08:00", 1767315600000), "2026-01-02T10:00+08:00[Asia/Shanghai]", null, null)
        val staleRecord = Record("stale", "另一日事实", EventTime("2026-01-03T09:00", "Asia/Shanghai", "+08:00", 1767402000000), "2026-01-03T10:00+08:00[Asia/Shanghai]", null, null)
        val currentToken = EvidenceVersion.legacyV2Source(currentRecord, 0, 0)
        val staleCurrentToken = EvidenceVersion.legacyV2Source(staleRecord, 0, 0)
        val differentSameLifecycle = "0:${"0".repeat(64)}"
        val answered = ReportQuestion("answered", listOf("current"), "当前结果？", answer = "已确认")
        val currentHash = EvidenceVersion.legacyV2InputHash(listOf(ReportSource("current", currentToken)), listOf(answered))

        db.execSQL("INSERT INTO records VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf(currentRecord.id,currentRecord.text,currentRecord.time.local,currentRecord.time.zone,currentRecord.time.offset,currentRecord.time.instant,currentRecord.created,null,null,0,0))
        db.execSQL("INSERT INTO records VALUES(?,?,?,?,?,?,?,?,?,?,?)", arrayOf(staleRecord.id,staleRecord.text,staleRecord.time.local,staleRecord.time.zone,staleRecord.time.offset,staleRecord.time.instant,staleRecord.created,null,null,0,0))
        db.execSQL("INSERT INTO report_days VALUES('2026-01-02',?,'generated','synthetic','model')", arrayOf(currentHash))
        db.execSQL("INSERT INTO report_sources VALUES('2026-01-02',0,'current',?)", arrayOf(currentToken))
        db.execSQL("INSERT INTO report_questions VALUES('2026-01-02','answered','当前结果？','已确认','answered-at',0,1,0)")
        db.execSQL("INSERT INTO report_question_records VALUES('2026-01-02','answered',0,'current',?)", arrayOf(currentToken))

        db.execSQL("INSERT INTO report_days VALUES('2026-01-03','stale-digest','generated','synthetic','model')")
        db.execSQL("INSERT INTO report_sources VALUES('2026-01-03',0,'stale',?)", arrayOf(differentSameLifecycle))
        db.execSQL("INSERT INTO report_questions VALUES('2026-01-03','old-answer','旧答案？','保留答案','answered-at',0,0,0)")
        db.execSQL("INSERT INTO report_question_records VALUES('2026-01-03','old-answer',0,'stale',?)", arrayOf(differentSameLifecycle))
        db.execSQL("INSERT INTO report_questions VALUES('2026-01-03','old-skip','旧问题？','',NULL,1,0,1)")
        db.execSQL("INSERT INTO report_question_records VALUES('2026-01-03','old-skip',0,'stale',?)", arrayOf(differentSameLifecycle))
        db.execSQL("INSERT INTO report_questions VALUES('2026-01-03','hidden','无证据？','',NULL,0,0,2)")
        db.execSQL("INSERT INTO report_question_records VALUES('2026-01-03','hidden',0,'stale',?)", arrayOf(differentSameLifecycle))
        db.version = 2
        db.close()

        database = AndroidDatabase(context, name)
        store = RecordStore(database, clock)
        repository = ReportRepository(database)
        val current = repository.day("2026-01-02")
        assertTrue(current.report!!.inputHash.startsWith("v3:"))
        assertFalse(current.changed)
        assertEquals("已确认", current.questions.single().answer)
        assertTrue(current.questions.single().applicable)

        val stale = repository.day("2026-01-03")
        assertEquals("legacy-v2:stale-digest", stale.report!!.inputHash)
        assertTrue(stale.changed)
        assertEquals(setOf("old-answer", "old-skip"), stale.questions.mapTo(linkedSetOf()) { it.id })
        assertTrue(stale.questions.none { it.applicable })
        val exactAliases = database.query("SELECT legacy_token FROM sync_source_alias WHERE record_id='stale'").use { cursor -> buildSet { while (cursor.next()) add(cursor.string(0)) } }
        assertTrue(staleCurrentToken in exactAliases)
        assertFalse(differentSameLifecycle in exactAliases)
    }

    @Test fun versionOneMigrationPreservesRecordsDraftsAndRevisionHistory() {
        database.close()
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY, body TEXT NOT NULL, local TEXT NOT NULL, zone TEXT NOT NULL, offset TEXT NOT NULL, epoch INTEGER NOT NULL, created TEXT NOT NULL, modified TEXT, deleted TEXT)")
        db.execSQL("CREATE TABLE drafts(id TEXT PRIMARY KEY, record_id TEXT UNIQUE REFERENCES records(id) ON DELETE CASCADE, new_slot INTEGER UNIQUE CHECK(new_slot=1), body TEXT NOT NULL, local TEXT NOT NULL, zone TEXT NOT NULL, offset TEXT, CHECK((record_id IS NULL AND new_slot IS NOT NULL) OR (record_id IS NOT NULL AND new_slot IS NULL)))")
        db.execSQL("CREATE TABLE revisions(id INTEGER PRIMARY KEY AUTOINCREMENT, record_id TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE, before_body TEXT NOT NULL, before_local TEXT NOT NULL, before_zone TEXT NOT NULL, before_offset TEXT NOT NULL, before_epoch INTEGER NOT NULL, after_body TEXT NOT NULL, after_local TEXT NOT NULL, after_zone TEXT NOT NULL, after_offset TEXT NOT NULL, after_epoch INTEGER NOT NULL, modified TEXT NOT NULL)")
        db.execSQL("INSERT INTO records VALUES('r','旧事实','2026-01-02T09:00','Asia/Shanghai','+08:00',1767315600000,'2026-01-02T10:00+08:00[Asia/Shanghai]','2026-01-02T11:00+08:00[Asia/Shanghai]',NULL)")
        db.execSQL("INSERT INTO drafts VALUES('d','r',NULL,'待更正事实','2026-01-02 09:00','Asia/Shanghai','+08:00')")
        db.execSQL("INSERT INTO revisions(record_id,before_body,before_local,before_zone,before_offset,before_epoch,after_body,after_local,after_zone,after_offset,after_epoch,modified) VALUES('r','更早事实','2026-01-02T08:00','Asia/Shanghai','+08:00',1767312000000,'旧事实','2026-01-02T09:00','Asia/Shanghai','+08:00',1767315600000,'2026-01-02T11:00+08:00[Asia/Shanghai]')")
        db.version = 1
        db.close()

        database = AndroidDatabase(context, name)
        store = RecordStore(database, clock)
        repository = ReportRepository(database)
        val snapshot = store.snapshot()
        assertEquals("旧事实", snapshot.records.single().text)
        assertEquals("待更正事实", snapshot.drafts.single().text)
        assertEquals("更早事实", snapshot.revisions.single().beforeText)
        assertEquals(listOf("r"), repository.input("2026-01-02").records.map { it.id })
        assertEquals(RecordSchema.VERSION.toLong(), database.query("PRAGMA user_version").use { check(it.next()); it.long(0) })
    }

    @Test fun deleteRestoreLifecycleRejectsLateCommitEvenWhenRecordLooksActiveAgain() {
        val id = add("合成：完成练习")
        assertTrue(repository.claim("2026-01-02"))
        val stale = repository.input("2026-01-02")
        trash(id)
        restore(id)

        val error = assertThrows(IllegalArgumentException::class.java) {
            repository.commit(stale, result(stale), "synthetic", "test-model")
        }
        assertTrue(error.message!!.contains("删除或移动"))
        assertNull(repository.day("2026-01-02").report)
    }

    @Test fun movingSourceToAnotherDateRejectsLateCommit() {
        val id = add("合成：完成练习")
        assertTrue(repository.claim("2026-01-02"))
        val stale = repository.input("2026-01-02")
        edit(id) { it.copy(local = "2026-01-03 09:30") }

        assertThrows(IllegalArgumentException::class.java) {
            repository.commit(stale, result(stale), "synthetic", "test-model")
        }
        assertTrue(repository.input("2026-01-02").records.isEmpty())
        assertEquals(listOf(id), repository.input("2026-01-03").records.map { it.id })
    }

    @Test fun ordinaryEditAllowsSnapshotCommitButMakesReportImmediatelyStale() {
        val id = add("合成：完成练习")
        assertTrue(repository.claim("2026-01-02"))
        val snapshot = repository.input("2026-01-02")
        edit(id) { it.copy(text = "合成：完成练习并记录结果") }

        repository.commit(snapshot, result(snapshot), "synthetic", "test-model")
        assertNotNull(repository.day("2026-01-02").report)
        assertTrue(repository.day("2026-01-02").changed)
    }

    @Test fun recordAddedDuringGenerationAllowsSnapshotCommitButMarksStale() {
        add("合成：完成练习")
        assertTrue(repository.claim("2026-01-02"))
        val snapshot = repository.input("2026-01-02")
        add("阅读：读完章节", "2026-01-02 10:30")

        repository.commit(snapshot, result(snapshot), "synthetic", "test-model")
        val day = repository.day("2026-01-02")
        assertEquals(2, day.records.size)
        assertEquals(1, day.report!!.sources.size)
        assertTrue(day.changed)
    }

    @Test fun answerChangedDuringGenerationSurvivesCommitAndMarksSnapshotChanged() {
        val id = add("合成：完成练习")
        val first = generate("2026-01-02", "结果是什么？", listOf(id))
        val question = first.questions.single()
        answer("2026-01-02", question.id, "第一版结果")

        assertTrue(repository.claim("2026-01-02"))
        val inFlight = repository.input("2026-01-02")
        answer("2026-01-02", question.id, "生成期间修正的结果")
        repository.commit(inFlight, result(inFlight, "结果更新了吗？", listOf(id)), "synthetic", "test-model")

        val day = repository.day("2026-01-02")
        assertEquals(question.id, day.questions.single().id)
        assertEquals("生成期间修正的结果", day.questions.single().answer)
        assertTrue(day.changed)
        skip("2026-01-02", question.id, true)
        assertEquals("生成期间修正的结果", repository.day("2026-01-02").questions.single().answer)
    }
    @Test fun answerRetryWithSameIntentDoesNotDuplicateHeadOrHistory() {
        val id = add("合成：完成练习")
        val generated = generate("2026-01-02", "结果是什么？", listOf(id))
        val question = generated.questions.single()
        val intent = UUID.randomUUID().toString()
        repository.answer("2026-01-02", question.id, "结果已确认", question.heads, intent)
        repository.answer("2026-01-02", question.id, "结果已确认", question.heads, intent)

        assertEquals(1, SyncRepository(database).readEntity("answer", "2026-01-02/${question.id}").heads.size)
        assertEquals(1L, database.query("SELECT COUNT(*) FROM report_answer_history WHERE date=? AND question_id=?", listOf("2026-01-02", question.id)).use { check(it.next()); it.long(0) })
    }


    @Test fun answeredQuestionOmittedByModelRemainsApplicableEvidence() {
        val id = add("合成：完成练习")
        val initial = generate("2026-01-02", "结果是什么？", listOf(id))
        val question = initial.questions.single()
        answer("2026-01-02", question.id, "结果已确认")

        assertTrue(repository.claim("2026-01-02"))
        val next = repository.input("2026-01-02")
        repository.commit(next, result(next), "synthetic", "test-model")

        val evidence = repository.day("2026-01-02").questions.single()
        assertEquals(question.id, evidence.id)
        assertEquals("结果已确认", evidence.answer)
        assertTrue(evidence.applicable)
    }

    @Test fun explicitAnswerResaveReconfirmsEvidenceAfterSourceEdit() {
        val id = add("合成：完成练习")
        val generated = generate("2026-01-02", "结果是什么？", listOf(id))
        val question = generated.questions.single()
        answer("2026-01-02", question.id, "结果已确认")
        edit(id) { it.copy(text = "合成：更正练习事实") }

        assertFalse(repository.day("2026-01-02").questions.single().applicable)
        answer("2026-01-02", question.id, "基于更正记录再次确认")
        val refreshed = repository.day("2026-01-02").questions.single()
        assertTrue(refreshed.applicable)
        assertEquals("基于更正记录再次确认", refreshed.answer)
    }

    @Test fun unansweredGeneratedQuestionDoesNotImmediatelyChangeInputHash() {
        add("合成：完成练习")
        assertTrue(repository.claim("2026-01-02"))
        val before = repository.input("2026-01-02")
        repository.commit(before, result(before, "练习结果是什么？"), "synthetic", "test-model")
        val after = repository.input("2026-01-02")

        assertEquals(before.hash, after.hash)
        assertFalse(repository.day("2026-01-02").changed)
    }

    @Test fun sourceFingerprintKeepsQuestionIdentitySkipAndHidesObsoleteUnansweredQuestion() {
        val firstId = add("合成：完成练习", "2026-01-02 09:30")
        val secondId = add("阅读：读完章节", "2026-01-02 10:30")
        val first = generate("2026-01-02", "第一次措辞", listOf(firstId))
        val stableId = first.questions.single().id
        skip("2026-01-02", stableId, true)

        assertTrue(repository.claim("2026-01-02"))
        val next = repository.input("2026-01-02")
        repository.commit(next, result(next, "完全不同的措辞", listOf(firstId)), "synthetic", "test-model")
        val preserved = repository.day("2026-01-02").questions.single()
        assertEquals(stableId, preserved.id)
        assertEquals("完全不同的措辞", preserved.text)
        assertTrue(preserved.skipped)

        skip("2026-01-02", stableId, false)
        assertTrue(repository.claim("2026-01-02"))
        val third = repository.input("2026-01-02")
        repository.commit(third, result(third, "另一个活动的问题", listOf(secondId)), "synthetic", "test-model")
        val visible = repository.day("2026-01-02").questions
        assertEquals(1, visible.size)
        assertEquals(listOf(secondId), visible.single().recordIds)
        assertNotEquals(stableId, visible.single().id)
    }

    @Test fun canceledClaimTokenCannotCommitAfterAReplacementClaim() {
        add("合成：完成练习")
        assertTrue(repository.claim("2026-01-02"))
        val canceled = repository.input("2026-01-02")
        repository.finish("2026-01-02", "已取消")
        assertTrue(repository.claim("2026-01-02"))
        repository.input("2026-01-02")

        val error = assertThrows(IllegalArgumentException::class.java) {
            repository.commit(canceled, result(canceled), "synthetic", "test-model")
        }
        assertTrue(error.message!!.contains("取消") || error.message!!.contains("替代"))
    }

    @Test fun clearEmptyRemovesBodyButRetainsAnsweredEvidenceAsInapplicable() {
        val id = add("合成：完成练习")
        val generated = generate("2026-01-02", "结果是什么？", listOf(id))
        answer("2026-01-02", generated.questions.single().id, "结果已确认")
        trash(id)

        clearEmpty("2026-01-02")
        val day = repository.day("2026-01-02")
        assertNull(day.report)
        assertEquals("结果已确认", day.questions.single().answer)
        assertFalse(day.questions.single().applicable)
    }
    @Test fun automaticClaimProgressRecoveryAndCooldownSurviveRestart() {
        add("合成：完成练习")
        assertTrue(repository.claim("2026-01-02", automatic = true))
        repository.progress("2026-01-02", "等待服务重试", retryAt = 123456L)
        repository.setServiceCooldown("https://service.invalid", 654321L)
        database.close()

        database = AndroidDatabase(context, name)
        store = RecordStore(database, clock)
        repository = ReportRepository(database)
        val running = repository.day("2026-01-02")
        assertTrue(running.running)
        assertEquals("等待服务重试", running.message)
        assertEquals(123456L, running.retryAt)
        assertEquals(654321L, repository.serviceCooldown("https://service.invalid"))

        repository.recoverInterrupted()
        val recovered = repository.day("2026-01-02")
        assertFalse(recovered.running)
        assertTrue(recovered.message.contains("中断"))
        assertFalse(repository.claim("2026-01-02", automatic = true))
        assertTrue(repository.claim("2026-01-02", automatic = false))
    }

}
