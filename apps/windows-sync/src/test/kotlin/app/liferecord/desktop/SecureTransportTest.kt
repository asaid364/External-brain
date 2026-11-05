package app.liferecord.desktop

import app.liferecord.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Synthetic SQLite fixtures only. Actual HTTPS and current-user DPAPI are exercised by SecureSmokeKt. */
class SecureTransportTest {
    private val clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val copyProtector = object : SecretProtector {
        override fun protect(cleartext: ByteArray) = cleartext.copyOf()
        override fun unprotect(ciphertext: ByteArray) = ciphertext.copyOf()
    }

    @Test fun newIdentityIsDnsSafeAndReopeningDoesNotRotateCertificate() = temporary { root ->
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) { bytes.fill(0xff.toByte()) }
        }
        val first = WindowsSecrets(root, clock, random, copyProtector).identity()
        assertEquals("ffffffffffffffffffff", first.serverId)
        assertEquals("life-${first.serverId}.local", first.stableHostName)
        val reopened = WindowsSecrets(root, clock, protector = copyProtector).identity()
        assertEquals(first.serverId, reopened.serverId)
        assertArrayEquals(first.certificate.certificate.encoded, reopened.certificate.certificate.encoded)
        assertArrayEquals(first.certificate.keyPair.private.encoded, reopened.certificate.keyPair.private.encoded)
    }

    @Test fun invalidPersistedIdentityFailsWithoutRewritingCredentials() = temporary { root ->
        WindowsSecrets(root, clock, protector = copyProtector).identity()
        val path = root.resolve("sync-credentials.dpapi")
        val text = Files.readString(path)
        val document = SyncWire.json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val identity = document.getValue("identity") as kotlinx.serialization.json.JsonObject
        val invalid = kotlinx.serialization.json.JsonObject(document + ("identity" to
            kotlinx.serialization.json.JsonObject(identity + ("serverId" to kotlinx.serialization.json.JsonPrimitive("BAD_id")))))
        Files.writeString(path, invalid.toString())
        val before = Files.readAllBytes(path)
        assertThrows(IllegalStateException::class.java) { WindowsSecrets(root, clock, protector = copyProtector).identity() }
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test fun failedProtectionAndUnreadableAiConfigNeverReplacePriorCiphertext() = temporary { root ->
        val path = root.resolve("synthetic-secret")
        AtomicSecretFile(path, copyProtector).write("prior encrypted fixture".toByteArray())
        val before = Files.readAllBytes(path)
        val failing = object : SecretProtector {
            override fun protect(cleartext: ByteArray): ByteArray = error("synthetic protect failure")
            override fun unprotect(ciphertext: ByteArray): ByteArray = error("synthetic unprotect failure")
        }
        assertThrows(IllegalStateException::class.java) { AtomicSecretFile(path, failing).write("replacement".toByteArray()) }
        assertArrayEquals(before, Files.readAllBytes(path))
        val ai = WindowsAiStorage(path, failing)
        val value = AiConfiguration("https://synthetic.invalid/v1", "synthetic-key", "synthetic-model", true, false)
        assertThrows(IllegalStateException::class.java) { ai.write(value, false) }
        assertArrayEquals(before, Files.readAllBytes(path))
        assertThrows(IllegalStateException::class.java) { ai.write(value, true) }
        assertArrayEquals(before, Files.readAllBytes(path))
    }

    @Test fun outOfOrderDuplicateAndCorruptPartsCannotPublishPartialContent() = replicas { sender, receiver ->
        val body = "合成🙂多字节".repeat(22000)
        val id = record(sender, body, "large-record")
        val outgoing = SyncExchange(sender)
        val incoming = SyncExchange(receiver)
        val manifest = outgoing.prepare(PEER, databaseId(receiver), 0, true)
        assertTrue("fixture must cross byte-part boundaries", manifest.partCount >= 2)
        incoming.beginIncoming(PEER, manifest)
        val last = outgoing.exportPart(manifest.exportId, manifest.partCount - 1)
        incoming.receivePart(manifest.exportId, last)
        incoming.receivePart(manifest.exportId, last)
        protocol(SyncErrorCode.INCOMPLETE_EXPORT) { incoming.commitIncoming(PEER, manifest.exportId) }
        assertTrue(RecordStore(receiver, clock).snapshot().records.isEmpty())
        assertEquals(0L, incoming.status(PEER).receivedRemoteSeq)
        val first = outgoing.exportPart(manifest.exportId, 0)
        val damaged = first.copy(dataBase64 = Base64.getEncoder().encodeToString("damaged".toByteArray()))
        protocol(SyncErrorCode.CONTENT_MISMATCH) { incoming.receivePart(manifest.exportId, damaged) }
        assertTrue(RecordStore(receiver, clock).snapshot().records.isEmpty())
        repeat(manifest.partCount) { incoming.receivePart(manifest.exportId, outgoing.exportPart(manifest.exportId, it)) }
        val ack = incoming.commitIncoming(PEER, manifest.exportId)
        assertEquals(Ack(manifest.exportId, manifest.throughSeq, manifest.manifestHash), ack)
        assertEquals(body, RecordStore(receiver, clock).snapshot().records.single { it.id == id }.text)
        assertEquals(manifest.throughSeq, incoming.status(PEER).receivedRemoteSeq)
        assertEquals(0L, outgoing.status(PEER).ackedLocalSeq)
        outgoing.acknowledge(PEER, databaseId(receiver), ack)
        assertEquals(manifest.throughSeq, outgoing.status(PEER).ackedLocalSeq)
    }

    @Test fun durableCommitRetryAndAckDoNotConsumeWritesAfterSnapshot() = temporary { root ->
        val senderPath = root.resolve("sender.db")
        val receiverPath = root.resolve("receiver.db")
        lateinit var manifest: ExportManifest
        lateinit var ack: Ack
        lateinit var firstId: String
        lateinit var laterId: String
        WindowsDatabase(senderPath).use { sender -> WindowsDatabase(receiverPath).use { receiver ->
            bind(sender, receiver)
            firstId = record(sender, "合成：快照 H", "first")
            val outgoing = SyncExchange(sender)
            val incoming = SyncExchange(receiver)
            manifest = outgoing.prepare(PEER, databaseId(receiver), 0, true)
            laterId = record(sender, "合成：快照之后 H+1", "later")
            stage(outgoing, incoming, manifest)
            ack = incoming.commitIncoming(PEER, manifest.exportId)
            assertEquals(setOf(firstId), RecordStore(receiver, clock).snapshot().records.map { it.id }.toSet())
        } }
        WindowsDatabase(senderPath).use { sender -> WindowsDatabase(receiverPath).use { receiver ->
            val outgoing = SyncExchange(sender)
            val incoming = SyncExchange(receiver)
            val before = SyncRepository(receiver).snapshot()
            assertEquals(ack, incoming.commitIncoming(PEER, manifest.exportId))
            val duplicate = incoming.beginIncoming(PEER, manifest)
            assertEquals(IncomingState.APPLIED, duplicate.state)
            assertEquals(ack, duplicate.ack)
            assertEquals(before, SyncRepository(receiver).snapshot())
            outgoing.acknowledge(PEER, databaseId(receiver), ack)
            val next = outgoing.prepare(PEER, databaseId(receiver), manifest.throughSeq)
            stage(outgoing, incoming, next)
            outgoing.acknowledge(PEER, databaseId(receiver), incoming.commitIncoming(PEER, next.exportId))
            assertEquals(setOf(firstId, laterId), RecordStore(receiver, clock).snapshot().records.map { it.id }.toSet())
            assertEquals(SyncRepository(sender).identity().commitSeq, outgoing.status(PEER).ackedLocalSeq)
            incoming.setConsent(PEER, false, true)
            protocol(SyncErrorCode.CONSENT_REQUIRED) { incoming.commitIncoming(PEER, manifest.exportId) }
        } }
    }

    @Test fun identitySchemaHashAndCursorErrorsLeaveBusinessAndWatermarksUnchanged() = replicas { sender, receiver ->
        record(sender, "合成：未发布", "validation")
        val outgoing = SyncExchange(sender)
        val incoming = SyncExchange(receiver)
        val manifest = outgoing.prepare(PEER, databaseId(receiver), 0, true)
        protocol(SyncErrorCode.UNSUPPORTED_SCHEMA) { incoming.beginIncoming(PEER, manifest.copy(dataSchema = 999)) }
        protocol(SyncErrorCode.CONTENT_MISMATCH) { incoming.beginIncoming(PEER, manifest.copy(throughSeq = manifest.throughSeq + 1)) }
        outgoing.setConsent("other-peer", true, true)
        val wrongReceiver = outgoing.prepare("other-peer", "different-database", 0, true)
        protocol(SyncErrorCode.IDENTITY_MISMATCH) { incoming.beginIncoming(PEER, wrongReceiver) }
        protocol(SyncErrorCode.CURSOR_GAP) { outgoing.prepare(PEER, databaseId(receiver), 1) }
        outgoing.acknowledge(PEER, databaseId(receiver), Ack(manifest.exportId, manifest.throughSeq, manifest.manifestHash))
        record(sender, "合成：游标缺口之后", "after-gap")
        val gap = outgoing.prepare(PEER, databaseId(receiver), manifest.throughSeq)
        protocol(SyncErrorCode.CURSOR_GAP) { incoming.beginIncoming(PEER, gap) }
        assertTrue(RecordStore(receiver, clock).snapshot().records.isEmpty())
        assertEquals(0L, incoming.status(PEER).receivedRemoteSeq)
    }

    @Test fun purgeInvalidatesBothStagingDirectionsAndFencePreventsResurrection() = replicas { sender, receiver ->
        val id = record(sender, "合成：待彻删正文", "purge-create")
        val outgoing = SyncExchange(sender)
        val incoming = SyncExchange(receiver)
        val initial = outgoing.prepare(PEER, databaseId(receiver), 0, true)
        stage(outgoing, incoming, initial)
        outgoing.acknowledge(PEER, databaseId(receiver), incoming.commitIncoming(PEER, initial.exportId))
        val stale = incoming.prepare(PEER, databaseId(sender), 0, true)
        val staleParts = List(stale.partCount) { incoming.exportPart(stale.exportId, it) }
        outgoing.beginIncoming(PEER, stale)
        staleParts.forEach { outgoing.receivePart(stale.exportId, it) }
        val oldExport = outgoing.prepare(PEER, databaseId(receiver), initial.throughSeq, true)
        stage(outgoing, incoming, oldExport)
        val store = RecordStore(sender, clock)
        store.moveToTrash(id, SyncRepository(sender).headIds("record", id), "purge-trash")
        store.purge(id, "purge-now")
        protocol(SyncErrorCode.NOT_FOUND) { outgoing.exportPart(oldExport.exportId, 0) }
        protocol(SyncErrorCode.NOT_FOUND) { outgoing.commitIncoming(PEER, stale.exportId) }
        assertEquals(initial.throughSeq, outgoing.status(PEER).ackedLocalSeq)
        incoming.mergeFences(PEER, databaseId(sender), outgoing.fences())
        protocol(SyncErrorCode.NOT_FOUND) { incoming.commitIncoming(PEER, oldExport.exportId) }
        assertEquals(initial.throughSeq, incoming.status(PEER).receivedRemoteSeq)
        assertTrue(RecordStore(receiver, clock).snapshot().records.isEmpty())
        // A delayed, otherwise valid pre-purge full snapshot cannot restore the body.
        outgoing.beginIncoming(PEER, stale)
        staleParts.forEach { outgoing.receivePart(stale.exportId, it) }
        outgoing.commitIncoming(PEER, stale.exportId)
        assertTrue(SyncRepository(sender).isPurged(id))
        assertTrue(RecordStore(sender, clock).snapshot().records.isEmpty())
    }

    @Test fun cleanupDiscardsIncompleteStagingButKeepsUnacknowledgedChangesAndReceipts() = replicas { sender, receiver ->
        val id = record(sender, "合成：中断后重新发送", "restart-create")
        val outgoing = SyncExchange(sender)
        val incoming = SyncExchange(receiver)
        val abandoned = outgoing.prepare(PEER, databaseId(receiver), 0, true)
        stage(outgoing, incoming, abandoned)
        incoming.cleanup()
        outgoing.cleanup()
        protocol(SyncErrorCode.NOT_FOUND) { incoming.commitIncoming(PEER, abandoned.exportId) }
        protocol(SyncErrorCode.NOT_FOUND) { outgoing.exportPart(abandoned.exportId, 0) }
        assertEquals(0L, incoming.status(PEER).receivedRemoteSeq)
        assertEquals(0L, outgoing.status(PEER).ackedLocalSeq)
        val rebuilt = outgoing.prepare(PEER, databaseId(receiver), 0, true)
        stage(outgoing, incoming, rebuilt)
        val ack = incoming.commitIncoming(PEER, rebuilt.exportId)
        incoming.cleanup()
        assertEquals(ack, incoming.commitIncoming(PEER, rebuilt.exportId))
        assertEquals(setOf(id), RecordStore(receiver, clock).snapshot().records.map { it.id }.toSet())
    }

    private fun record(database: WindowsDatabase, text: String, intent: String): String {
        val store = RecordStore(database, clock)
        val opened = store.beginNew()
        return store.save(opened, opened.draft.copy(text = text, local = "2026-01-02 09:00", zone = "UTC", offset = "Z"), intent)
    }

    private fun stage(sender: SyncExchange, receiver: SyncExchange, manifest: ExportManifest) {
        receiver.beginIncoming(PEER, manifest)
        repeat(manifest.partCount) { receiver.receivePart(manifest.exportId, sender.exportPart(manifest.exportId, it)) }
    }

    private fun databaseId(database: WindowsDatabase) = SyncRepository(database).identity().databaseId
    private fun bind(sender: WindowsDatabase, receiver: WindowsDatabase) {
        listOf(sender, receiver).forEach { SyncExchange(it).apply { bindSpace("synthetic-space"); setConsent(PEER, true, true) } }
    }
    private fun protocol(code: SyncErrorCode, action: () -> Unit) {
        assertEquals(code, assertThrows(SyncProtocolException::class.java) { action() }.code)
    }
    private fun replicas(action: (WindowsDatabase, WindowsDatabase) -> Unit) = temporary { root ->
        WindowsDatabase(root.resolve("sender.db")).use { sender -> WindowsDatabase(root.resolve("receiver.db")).use { receiver ->
            bind(sender, receiver)
            action(sender, receiver)
        } }
    }
    private fun temporary(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("liferecord-secure-regression-")
        try { action(root) } finally { root.toFile().deleteRecursively() }
    }
    private companion object { const val PEER = "synthetic-peer" }
}
