package app.liferecord.desktop

import app.liferecord.core.AiConfiguration
import app.liferecord.core.AiFailure
import app.liferecord.core.GeneratedReport
import app.liferecord.core.RecordStore
import app.liferecord.core.ReportAi
import app.liferecord.core.ReportEngine
import app.liferecord.core.ReportInput
import app.liferecord.core.ReportParagraph
import app.liferecord.core.ReportRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ReportEngineTest {
    private val directory: Path = Files.createTempDirectory("liferecord-engine-${UUID.randomUUID()}")
    private val clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val database = WindowsDatabase(directory.resolve("records.db"))
    private val store = RecordStore(database, clock)
    private val repository = ReportRepository(database)
    private val configuration = AiConfiguration("https://synthetic.invalid/v1", "synthetic-only", "synthetic-model", true, true)

    @After
    fun close() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    private fun addRecord() {
        val session = store.beginNew()
        store.save(session, session.draft.copy(text = "合成：完成阅读练习", local = "2026-11-02 09:00", zone = "UTC", offset = "Z"), UUID.randomUUID().toString())
    }

    private fun engine(
        generate: suspend (ReportInput) -> GeneratedReport,
        waits: MutableList<Long> = mutableListOf(),
    ) = ReportEngine(
        repository,
        { configuration },
        object : ReportAi {
            override suspend fun generate(configuration: AiConfiguration, input: ReportInput) = generate(input)
            override suspend fun test(configuration: AiConfiguration) = Unit
        },
        clock,
        { true },
        { waits.add(it) },
    )

    private fun result(input: ReportInput) = GeneratedReport(
        listOf(ReportParagraph("完成阅读练习。", input.records.map { it.id })),
        emptyList(),
    )

    @Test
    fun transientFailureRetriesOnceSameSnapshotThenNoChangeDoesNotRequest() = runBlocking {
        addRecord()
        val snapshots = mutableListOf<ReportInput>()
        val waits = mutableListOf<Long>()
        val engine = engine({ input ->
            snapshots.add(input)
            if (snapshots.size == 1) throw AiFailure("短暂故障", true)
            result(input)
        }, waits)

        engine.request("2026-11-02")
        engine.awaitCompletion("2026-11-02")

        assertEquals(listOf(10_000L), waits)
        assertEquals(2, snapshots.size)
        assertSame(snapshots[0], snapshots[1])
        assertNotNull(repository.day("2026-11-02").report)

        engine.request("2026-11-02")
        engine.awaitCompletion("2026-11-02")
        assertEquals(2, snapshots.size)
    }

    @Test
    fun longProviderCooldownStopsRetryAndAppliesToForcedRequest() = runBlocking {
        addRecord()
        var calls = 0
        val waits = mutableListOf<Long>()
        val engine = engine({
            calls++
            throw AiFailure("限流", true, 120_000)
        }, waits)

        engine.request("2026-11-02")
        engine.awaitCompletion("2026-11-02")
        engine.request("2026-11-02", force = true)

        assertEquals(1, calls)
        assertTrue(waits.isEmpty())
        assertNull(repository.day("2026-11-02").report)
        assertFalse(repository.day("2026-11-02").running)
    }

    @Test
    fun cancellationAndRepeatedClickCannotPublishOrDuplicate() = runBlocking {
        addRecord()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val engine = engine({ input ->
            calls++
            entered.complete(Unit)
            release.await()
            result(input)
        })

        engine.request("2026-11-02")
        withTimeout(5_000) { entered.await() }
        engine.request("2026-11-02")
        engine.cancel("2026-11-02")
        release.complete(Unit)

        assertEquals(1, calls)
        assertNull(repository.day("2026-11-02").report)
        assertFalse(repository.day("2026-11-02").running)
    }

    @Test
    fun automaticNeverOverwritesManuallyGeneratedDay() = runBlocking {
        addRecord()
        var calls = 0
        val engine = engine({
            calls++
            result(it)
        })

        engine.request("2026-11-02")
        engine.awaitCompletion("2026-11-02")
        val old = store.snapshot().records.single()
        val editing = store.beginEdit(old.id)
        store.save(editing, editing.draft.copy(text = "合成：补充结果"), UUID.randomUUID().toString())
        engine.request("2026-11-02", automatic = true)

        assertEquals(1, calls)
        assertTrue(repository.day("2026-11-02").changed)
    }
}
