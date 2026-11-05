package app.liferecord

import app.liferecord.core.*

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var store: RecordStore
    private lateinit var database: AndroidDatabase
    private var fail: String? = null
    private val clock = java.time.Clock.fixed(java.time.Instant.parse("2027-01-01T00:00:00Z"), java.time.ZoneId.of("Asia/Shanghai"))

    private fun newDraft(text: String = "合成：完成练习\n计划明天核对"): Pair<EditSession, Draft> {
        val session = store.beginNew()
        return session to session.draft.copy(text = text, local = "2026-01-02 09:30", zone = "Asia/Shanghai", offset = "+08:00")
    }

    private fun saveNew(text: String = "合成：完成练习\n计划明天核对"): String {
        val (session, draft) = newDraft(text)
        return store.save(session, draft, UUID.randomUUID().toString())
    }

    @Before fun setup() {
        name = "test-${UUID.randomUUID()}.db"
        database = AndroidDatabase(context, name)
        store = RecordStore(database, clock) { if (it == fail) throw SqlFailure("synthetic storage failure") }
    }
    @After fun cleanup() { database.close(); context.deleteDatabase(name) }

    @Test fun corruptDatabaseIsNotReplacedWithEmptyData() {
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val original = "synthetic-corrupt-database-content".toByteArray()
        file.writeBytes(original)
        assertThrows(SqlFailure::class.java) { store.snapshot() }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun sameIntentRetryCorrectionAndRestartKeepProvenance() {
        val (opened, draft) = newDraft()
        val session = store.keepDraft(opened, draft)
        val createIntent = UUID.randomUUID().toString()
        store.save(session, draft, createIntent)
        store.save(session, draft, createIntent)
        val original = store.snapshot().records.single()
        assertEquals(draft.text, original.text)
        assertTrue(store.snapshot().drafts.isEmpty())
        assertTrue(store.snapshot().revisions.isEmpty())

        val editing = store.beginEdit(original.id)
        val correction = editing.draft.copy(text = "合成：更正事实", local = "2026-01-01 22:00")
        val kept = store.keepDraft(editing, correction)
        val editIntent = UUID.randomUUID().toString()
        store.save(kept, correction, editIntent)
        store.save(kept, correction, editIntent)

        database.close(); database = AndroidDatabase(context, name); store = RecordStore(database)
        val reloaded = store.snapshot()
        assertEquals(original.created, reloaded.records.single().created)
        assertEquals("2026-01-01T22:00", reloaded.records.single().time.local)
        assertEquals(original.text, reloaded.revisions.single().beforeText)
        assertEquals(correction.text, reloaded.revisions.single().afterText)
    }

    @Test fun failedSaveKeepsDraftAndRollsBackBodyHistoryAndIntent() {
        val (opened, draft) = newDraft()
        val kept = store.keepDraft(opened, draft)
        val createIntent = UUID.randomUUID().toString()
        fail = "save"
        assertThrows(SqlFailure::class.java) { store.save(kept, draft, createIntent) }
        assertTrue(store.snapshot().records.isEmpty())
        assertEquals(draft.text, store.snapshot().drafts.single().text)
        fail = null
        store.save(kept, draft, createIntent)

        val original = store.snapshot().records.single()
        val editing = store.beginEdit(original.id)
        val correction = editing.draft.copy(text = "合成更正")
        val correctionSession = store.keepDraft(editing, correction)
        fail = "save"
        assertThrows(SqlFailure::class.java) { store.save(correctionSession, correction, UUID.randomUUID().toString()) }
        assertEquals(original, store.snapshot().records.single())
        assertTrue(store.snapshot().revisions.isEmpty())
        assertEquals(correction.text, store.snapshot().drafts.single().text)
    }

    @Test fun trashRestoreAndPurgeAreAtomicWithEditDraft() {
        val id = saveNew()
        val editing = store.beginEdit(id)
        val pending = editing.draft.copy(text = "尚未保存的合成修改")
        store.keepDraft(editing, pending)
        val activeHeads = store.sync.headIds("record", id)

        fail = "delete"
        assertThrows(SqlFailure::class.java) { store.moveToTrash(id, activeHeads, UUID.randomUUID().toString()) }
        assertNull(store.snapshot().records.single().deleted)
        fail = null
        store.moveToTrash(id, activeHeads, UUID.randomUUID().toString())
        val deleted = store.snapshot().records.single()
        assertNotNull(deleted.deleted)
        assertEquals(pending.text, store.snapshot().drafts.single().text)
        assertThrows(IllegalArgumentException::class.java) { store.keepDraft(editing, pending.copy(text = "不允许")) }

        val deletedHeads = store.sync.headIds("record", id)
        fail = "restore"
        assertThrows(SqlFailure::class.java) { store.restore(id, deletedHeads, UUID.randomUUID().toString()) }
        assertEquals(deleted, store.snapshot().records.single())
        fail = null
        store.restore(id, deletedHeads, UUID.randomUUID().toString())
        assertNull(store.snapshot().records.single().deleted)

        val resumed = store.beginEdit(id)
        val corrected = resumed.draft.copy(text = pending.text)
        store.save(resumed, corrected, UUID.randomUUID().toString())
        val next = store.beginEdit(id)
        store.keepDraft(next, next.draft.copy(text = "下一份草稿"))
        store.moveToTrash(id, store.sync.headIds("record", id), UUID.randomUUID().toString())
        val purgeIntent = UUID.randomUUID().toString()
        fail = "purge"
        assertThrows(SqlFailure::class.java) { store.purge(id, purgeIntent) }
        assertEquals(1, store.snapshot().records.size)
        assertEquals(1, store.snapshot().revisions.size)
        assertEquals(1, store.snapshot().drafts.size)
        fail = null
        store.purge(id, purgeIntent)
        store.purge(id, purgeIntent)
        val empty = store.snapshot()
        assertTrue(empty.records.isEmpty()); assertTrue(empty.revisions.isEmpty()); assertTrue(empty.drafts.isEmpty())
    }

    @Test fun draftFailureDiscardAndReadFailureNeverDestroySavedData() {
        val (opened, draft) = newDraft()
        val kept = store.keepDraft(opened, draft)
        fail = "draft"
        assertThrows(SqlFailure::class.java) { store.keepDraft(kept, draft.copy(text = "另一段")) }
        assertEquals(draft.text, store.snapshot().drafts.single().text)
        val discardIntent = UUID.randomUUID().toString()
        fail = "discard"
        assertThrows(SqlFailure::class.java) { store.discardDraft(kept, discardIntent) }
        assertEquals(draft.text, store.snapshot().drafts.single().text)
        fail = "snapshot"
        assertThrows(SqlFailure::class.java) { store.snapshot() }
        fail = null
        assertEquals(draft.text, store.snapshot().drafts.single().text)
        store.discardDraft(kept, discardIntent)
        assertTrue(store.snapshot().drafts.isEmpty())
    }

    @Test fun noChangeAndBlankDraftAreNotVersions() {
        val blank = store.beginNew()
        store.keepDraft(blank, blank.draft.copy(text = "  \n"))
        assertTrue(store.snapshot().drafts.isEmpty())
        val id = saveNew()
        val same = store.beginEdit(id)
        store.keepDraft(same, same.draft)
        store.save(same, same.draft, UUID.randomUUID().toString())
        assertTrue(store.snapshot().drafts.isEmpty())
        assertTrue(store.snapshot().revisions.isEmpty())
    }
}
