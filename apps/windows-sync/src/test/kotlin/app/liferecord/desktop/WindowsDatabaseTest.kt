package app.liferecord.desktop

import app.liferecord.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.*

class WindowsDatabaseTest {
    @Test fun rollbackAndRestartPreserveContentDraftAndHistory() {
        val dir = Files.createTempDirectory("liferecord-synthetic-")
        val path = dir.resolve("fixture.db")
        val clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC)
        try {
            WindowsDatabase(path).use { database ->
                val store = RecordStore(database, clock)
                val opened = store.beginNew()
                val draft = opened.draft.copy(text = "合成：完成阅读", local = "2026-11-02 09:00", zone = "UTC", offset = "Z")
                store.save(opened, draft, "create-synthetic")
                val editing = store.beginEdit(store.snapshot().records.single().id)
                val edit = editing.draft.copy(text = "合成：完成示例")
                val kept = store.keepDraft(editing, edit)
                assertThrows(IllegalStateException::class.java) {
                    database.transaction { store.save(kept, edit, "edit-synthetic"); error("synthetic rollback") }
                }
                assertEquals(draft.text, store.snapshot().records.single().text)
                assertEquals(edit.text, store.snapshot().drafts.single().text)
                assertTrue(store.snapshot().revisions.isEmpty())
                store.save(kept, edit, "edit-synthetic")
            }
            WindowsDatabase(path).use { database ->
                val snapshot = RecordStore(database, clock).snapshot()
                assertEquals("合成：完成示例", snapshot.records.single().text)
                assertEquals("合成：完成阅读", snapshot.revisions.single().beforeText)
                assertTrue(snapshot.drafts.isEmpty())
            }
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun restoredDraftSessionSavesAfterOriginalEditingSessionIsLost() {
        val dir = Files.createTempDirectory("liferecord-restored-draft-")
        val clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC)
        try {
            WindowsDatabase(dir.resolve("fixture.db")).use { database ->
                val store = RecordStore(database, clock)
                val creating = store.beginNew()
                val recordId = store.save(
                    creating,
                    creating.draft.copy(text = "合成：原始事实", local = "2026-11-02 09:00", zone = "UTC", offset = "Z"),
                    "create-restored-draft-fixture",
                )
                val editing = store.beginEdit(recordId)
                val changed = editing.draft.copy(text = "合成：超时后恢复的修改")
                store.keepDraft(editing, changed)

                val restored = store.beginEdit(recordId)
                assertEquals(changed.text, restored.draft.text)
                store.save(restored, restored.draft, "save-restored-draft")

                val snapshot = store.snapshot()
                assertEquals("合成：超时后恢复的修改", snapshot.records.single().text)
                assertTrue(snapshot.drafts.isEmpty())
                assertEquals(1, snapshot.revisions.size)
                assertEquals("合成：原始事实", snapshot.revisions.single().beforeText)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun openCursorSupportsNestedQueriesAndDoesNotLoseOuterPosition() {
        val dir = Files.createTempDirectory("liferecord-cursor-")
        try {
            WindowsDatabase(dir.resolve("fixture.db")).use { database ->
                database.query("SELECT 'first' AS name UNION ALL SELECT 'second'").use { outer ->
                    assertTrue(outer.next())
                    assertEquals("first", outer.string(outer.columnIndex("name")))
                    database.query("SELECT 42").use { inner -> assertTrue(inner.next()); assertEquals(42L, inner.long(0)) }
                    assertTrue(outer.next())
                    assertEquals("second", outer.string(0))
                    assertFalse(outer.next())
                }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
