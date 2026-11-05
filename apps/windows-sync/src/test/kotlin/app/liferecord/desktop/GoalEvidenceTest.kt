package app.liferecord.desktop

import app.liferecord.core.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class GoalEvidenceTest {
    private val clock = Clock.fixed(Instant.parse("2027-01-22T04:00:00Z"), ZoneOffset.UTC)

    @Test fun snapshotUsesFormalLocalDateEvidenceAndCurrentAnswerAndReportOnly() {
        withDatabase("goal-evidence-snapshot") { db ->
            val store = RecordStore(db, clock)
            val first = saveRecord(store, "合成：可运行原型已交付", "2027-01-15 09:00")
            saveRecord(store, "窗口外事实", "2027-01-14 23:59")
            val reports = ReportRepository(db)
            generateReport(reports, "2027-01-15", ask = true)
            val question = reports.day("2027-01-15").questions.single()
            reports.answer("2027-01-15", question.id, "已完成独立验证", question.heads, uuid())
            generateReport(reports, "2027-01-15", ask = false, answerId = question.id)
            duplicateHead(db, ReportRepository.ANSWER_KIND, "2027-01-15/${question.id}")
            duplicateHead(db, ReportRepository.REPORT_KIND, "2027-01-15")

            val goals = GoalRepository(db, clock)
            val milestone = uuid()
            val opened = goals.openEditor()
            goals.save(opened, goal(opened.goalId, milestone), uuid())
            val input = goals.snapshot(GoalReviewKey(opened.goalId, "2027-01-15", "2027-01-21"))

            assertEquals(3, input.evidence.size)
            assertEquals(setOf("r:$first", "a:2027-01-15/${question.id}", "d:2027-01-15/0"),
                input.evidence.mapTo(linkedSetOf()) { it.dependency.ref.id })
            assertEquals(1, input.coverage.first().recordCount)
            assertEquals("CURRENT", input.coverage.first().reportStatus)
            assertEquals(0, input.coverage.first().unanswered)
            assertEquals(7, input.coverage.size)
            assertEquals(input.hash, goals.snapshot(input.key).hash)
            assertEquals(64, input.hash.length)
            assertFalse(input.evidence.any { it.text.contains("窗口外事实") })
            assertTrue(input.evidence.single { it.dependency.ref.kind == GoalEvidenceKind.ANSWER }.text.contains("已完成独立验证"))
            assertTrue(input.evidence.filter { it.dependency.ref.kind != GoalEvidenceKind.RECORD }
                .all { it.dependency.heads.size == 2 })
        }
    }

    @Test fun staleReportIsCoverageOnlyAndMissingDaysRemainUnknown() {
        withDatabase("goal-evidence-stale-report") { db ->
            val store = RecordStore(db, clock)
            saveRecord(store, "原始事实", "2027-01-15 09:00")
            val reports = ReportRepository(db)
            generateReport(reports, "2027-01-15", ask = false)
            saveRecord(store, "后来补充的正式事实", "2027-01-15 11:00")

            val goals = GoalRepository(db, clock)
            val opened = goals.openEditor()
            goals.save(opened, goal(opened.goalId, uuid()), uuid())
            val input = goals.snapshot(GoalReviewKey(opened.goalId, "2027-01-15", "2027-01-21"))

            assertEquals("STALE", input.coverage.first().reportStatus)
            assertTrue(input.evidence.none { it.dependency.ref.kind == GoalEvidenceKind.REPORT })
            assertEquals(2, input.coverage.first().recordCount)
            assertTrue(input.coverage.drop(1).all { it.recordCount == 0 && it.reportStatus == "MISSING" })
        }
    }

    @Test fun retainedReviewDerivesCurrentStaleUnavailableAndConflictWithoutStoredSourceText() {
        withDatabase("goal-evidence-status") { db ->
            val store = RecordStore(db, clock)
            val recordId = saveRecord(store, "生成时事实", "2027-01-15 09:00")
            val goals = GoalRepository(db, clock)
            val opened = goals.openEditor()
            val milestone = uuid()
            goals.save(opened, goal(opened.goalId, milestone), uuid())
            val key = GoalReviewKey(opened.goalId, "2027-01-15", "2027-01-21")
            val input = goals.snapshot(key)
            val review = review(input)

            assertEquals(GoalEvidence.CURRENT, goals.reviewStatus(review))
            val edit = store.beginEdit(recordId)
            store.save(edit, edit.draft.copy(text = "当前已修改事实"), uuid())
            assertEquals(GoalEvidence.STALE, goals.reviewStatus(review))

            val heads = SyncRepository(db).headIds("record", recordId)
            store.moveToTrash(recordId, heads, uuid())
            assertEquals(GoalEvidence.UNAVAILABLE, goals.reviewStatus(review))

            val staleGoalEditor = goals.openEditor(opened.goalId)
            goals.save(staleGoalEditor, staleGoalEditor.initial!!.copy(title = "候选B"), uuid())
            goals.save(staleGoalEditor, staleGoalEditor.initial!!.copy(title = "候选C"), uuid())
            assertEquals(GoalEvidence.CONFLICT, goals.reviewStatus(review))
            assertThrows(UnresolvedConflict::class.java) { goals.snapshot(key) }
        }
    }

    @Test fun confirmationEvidenceRejectsChangedOrUnavailableReference() {
        withDatabase("goal-evidence-reference") { db ->
            val store = RecordStore(db, clock)
            val recordId = saveRecord(store, "用于用户声明的事实", "2027-01-15 09:00")
            val goals = GoalRepository(db, clock)
            val milestone = uuid()
            val opened = goals.openEditor()
            goals.save(opened, goal(opened.goalId, milestone), uuid())
            val input = goals.snapshot(GoalReviewKey(opened.goalId, "2027-01-15", "2027-01-21"))
            val ref = input.evidence.single().dependency.ref
            goals.confirmMilestone(opened.goalId, milestone, GoalCodec.criterionHash("交付可运行原型"), true,
                "用户核对", listOf(ref), emptySet(), uuid())

            val edit = store.beginEdit(recordId)
            store.save(edit, edit.draft.copy(text = "证据内容已改变"), uuid())
            assertThrows(IllegalArgumentException::class.java) {
                goals.confirmMilestone(opened.goalId, milestone, GoalCodec.criterionHash("交付可运行原型"), true,
                    "再次核对", listOf(ref), goals.detail(opened.goalId).confirmations.single().heads, uuid())
            }
        }
    }

    @Test fun equivalentHeadsRemainUsableAndConfirmationOrderIsCanonical() {
        withDatabase("goal-evidence-equivalent-heads") { db ->
            val store = RecordStore(db, clock)
            val recordId = saveRecord(store, "等值多头事实", "2027-01-15 09:00")
            duplicateHead(db, "record", recordId)
            val goals = GoalRepository(db, clock)
            val opened = goals.openEditor()
            val firstMilestone = uuid()
            val secondMilestone = uuid()
            val spec = goal(opened.goalId, firstMilestone).copy(
                milestones = listOf(
                    GoalMilestone(firstMilestone, "交付可运行原型"),
                    GoalMilestone(secondMilestone, "完成独立验证"),
                ),
            )
            goals.save(opened, spec, uuid())
            duplicateHead(db, GoalRepository.GOAL_KIND, opened.goalId)
            goals.confirmMilestone(opened.goalId, firstMilestone, GoalCodec.criterionHash("交付可运行原型"),
                true, "用户声明一", emptyList(), emptySet(), uuid())
            goals.confirmMilestone(opened.goalId, secondMilestone, GoalCodec.criterionHash("完成独立验证"),
                false, "用户声明二", emptyList(), emptySet(), uuid())
            duplicateHead(db, GoalRepository.CONFIRMATION_KIND,
                GoalRepository.confirmationKey(opened.goalId, firstMilestone))

            val input = goals.snapshot(GoalReviewKey(opened.goalId, "2027-01-15", "2027-01-21"))
            assertEquals(2, input.goalHeads.size)
            assertEquals(2, input.evidence.single().dependency.heads.size)
            assertEquals(2, input.confirmations.single { it.value.milestoneId == firstMilestone }.heads.size)
            val reordered = review(input).copy(confirmationSnapshot = input.confirmations.reversed())
            assertEquals(GoalEvidence.CURRENT, goals.reviewStatus(reordered))
        }
    }

    @Test fun unavailableSourceOutranksAnotherChangedSourceWithinOneDependency() {
        withDatabase("goal-evidence-priority") { db ->
            val store = RecordStore(db, clock)
            val changedId = saveRecord(store, "稍后修改", "2027-01-15 09:00")
            val purgedId = saveRecord(store, "稍后彻删", "2027-01-15 10:00")
            val reports = ReportRepository(db)
            generateReport(reports, "2027-01-15", ask = false)
            val goals = GoalRepository(db, clock)
            val opened = goals.openEditor()
            goals.save(opened, goal(opened.goalId, uuid()), uuid())
            val input = goals.snapshot(GoalReviewKey(opened.goalId, "2027-01-15", "2027-01-21"))
            val reportDependency = input.evidence.single { it.dependency.ref.kind == GoalEvidenceKind.REPORT }.dependency

            val changed = store.beginEdit(changedId)
            store.save(changed, changed.draft.copy(text = "已经修改"), uuid())
            store.moveToTrash(purgedId, SyncRepository(db).headIds("record", purgedId), uuid())
            store.purge(purgedId, uuid())
            val retained = review(input).copy(dependencies = listOf(reportDependency))
            assertEquals(GoalEvidence.UNAVAILABLE, goals.reviewStatus(retained))
        }
    }

    private fun generateReport(repository: ReportRepository, date: String, ask: Boolean, answerId: String? = null) {
        assertTrue(repository.claim(date))
        val input = repository.input(date)
        val ids = input.records.map { it.id }
        val questions = if (ask || answerId != null) listOf(ReportQuestion("temporary", ids, "是否完成独立验证？")) else emptyList()
        repository.commit(
            input,
            GeneratedReport(listOf(ReportParagraph("本日记录了可核对的事实。", ids, answerId?.let(::listOf).orEmpty())), questions),
            "synthetic", "synthetic-model",
        )
    }

    private fun review(input: GoalAnalysisInput): GoalReviewPayload {
        val evidenceId = input.evidence.first().dependency.ref.id
        return GoalReviewPayload(
            key = input.key,
            goalSnapshot = input.goal,
            goalHeads = input.goalHeads,
            confirmationSnapshot = input.confirmations,
            inputHash = input.hash,
            dependencies = input.evidence.map { it.dependency },
            coverage = input.coverage,
            direction = GoalDirection.ADVANCING,
            assessment = GoalEvidenceClaim("正在推进", listOf(evidenceId)),
            observations = emptyList(), achievements = emptyList(), recurringProblems = emptyList(),
            positiveFeedback = emptyList(), milestoneSuggestions = emptyList(), nextActions = emptyList(), unknowns = emptyList(),
            generatedAt = clock.instant().toString(), service = "synthetic", model = "synthetic-model",
        )
    }

    private fun saveRecord(store: RecordStore, text: String, local: String): String {
        val session = store.beginNew()
        return store.save(session, session.draft.copy(text = text, local = local, zone = "UTC", offset = "Z"), uuid())
    }

    private fun goal(id: String, milestoneId: String) = GoalSpec(
        id, "交付合成小工具", "可运行原型", "2027-01-01", "2027-01-21", "UTC", 7,
        listOf(GoalMilestone(milestoneId, "交付可运行原型")), false,
    )

    private fun duplicateHead(db: WindowsDatabase, kind: String, key: String) {
        val sync = SyncRepository(db)
        val payload = sync.readEntity(kind, key).heads.first().payload
        sync.writeLocalHead(kind, key, payload, observedBase = emptySet(), intentId = uuid())
    }

    private fun withDatabase(prefix: String, block: (WindowsDatabase) -> Unit) {
        val root = Files.createTempDirectory(prefix)
        try { WindowsDatabase(root.resolve("records.db")).use(block) } finally { root.toFile().deleteRecursively() }
    }

    private fun uuid(): String = UUID.randomUUID().toString()
}
