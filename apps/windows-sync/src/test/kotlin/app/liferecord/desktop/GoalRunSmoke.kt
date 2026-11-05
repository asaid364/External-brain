package app.liferecord.desktop

import app.liferecord.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Isolated SQLite + real goal/completion client exercise; never contacts an AI service. */
fun main() = runBlocking {
    val root = Files.createTempDirectory("goal-run-smoke-${UUID.randomUUID()}-")
    val path = root.resolve("records.db")
    val clock = Clock.fixed(Instant.parse("2027-01-22T04:00:00Z"), ZoneOffset.UTC)
    val config = AiConfiguration("https://synthetic.invalid/v1", "synthetic-only", "fixture", false, false)
    val sends = AtomicInteger()
    lateinit var key: GoalReviewKey
    lateinit var result: GeneratedGoalReview
    lateinit var goalId: String
    val transport = ChatTransport { _ ->
        sends.incrementAndGet()
        val envelope = buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", "stop")
                put("message", buildJsonObject { put("content", GoalCodec.encodeResult(result).toString()) })
            }) })
        }
        ChatResponse(200, emptyMap(), envelope.toString())
    }
    try {
        WindowsDatabase(path).use { db ->
            val repo = GoalRepository(db, clock)
            val edit = repo.openEditor()
            goalId = edit.goalId
            val milestone = UUID.randomUUID().toString()
            val goal = GoalSpec(goalId, "SYNTH-GOAL-${UUID.randomUUID()}", "交付合成原型", "2027-01-01", "2027-01-21", "UTC", 7,
                listOf(GoalMilestone(milestone, "交付可运行原型"), GoalMilestone(UUID.randomUUID().toString(), "独立验证结果")), false)
            repo.save(edit, goal, UUID.randomUUID().toString())
            val store = RecordStore(db, clock)
            val record = store.beginNew()
            store.save(record, record.draft.copy(text = "合成：可运行原型已交付", local = "2027-01-15 09:00", zone = "UTC", offset = "Z"), UUID.randomUUID().toString())
            key = GoalReviewKey(goalId, "2027-01-15", "2027-01-21")
            val ref = repo.snapshot(key).evidence.first { it.dependency.ref.kind == GoalEvidenceKind.RECORD }.dependency.ref.id
            result = GeneratedGoalReview(GoalDirection.ADVANCING, GoalEvidenceClaim("模拟AI：原型已交付", listOf(ref)), emptyList(),
                listOf(GoalEvidenceClaim("模拟AI：已有交付成果", listOf(ref))), emptyList(),
                listOf(GoalEvidenceClaim("模拟AI：可核实的交付推进", listOf(ref))),
                listOf(GoalMilestoneSuggestion(milestone, "模拟AI：请核对后确认", listOf(ref))), emptyList(), listOf("其它日期行动未知"))
            repo.grantAnalysis(goalId, GoalCodec.specHash(goal), config.endpoint, config.model, true, false)
            val coord = GoalReviewCoordinator(db, repo, clock)
            val holder = store.sync.identity().databaseId
            val coordination = object : GoalReviewGenerationGate {
                override suspend fun beforeCapture(key: GoalReviewKey, automatic: Boolean) { check(!automatic) }
                override suspend fun run(input: GoalAnalysisInput, automatic: Boolean, generate: suspend () -> Unit) {
                    val lease = coord.claim(input.key, requireNotNull(input.runId), holder, input.hash)
                    generate()
                    coord.finish(input.key, lease.runId, lease.token)
                }
            }
            val engine = GoalAnalysisEngine(repo, { config }, GoalAnalysisClient(clock, transport), clock,
                serviceGate = Mutex(), generationGate = coordination)
            engine.request(key)
            withTimeout(10_000) { engine.awaitCompletion(key) }
            check(repo.run(key)?.state == GoalRunState.SUCCEEDED)
            check(repo.detail(goalId).reviews.single().direction == GoalDirection.ADVANCING)
            check(repo.detail(goalId).summary.completed == 0)
            check(sends.get() == 1)
            engine.request(key)
            engine.awaitCompletion(key)
            check(sends.get() == 1) // Unchanged input is free.
        }
        WindowsDatabase(path).use { db ->
            val repo = GoalRepository(db, clock)
            check(repo.detail(goalId).reviews.size == 1)
            check(repo.run(key)?.state == GoalRunState.SUCCEEDED)
            val coord = GoalReviewCoordinator(db, repo, clock)
            val held = coord.claim(key, UUID.randomUUID().toString(), "synthetic-peer", repo.snapshot(key).hash)
            val blocked = GoalAnalysisEngine(repo, { config }, GoalAnalysisClient(clock, transport), clock,
                generationGate = object : GoalReviewGenerationGate {
                    override suspend fun beforeCapture(key: GoalReviewKey, automatic: Boolean) {}
                    override suspend fun run(input: GoalAnalysisInput, automatic: Boolean, generate: suspend () -> Unit) {
                        coord.claim(input.key, requireNotNull(input.runId), "synthetic-local", input.hash)
                        generate()
                    }
                })
            blocked.request(key, force = true)
            withTimeout(10_000) { blocked.awaitCompletion(key) }
            check(sends.get() == 1)
            check(coord.current(key)?.runId == held.runId)
            check(repo.run(key)?.requestSent == false)
            coord.finish(key, held.runId, held.token)
            result = result.copy(assessment = result.assessment.copy(text = "模拟AI：重新核对原型交付"))
            // Fail only review publication; RESULT_READY/context must still commit durably.
            db.exec("CREATE TRIGGER synthetic_fail_review BEFORE INSERT ON sync_heads WHEN NEW.kind='goal-review' BEGIN SELECT RAISE(ABORT,'synthetic publication failure'); END")
            val engine = GoalAnalysisEngine(repo, { config }, GoalAnalysisClient(clock, transport), clock)
            engine.request(key, force = true)
            withTimeout(10_000) { engine.awaitCompletion(key) }
            check(sends.get() == 2)
            check(repo.run(key)?.state == GoalRunState.RESULT_READY)
            check(repo.detail(goalId).reviews.size == 1) // Prior success survives publication failure.
            db.exec("DROP TRIGGER synthetic_fail_review")
        }
        WindowsDatabase(path).use { db ->
            val repo = GoalRepository(db, clock)
            check(repo.run(key)?.state == GoalRunState.RESULT_READY)
            val engine = GoalAnalysisEngine(repo, { error("Save-only recovery must not load credentials") },
                object : GoalAnalysisAi {
                    override suspend fun generate(configuration: AiConfiguration, input: GoalAnalysisInput, beforeSend: suspend () -> Unit): GeneratedGoalReview =
                        error("Save-only recovery must not call AI")
                }, clock)
            engine.recover()
            check(repo.run(key)?.state == GoalRunState.SUCCEEDED)
            check(repo.detail(goalId).reviews.size == 1)
            check(repo.detail(goalId).summary.completed == 0)
            engine.retryLocalSave(key)
            check(sends.get() == 2)
        }
        WindowsDatabase(path).use { db ->
            val repo = GoalRepository(db, clock)
            check(repo.run(key)?.state == GoalRunState.SUCCEEDED)
            check(repo.detail(goalId).reviews.single().assessment.text.startsWith("模拟AI"))
            check(repo.detail(goalId).summary.completed == 0)
        }
        println("GOAL_RUN_SMOKE: PASS fixture=$goalId requests=${sends.get()}; controlled real client -> SQLite publication -> reopen; lease conflict sent zero; durable RESULT_READY recovered save-only without credentials; 0/2 remains user-controlled; no real AI service/network")
    } finally {
        root.toFile().deleteRecursively()
    }
}
