package app.liferecord.desktop

import app.liferecord.core.DraftTerminal
import app.liferecord.core.DraftIdentityMismatch
import app.liferecord.core.DraftTerminalMode
import app.liferecord.core.EntityState
import app.liferecord.core.PurgedRecord
import app.liferecord.core.RecordStore
import app.liferecord.core.SyncRepository
import app.liferecord.core.SqlDatabase
import app.liferecord.core.SyncStateSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CausalReplicationTest {
    private class Replica(root: Path, name: String) : Closeable {
        val database = WindowsDatabase(root.resolve("$name.db"))
        val sync = SyncRepository(database)
        init {
            database.exec("CREATE TABLE causal_test_projection(kind TEXT NOT NULL,entity_key TEXT NOT NULL,payloads TEXT NOT NULL,unresolved INTEGER NOT NULL,PRIMARY KEY(kind,entity_key))")
        }
        fun merge(snapshot: SyncStateSnapshot) = sync.merge(snapshot)
        fun resolve(kind: String, key: String, payload: JsonElement, displayedHeads: Set<String>, intentId: String) =
            sync.resolve(kind, key, payload, displayedHeads, intentId, ::projectState)
        fun projected(kind: String, key: String): Pair<String, Boolean>? = database.query(
            "SELECT payloads,unresolved FROM causal_test_projection WHERE kind=? AND entity_key=?", listOf(kind, key),
        ).use { if (it.next()) it.string(0) to (it.long(1) != 0L) else null }
        private fun projectState(db: SqlDatabase, state: EntityState) {
            if (state.heads.isEmpty()) {
                db.exec("DELETE FROM causal_test_projection WHERE kind=? AND entity_key=?", listOf(state.kind, state.key))
                return
            }
            val payloads = state.heads.joinToString("\n") { it.payload.toString() }
            val args = listOf(payloads, if (state.unresolved) 1 else 0, state.kind, state.key)
            if (db.exec("UPDATE causal_test_projection SET payloads=?,unresolved=? WHERE kind=? AND entity_key=?", args) == 0) {
                db.exec("INSERT INTO causal_test_projection(payloads,unresolved,kind,entity_key) VALUES(?,?,?,?)", args)
            }
        }
        override fun close() = database.close()
    }

    private fun payload(text: String) = buildJsonObject { put("text", text) }
    private fun text(state: EntityState): Set<String> = state.heads.mapTo(linkedSetOf()) {
        (it.payload as JsonObject).getValue("text").toString().removeSurrounding("\"")
    }

    @Test
    fun realStoresChoosingExistingCandidateRetiresDisplayedConflictAndConverges() {
        val root = Files.createTempDirectory("liferecord-real-resolution-")
        val clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC)
        try {
            Replica(root, "phone").use { phone ->
                Replica(root, "desktop").use { desktop ->
                    val phoneStore = RecordStore(phone.database, clock)
                    val desktopStore = RecordStore(desktop.database, clock)
                    val creating = phoneStore.beginNew()
                    val recordId = phoneStore.save(
                        creating,
                        creating.draft.copy(text = "A", local = "2026-01-02 09:00", zone = "UTC", offset = "Z"),
                        "create-a",
                    )
                    desktop.merge(phone.sync.snapshot())

                    val openedA = phoneStore.beginEdit(recordId)
                    val desktopEdit = desktopStore.beginEdit(recordId)
                    desktopStore.save(desktopEdit, desktopEdit.draft.copy(text = "B"), "desktop-b")
                    phone.merge(desktop.sync.snapshot())
                    phoneStore.save(openedA, openedA.draft.copy(text = "C"), "phone-c")

                    val conflict = phone.sync.readEntity("record", recordId)
                    assertEquals(setOf("B", "C"), text(conflict))
                    val displayed = conflict.heads.mapTo(linkedSetOf()) { it.id }
                    val chosenB = conflict.heads.single { (it.payload as JsonObject).getValue("text").toString() == "\"B\"" }
                    phoneStore.resolveRecord(recordId, chosenB.id, displayed, "choose-b")
                    desktop.merge(phone.sync.snapshot())
                    phone.merge(desktop.sync.snapshot())

                    assertEquals(1, phone.sync.readEntity("record", recordId).heads.size)
                    assertEquals(1, desktop.sync.readEntity("record", recordId).heads.size)
                    val phoneSnapshot = phoneStore.snapshot()
                    val desktopSnapshot = desktopStore.snapshot()
                    assertEquals("B", phoneSnapshot.records.single().text)
                    assertEquals(phoneSnapshot.records.single(), desktopSnapshot.records.single())
                    assertEquals(2, phoneSnapshot.revisions.size)
                    assertEquals(phoneSnapshot.revisions.mapTo(linkedSetOf()) { it.id }, desktopSnapshot.revisions.mapTo(linkedSetOf()) { it.id })
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun openedAThenUnseenBThenCAndManualResolutionConverge() {
        val root = Files.createTempDirectory("liferecord-causal-record-")
        try {
            Replica(root, "phone").use { phone ->
                Replica(root, "desktop").use { desktop ->
                    phone.sync.writeLocalHead("test-record", "r", payload("A"), emptySet(), "phone-create")
                    desktop.merge(phone.sync.snapshot())
                    val openedOnPhone = phone.sync.headIds("test-record", "r")
                    val openedOnDesktop = desktop.sync.headIds("test-record", "r")

                    desktop.sync.writeLocalHead("test-record", "r", payload("B"), openedOnDesktop, "desktop-edit")
                    phone.merge(desktop.sync.snapshot())
                    phone.sync.writeLocalHead("test-record", "r", payload("C"), openedOnPhone, "phone-edit")

                    assertEquals(setOf("B", "C"), text(phone.sync.readEntity("test-record", "r")))
                    desktop.merge(phone.sync.snapshot())
                    assertEquals(setOf("B", "C"), text(desktop.sync.readEntity("test-record", "r")))

                    val candidates = phone.sync.headIds("test-record", "r")
                    phone.resolve("test-record", "r", payload("resolved B+C"), candidates, "phone-resolve")
                    desktop.merge(phone.sync.snapshot())
                    phone.merge(desktop.sync.snapshot())

                    assertEquals(setOf("resolved B+C"), text(phone.sync.readEntity("test-record", "r")))
                    assertEquals(setOf("resolved B+C"), text(desktop.sync.readEntity("test-record", "r")))
                    assertFalse(phone.sync.readEntity("test-record", "r").unresolved)
                    val projected = phone.projected("test-record", "r")
                    assertTrue(projected!!.first.contains("resolved B+C"))
                    assertFalse(projected.second)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentNewDraftsKeepTargetsAndLateAutosaveRemainsAfterTerminal() {
        val root = Files.createTempDirectory("liferecord-causal-draft-")
        try {
            Replica(root, "phone").use { phone ->
                Replica(root, "desktop").use { desktop ->
                    fun draft(id: String, target: String, text: String) = buildJsonObject {
                        put("id", id); put("targetRecordId", target); put("text", text)
                    }
                    val phoneDraft = draft("phone-draft", "phone-target", "手机输入")
                    phone.sync.writeLocalHead("draft-slot", "new", phoneDraft, emptySet(), "phone-draft")
                    desktop.sync.writeLocalHead("draft-slot", "new", draft("desktop-draft", "desktop-target", "电脑输入"), emptySet(), "desktop-draft")
                    phone.merge(desktop.sync.snapshot())
                    desktop.merge(phone.sync.snapshot())

                    val conflict = phone.sync.readEntity("draft-slot", "new")
                    val phoneDraftHash = conflict.heads.single { it.payload == phoneDraft }.payloadHash
                    assertTrue(conflict.unresolved)
                    val targets = conflict.heads.mapTo(linkedSetOf()) { (it.payload as JsonObject).getValue("targetRecordId").toString().removeSurrounding("\"") }
                    assertEquals(setOf("phone-target", "desktop-target"), targets)

                    val consumed = phone.sync.headIds("draft-slot", "new")
                    phone.database.transaction { db ->
                        phone.sync.addDraftTerminal(db, DraftTerminal("phone-draft", "phone-terminal", DraftTerminalMode.SAVED, "phone-target", consumed, phoneDraftHash))
                    }
                    desktop.sync.writeLocalHead("draft-slot", "new", draft("phone-draft", "phone-target", "迟到的不同输入"), emptySet(), "late-autosave")
                    phone.merge(desktop.sync.snapshot())

                    assertTrue(phone.sync.snapshot().draftTerminals.any { it.draftId == "phone-draft" && it.mode == DraftTerminalMode.SAVED })
                    assertTrue(text(phone.sync.readEntity("draft-slot", "new")).contains("迟到的不同输入"))
                    assertTrue(phone.sync.readEntity("draft-slot", "new").unresolved)
                    desktop.merge(phone.sync.snapshot())
                    assertTrue(desktop.sync.snapshot().draftTerminals.any { it.draftId == "phone-draft" && it.mode == DraftTerminalMode.SAVED })
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun sameDraftIdCannotRebindTargetAndRejectedBatchLeavesReplicaUntouched() {
        val root = Files.createTempDirectory("liferecord-draft-identity-")
        try {
            Replica(root, "phone").use { phone ->
                Replica(root, "desktop").use { desktop ->
                    fun draft(target: String) = buildJsonObject {
                        put("id", "same-generation"); put("targetRecordId", target); put("text", "合成输入")
                    }
                    phone.sync.writeLocalHead("draft-slot", "new", draft("target-one"), emptySet(), "phone-generation")
                    desktop.sync.writeLocalHead("draft-slot", "new", draft("target-two"), emptySet(), "desktop-generation")
                    val before = phone.sync.readEntity("draft-slot", "new")

                    assertThrows(DraftIdentityMismatch::class.java) { phone.merge(desktop.sync.snapshot()) }
                    assertEquals(before, phone.sync.readEntity("draft-slot", "new"))
                    assertEquals(setOf("target-one"), before.heads.mapTo(linkedSetOf()) { (it.payload as JsonObject).getValue("targetRecordId").toString().removeSurrounding("\"") })
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun purgeFenceRejectsOldBusinessSnapshotAndLeavesNoBodyMetadata() {
        val root = Files.createTempDirectory("liferecord-causal-purge-")
        val clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC)
        try {
            Replica(root, "phone").use { phone ->
                Replica(root, "desktop").use { desktop ->
                    val phoneStore = RecordStore(phone.database, clock)
                    val desktopStore = RecordStore(desktop.database, clock)
                    val opened = phoneStore.beginNew()
                    val recordId = phoneStore.save(
                        opened,
                        opened.draft.copy(text = "sensitive old body", local = "2026-01-02 09:00", zone = "UTC", offset = "Z"),
                        "create",
                    )
                    val oldSnapshot = phone.sync.snapshot()
                    desktop.merge(oldSnapshot)
                    assertEquals("sensitive old body", desktopStore.snapshot().records.single().text)

                    phoneStore.moveToTrash(recordId, phone.sync.headIds("record", recordId), "trash")
                    phoneStore.purge(recordId, "purge")
                    desktop.merge(phone.sync.snapshot())
                    desktop.merge(oldSnapshot)

                    assertTrue(desktop.sync.isPurged(recordId))
                    assertTrue(desktop.sync.readEntity("record", recordId).heads.isEmpty())
                    assertTrue(desktopStore.snapshot().records.isEmpty())
                    val pattern = "%sensitive old body%"
                    assertEquals(0L, desktop.database.query("SELECT COUNT(*) FROM sync_heads WHERE payload_json LIKE ?", listOf(pattern)).use { check(it.next()); it.long(0) })
                    assertEquals(0L, desktop.database.query("SELECT COUNT(*) FROM sync_history WHERE payload_json LIKE ?", listOf(pattern)).use { check(it.next()); it.long(0) })
                    assertThrows(PurgedRecord::class.java) {
                        desktop.sync.writeLocalHead("record", recordId, payload("resurrected"), emptySet(), "resurrection")
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
