package app.liferecord.desktop

import app.liferecord.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Real isolated SQLite stores, fixed snapshots, framed transport and durable receipts; no network/AI. */
class GoalReplicationTest {
    private val clock = Clock.fixed(Instant.parse("2027-01-22T04:00:00Z"), ZoneOffset.UTC)
    private val endpoint = "https://synthetic.invalid/v1"
    private val model = "synthetic-model"
    private val peer = "synthetic-goal-peer"
    private fun uuid() = UUID.randomUUID().toString()

    private data class Fixture(val goal: GoalSpec, val key: GoalReviewKey, val recordId: String)

    private fun fixture(db: WindowsDatabase): Fixture {
        val goals = GoalRepository(db, clock)
        val opened = goals.openEditor()
        val goal = GoalSpec(opened.goalId, "交付合成小工具", "交付并独立验证", "2027-01-01", "2027-01-21", "UTC", 7,
            listOf(GoalMilestone(uuid(), "交付可运行原型"), GoalMilestone(uuid(), "用独立数据验证结果")), false)
        goals.save(opened, goal, uuid())
        val records = RecordStore(db, clock)
        val openedRecord = records.beginNew()
        val recordId = records.save(openedRecord, openedRecord.draft.copy(text = "合成：可运行原型已交付",
            local = "2027-01-15 09:00", zone = "UTC", offset = "Z"), uuid())
        return Fixture(goal, GoalReviewKey(goal.id, "2027-01-15", "2027-01-21"), recordId)
    }

    private fun stageResult(db: WindowsDatabase, fixture: Fixture, assessment: String = "合成：原型交付推进目标"): GoalReviewContext {
        val goals = GoalRepository(db, clock)
        goals.grantAnalysis(fixture.goal.id, GoalCodec.specHash(fixture.goal), endpoint, model, true, false)
        val input = requireNotNull(goals.claim(fixture.key, false, endpoint, model))
        goals.markSent(requireNotNull(input.runId))
        val evidence = input.evidence.first { it.dependency.ref.kind == GoalEvidenceKind.RECORD }.dependency.ref.id
        goals.stage(requireNotNull(input.runId), GeneratedGoalReview(GoalDirection.ADVANCING,
            GoalEvidenceClaim(assessment, listOf(evidence)), emptyList(),
            listOf(GoalEvidenceClaim("合成：交付可运行原型", listOf(evidence))), emptyList(),
            listOf(GoalEvidenceClaim("合成：交付有正式记录支持", listOf(evidence))),
            listOf(GoalMilestoneSuggestion(fixture.goal.milestones.first().id, "请核对原型", listOf(evidence))),
            emptyList(), listOf("其余日期未知")))
        return requireNotNull(goals.context(requireNotNull(input.runId)))
    }

    private fun publish(db: WindowsDatabase, fixture: Fixture, assessment: String = "合成：原型交付推进目标") {
        stageResult(db, fixture, assessment)
        val goals = GoalRepository(db, clock)
        requireNotNull(goals.publishPending(fixture.key))
    }

    private fun bind(a: WindowsDatabase, b: WindowsDatabase) {
        listOf(a, b).forEach { SyncExchange(it).apply { bindSpace("synthetic-goal-space"); setConsent(peer, true, true) } }
    }

    private fun stage(sender: SyncExchange, receiver: SyncExchange, manifest: ExportManifest) {
        receiver.beginIncoming(peer, SyncWire.decodeManifest(SyncWire.encodeManifest(manifest)))
        repeat(manifest.partCount) {
            val part = SyncWire.decodePart(SyncWire.encodePart(sender.exportPart(manifest.exportId, it)))
            receiver.receivePart(manifest.exportId, part)
            receiver.receivePart(manifest.exportId, part)
        }
    }

    private fun transfer(a: WindowsDatabase, b: WindowsDatabase, full: Boolean = false): Ack {
        val outgoing = SyncExchange(a)
        val incoming = SyncExchange(b)
        val manifest = outgoing.prepare(peer, SyncRepository(b).identity().databaseId,
            outgoing.status(peer).ackedLocalSeq, full)
        stage(outgoing, incoming, manifest)
        val ack = SyncWire.decodeAck(SyncWire.encodeAck(incoming.commitIncoming(peer, manifest.exportId)))
        outgoing.acknowledge(peer, SyncRepository(b).identity().databaseId, ack)
        return ack
    }

    @Test fun fullIncrementalBidirectionalProgressAndDurableAckSurviveReopen() = temporary { root ->
        lateinit var goal: GoalSpec
        lateinit var ack: Ack
        WindowsDatabase(root.resolve("a.db")).use { a -> WindowsDatabase(root.resolve("b.db")).use { b ->
            bind(a, b)
            val fixture = fixture(a)
            goal = fixture.goal
            publish(a, fixture)
            val outgoing = SyncExchange(a)
            val incoming = SyncExchange(b)
            val manifest = outgoing.prepare(peer, SyncRepository(b).identity().databaseId, 0, true)
            stage(outgoing, incoming, manifest)
            ack = incoming.commitIncoming(peer, manifest.exportId)
            assertEquals(0L, outgoing.status(peer).ackedLocalSeq) // lost ACK is not implicit acknowledgement
            assertEquals(0, GoalRepository(b, clock).detail(goal.id).summary.completed)
            assertEquals(1, GoalRepository(b, clock).detail(goal.id).reviews.size)
        } }
        WindowsDatabase(root.resolve("a.db")).use { a -> WindowsDatabase(root.resolve("b.db")).use { b ->
            val outgoing = SyncExchange(a)
            val incoming = SyncExchange(b)
            assertEquals(ack, incoming.commitIncoming(peer, ack.exportId))
            outgoing.acknowledge(peer, SyncRepository(b).identity().databaseId, ack)
            assertEquals(ack.throughSeq, outgoing.status(peer).ackedLocalSeq)
            val milestone = goal.milestones.first()
            GoalRepository(b, clock).confirmMilestone(goal.id, milestone.id, GoalCodec.criterionHash(milestone.criterion),
                true, "用户声明", emptyList(), emptySet(), uuid())
            transfer(b, a, true)
            assertEquals(50, GoalRepository(a, clock).detail(goal.id).summary.percent)
            val confirmationKey = "${goal.id}/${milestone.id}"
            GoalRepository(a, clock).confirmMilestone(goal.id, milestone.id, GoalCodec.criterionHash(milestone.criterion),
                false, "用户撤回", emptyList(), SyncRepository(a).headIds(GoalCodec.CONFIRMATION_KIND, confirmationKey), uuid())
            val incremental = transfer(a, b)
            assertTrue(incremental.throughSeq > ack.throughSeq)
            assertEquals(0, GoalRepository(b, clock).detail(goal.id).summary.completed)
            assertEquals(1L, incoming.status(peer).counts!!.goals)
            assertEquals(1L, incoming.status(peer).counts!!.reviews)
            assertNull(GoalRepository(b, clock).permission(goal.id)) // permissions are not synchronized
            assertNull(GoalRepository(b, clock).run(GoalReviewKey(goal.id, "2027-01-15", "2027-01-21")))
        } }
    }

    @Test fun unseenGoalAndConfirmationEditsRemainConflictsAcrossFramedExchange() = replicas { a, b ->
        val fixture = fixture(a)
        transfer(a, b, true)
        val ga = GoalRepository(a, clock)
        val gb = GoalRepository(b, clock)
        val openedA = ga.openEditor(fixture.goal.id)
        val openedB = gb.openEditor(fixture.goal.id)
        ga.save(openedA, fixture.goal.copy(title = "合成A"), uuid())
        gb.save(openedB, fixture.goal.copy(title = "合成B"), uuid())
        val milestone = fixture.goal.milestones.first()
        ga.confirmMilestone(fixture.goal.id, milestone.id, GoalCodec.criterionHash(milestone.criterion), true,
            "A确认", emptyList(), emptySet(), uuid())
        gb.confirmMilestone(fixture.goal.id, milestone.id, GoalCodec.criterionHash(milestone.criterion), false,
            "B撤回", emptyList(), emptySet(), uuid())
        transfer(a, b)
        transfer(b, a, true)
        listOf(a, b).forEach {
            assertEquals(2, SyncRepository(it).readEntity(GoalCodec.GOAL_KIND, fixture.goal.id).heads.size)
            assertEquals(2, SyncRepository(it).readEntity(GoalCodec.CONFIRMATION_KIND, "${fixture.goal.id}/${milestone.id}").heads.size)
            assertNull(GoalRepository(it, clock).detail(fixture.goal.id).summary.percent)
        }
    }

    @Test fun purgeKeepsHistoricalReviewButInvalidatesPendingOnBothReplicas() = replicas { a, b ->
        val fixture = fixture(a)
        publish(a, fixture)
        transfer(a, b, true)
        stageResult(a, fixture)
        stageResult(b, fixture)
        val store = RecordStore(a, clock)
        store.moveToTrash(fixture.recordId, SyncRepository(a).headIds("record", fixture.recordId), uuid())
        store.purge(fixture.recordId, uuid())
        assertEquals(GoalRunState.INVALIDATED, GoalRepository(a, clock).run(fixture.key)!!.state)
        assertNull(pending(a, fixture.key))
        transfer(a, b)
        listOf(a, b).forEach { db ->
            val goals = GoalRepository(db, clock)
            assertEquals(GoalRunState.INVALIDATED, goals.run(fixture.key)!!.state)
            assertNull(pending(db, fixture.key))
            val history = goals.detail(fixture.goal.id).reviews.single()
            assertEquals(GoalDirection.ADVANCING, history.direction)
            assertTrue(history.dependencies.any { fixture.recordId in it.ref.recordIds })
            assertTrue(SyncRepository(db).isPurged(fixture.recordId))
            assertTrue(RecordStore(db, clock).snapshot().records.isEmpty())
        }
    }

    @Test fun preconsentCountsAreUnknownAndSchemaThreeCannotStageAnyData() = replicas { a, b ->
        fixture(a)
        val outgoing = SyncExchange(a)
        val incoming = SyncExchange(b)
        incoming.setConsent(peer, false, true)
        val hidden = SyncWire.decodeStatus(SyncWire.encodeStatus(incoming.status(peer)))
        assertNull(hidden.entityCount)
        assertNull(hidden.purgeCount)
        assertNull(hidden.counts)
        incoming.setConsent(peer, true, true)
        val manifest = outgoing.prepare(peer, SyncRepository(b).identity().databaseId, 0, true)
        val error = assertThrows(SyncProtocolException::class.java) {
            incoming.beginIncoming(peer, manifest.copy(dataSchema = 3))
        }
        assertEquals(SyncErrorCode.UNSUPPORTED_SCHEMA, error.code)
        assertEquals(0L, incoming.status(peer).receivedRemoteSeq)
        assertTrue(GoalRepository(b, clock).list().isEmpty())
    }

    @Test fun malformedGoalPayloadAndHashRejectWholeFramedBatchWithoutCursorAdvance() = replicas { a, b ->
        val fixture = fixture(a)
        val sync = SyncRepository(a)
        val bad = JsonObject(GoalCodec.encodeGoal(fixture.goal).let { it as JsonObject } + ("id" to JsonPrimitive(uuid())))
        sync.writeLocalHead(GoalCodec.GOAL_KIND, fixture.goal.id, bad, sync.headIds(GoalCodec.GOAL_KIND, fixture.goal.id), uuid())
        val outgoing = SyncExchange(a)
        val incoming = SyncExchange(b)
        val manifest = outgoing.prepare(peer, SyncRepository(b).identity().databaseId, 0, true)
        incoming.beginIncoming(peer, manifest)
        val part = outgoing.exportPart(manifest.exportId, 0)
        assertThrows(SyncProtocolException::class.java) { incoming.receivePart(manifest.exportId, part.copy(partHash = "0".repeat(64))) }
        repeat(manifest.partCount) { incoming.receivePart(manifest.exportId, outgoing.exportPart(manifest.exportId, it)) }
        assertThrows(Exception::class.java) { incoming.commitIncoming(peer, manifest.exportId) }
        assertEquals(0L, incoming.status(peer).receivedRemoteSeq)
        assertTrue(GoalRepository(b, clock).list().isEmpty())
        assertTrue(RecordStore(b, clock).snapshot().records.isEmpty())
    }

    @Test fun invalidReviewReferenceRejectsWholeImportAndStatusNeverConvertsUnknownToZero() = replicas { a, b ->
        val fixture = fixture(a)
        publish(a, fixture)
        val sync = SyncRepository(a)
        val review = sync.readEntity(GoalCodec.REVIEW_KIND, fixture.key.encoded).heads.single().payload as JsonObject
        val bad = JsonObject(review + ("assessment" to JsonObject(mapOf(
            "text" to JsonPrimitive("合成：不存在的引用"),
            "evidenceIds" to JsonArray(listOf(JsonPrimitive("r:${uuid()}"))),
        ))))
        sync.writeLocalHead(GoalCodec.REVIEW_KIND, fixture.key.encoded, bad,
            sync.headIds(GoalCodec.REVIEW_KIND, fixture.key.encoded), uuid())
        val outgoing = SyncExchange(a)
        val incoming = SyncExchange(b)
        val manifest = outgoing.prepare(peer, SyncRepository(b).identity().databaseId, 0, true)
        stage(outgoing, incoming, manifest)
        assertThrows(Exception::class.java) { incoming.commitIncoming(peer, manifest.exportId) }
        assertEquals(0L, incoming.status(peer).receivedRemoteSeq)
        assertTrue(GoalRepository(b, clock).list().isEmpty())
        val visible = outgoing.status(peer)
        assertEquals(1L, SyncWire.decodeStatus(SyncWire.encodeStatus(visible)).counts!!.goals)
        assertThrows(SyncProtocolException::class.java) {
            SyncWire.decodeStatus(SyncWire.encodeStatus(visible.copy(sendConsent = false)))
        }
        assertThrows(SyncProtocolException::class.java) {
            SyncWire.decodeStatus(SyncWire.encodeStatus(visible.copy(counts = visible.counts!!.copy(goals = -1))))
        }
        assertThrows(SyncProtocolException::class.java) {
            SyncWire.decodeStatus(SyncWire.encodeStatus(visible.copy(entityCount = null)))
        }
    }

    @Test fun concurrentCompleteReviewsStayCandidatesAndResolutionDoesNotSwallowUnseenHead() = replicas { a, b ->
        val fixture = fixture(a)
        transfer(a, b, true)
        publish(a, fixture)
        publish(b, fixture, "合成：另一完整判断认为原型推进目标")
        transfer(a, b)
        transfer(b, a, true)
        val sync = SyncRepository(a)
        val displayed = sync.headIds(GoalCodec.REVIEW_KIND, fixture.key.encoded)
        assertEquals(2, displayed.size)
        val chosen = displayed.first()
        val payload = sync.readEntity(GoalCodec.REVIEW_KIND, fixture.key.encoded).heads.first().payload
        // A third complete candidate arrives after the user opened the picker.
        SyncRepository(b).writeLocalHead(GoalCodec.REVIEW_KIND, fixture.key.encoded, payload, emptySet(), uuid())
        transfer(b, a)
        val unseen = sync.headIds(GoalCodec.REVIEW_KIND, fixture.key.encoded) - displayed
        assertEquals(1, unseen.size)
        GoalRepository(a, clock).resolveReview(fixture.key, chosen, displayed, uuid())
        assertEquals(2, sync.readEntity(GoalCodec.REVIEW_KIND, fixture.key.encoded).heads.size)
        assertTrue(sync.headIds(GoalCodec.REVIEW_KIND, fixture.key.encoded).containsAll(unseen))
        transfer(a, b)
        assertEquals(sync.readEntity(GoalCodec.REVIEW_KIND, fixture.key.encoded),
            SyncRepository(b).readEntity(GoalCodec.REVIEW_KIND, fixture.key.encoded))
    }

    @Test fun reviewArrivingBeforeGoalAndRecordConvergesWithoutDiscardingHistoricalReferences() = replicas { a, b ->
        val fixture = fixture(a)
        publish(a, fixture)
        val snapshot = SyncRepository(a).snapshot()
        val reviews = snapshot.entities.filter { it.kind == GoalCodec.REVIEW_KIND }
        SyncRepository(b).merge(snapshot.copy(entities = reviews, knownHeads = emptyList(),
            lifeEvents = emptyList(), histories = emptyList(), sourceAliases = emptyList()))
        assertEquals(1, SyncRepository(b).readEntity(GoalCodec.REVIEW_KIND, fixture.key.encoded).heads.size)
        assertTrue(RecordStore(b, clock).snapshot().records.isEmpty())
        transfer(a, b, true)
        val detail = GoalRepository(b, clock).detail(fixture.goal.id)
        assertEquals(fixture.goal, detail.summary.spec)
        assertEquals(1, detail.reviews.size)
        assertEquals(GoalRepository(a, clock).detail(fixture.goal.id).reviews, detail.reviews)
        assertEquals(1, RecordStore(b, clock).snapshot().records.size)
    }

    private fun pending(db: WindowsDatabase, key: GoalReviewKey): String? =
        db.query("SELECT pending_json FROM goal_review_runs WHERE review_key=?", listOf(key.encoded)).use {
            if (it.next() && !it.isNull(0)) it.string(0) else null
        }

    private fun replicas(action: (WindowsDatabase, WindowsDatabase) -> Unit) = temporary { root ->
        WindowsDatabase(root.resolve("a.db")).use { a -> WindowsDatabase(root.resolve("b.db")).use { b ->
            bind(a, b)
            action(a, b)
        } }
    }

    private fun temporary(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("goal-replication-${uuid()}")
        try { action(root) } finally { root.toFile().deleteRecursively() }
    }
}
