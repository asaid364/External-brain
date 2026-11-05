package app.liferecord.desktop

import app.liferecord.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.*
import java.util.UUID

class DraftForkTest {
    @Test fun explicitResidualForkIsAnIdempotentNewDraftNotAFormalRecord() {
        val root=Files.createTempDirectory("liferecord-fork-")
        val clock=Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"),ZoneOffset.UTC)
        try { WindowsDatabase(root.resolve("owned.db")).use { database ->
            val store=RecordStore(database,clock)
            val session=store.beginNew()
            val draft=session.draft.copy(text="合成残留文字",local="2026-11-02 09:00",zone="UTC",offset="Z")
            store.keepDraft(session,draft)
            val state=store.sync.readEntity("draft-slot","new")
            val head=state.heads.single()
            val intent=UUID.randomUUID().toString()
            val fork=store.forkDraft("new",head.id,setOf(head.id),intent)
            val retried=store.forkDraft("new",head.id,setOf(head.id),intent)
            assertEquals(fork.draftId,retried.draftId)
            assertEquals(fork.targetRecordId,retried.targetRecordId)
            assertNotEquals(session.targetRecordId,fork.targetRecordId)
            assertEquals("合成残留文字",store.snapshot().drafts.single().text)
            assertTrue(store.snapshot().records.isEmpty())
            assertFalse(store.sync.readEntity("draft-slot","new").unresolved)
        } } finally { root.toFile().deleteRecursively() }
    }
}
