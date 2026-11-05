package app.liferecord.desktop

import app.liferecord.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class GoalAnalysisEngineTest {
    private val directory: Path = Files.createTempDirectory("goal-analysis-engine-${UUID.randomUUID()}")
    private val clock = Clock.fixed(Instant.parse("2027-01-22T04:00:00Z"), ZoneOffset.UTC)
    private val database = WindowsDatabase(directory.resolve("records.db"))
    private val repository = GoalRepository(database, clock)
    private val configuration = AiConfiguration("https://synthetic.invalid/v1", "synthetic-only", "fixture", false, false)

    @After fun close() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    @Test fun permissionIsIndependentAndSuggestionPublishesWithoutChangingProgress() = runBlocking {
        val fixture = fixture()
        var sends = 0
        val ai = object : GoalAnalysisAi {
            override suspend fun generate(configuration: AiConfiguration, input: GoalAnalysisInput, beforeSend: suspend () -> Unit): GeneratedGoalReview {
                beforeSend()
                sends++
                return result(input, fixture.milestoneId)
            }
        }
        val engine = GoalAnalysisEngine(repository, { configuration }, ai, clock)

        assertTrue(runCatching { engine.request(fixture.key) }.isFailure)
        assertEquals(0, sends)
        assertNull(repository.run(fixture.key))

        repository.grantAnalysis(fixture.goal.id, GoalCodec.specHash(fixture.goal), configuration.endpoint,
            configuration.model, sendAllowed = true, automaticAllowed = false)
        engine.request(fixture.key)
        engine.awaitCompletion(fixture.key)

        assertEquals(1, sends)
        assertEquals(GoalRunState.SUCCEEDED, repository.run(fixture.key)!!.state)
        assertTrue(repository.externalAttempted(fixture.key))
        assertEquals(GoalDirection.ADVANCING, repository.detail(fixture.goal.id).reviews.single().direction)
        assertEquals(0, repository.detail(fixture.goal.id).summary.completed)
    }

    @Test fun duplicateClicksShareOneRequestAndCancellationCannotLatePublish() = runBlocking {
        val fixture = fixture()
        repository.grantAnalysis(fixture.goal.id, GoalCodec.specHash(fixture.goal), configuration.endpoint,
            configuration.model, sendAllowed = true, automaticAllowed = false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sends = 0
        val ai = object : GoalAnalysisAi {
            override suspend fun generate(configuration: AiConfiguration, input: GoalAnalysisInput, beforeSend: suspend () -> Unit): GeneratedGoalReview {
                beforeSend()
                sends++
                entered.complete(Unit)
                release.await()
                return result(input, fixture.milestoneId)
            }
        }
        val engine = GoalAnalysisEngine(repository, { configuration }, ai, clock)
        engine.request(fixture.key)
        withTimeout(5_000) { entered.await() }
        engine.request(fixture.key)
        engine.cancel(fixture.key)
        release.complete(Unit)

        assertEquals(1, sends)
        assertEquals(GoalRunState.CANCELLED, repository.run(fixture.key)!!.state)
        assertTrue(repository.detail(fixture.goal.id).reviews.isEmpty())
    }

    @Test fun sentInterruptedRunRecoversUnknownAndAutomaticCannotConsumeStickyWindow() = runBlocking {
        val fixture = fixture()
        repository.grantAnalysis(fixture.goal.id, GoalCodec.specHash(fixture.goal), configuration.endpoint,
            configuration.model, sendAllowed = true, automaticAllowed = true)
        val claimed = repository.claim(fixture.key, true, configuration.endpoint, configuration.model)
        assertNotNull(claimed)
        repository.markSent(requireNotNull(claimed!!.runId))

        repository.recoverInterrupted()
        assertEquals(GoalRunState.UNKNOWN, repository.run(fixture.key)!!.state)
        assertTrue(repository.externalAttempted(fixture.key))
        assertNull(repository.claim(fixture.key, true, configuration.endpoint, configuration.model))
    }

    private data class Fixture(val goal: GoalSpec, val milestoneId: String, val key: GoalReviewKey)

    private fun fixture(): Fixture {
        val opened = repository.openEditor()
        val milestoneId = UUID.randomUUID().toString()
        val goal = GoalSpec(opened.goalId, "交付合成小工具", "交付可运行原型", "2027-01-01", "2027-01-21", "UTC", 7,
            listOf(GoalMilestone(milestoneId, "交付可运行原型")), false)
        repository.save(opened, goal, UUID.randomUUID().toString())
        val store = RecordStore(database, clock)
        val record = store.beginNew()
        store.save(record, record.draft.copy(text = "合成：可运行原型已交付", local = "2027-01-15 09:00", zone = "UTC", offset = "Z"), UUID.randomUUID().toString())
        return Fixture(goal, milestoneId, GoalReviewKey(goal.id, "2027-01-15", "2027-01-21"))
    }

    private fun result(input: GoalAnalysisInput, milestoneId: String): GeneratedGoalReview {
        val evidenceId = input.evidence.first { it.dependency.ref.kind == GoalEvidenceKind.RECORD }.dependency.ref.id
        return GeneratedGoalReview(
            GoalDirection.ADVANCING,
            GoalEvidenceClaim("记录显示原型已交付", listOf(evidenceId)),
            emptyList(),
            listOf(GoalEvidenceClaim("已交付可运行原型", listOf(evidenceId))),
            emptyList(),
            listOf(GoalEvidenceClaim("已有可核实的交付成果", listOf(evidenceId))),
            listOf(GoalMilestoneSuggestion(milestoneId, "请核对证据后确认", listOf(evidenceId))),
            emptyList(),
            listOf("其它日期缺少记录"),
        )
    }
}
