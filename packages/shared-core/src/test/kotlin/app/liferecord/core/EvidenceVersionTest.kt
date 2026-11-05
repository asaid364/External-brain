package app.liferecord.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceVersionTest {
    private val record = Record(
        id = "record-1",
        text = "合成：完成练习",
        time = EventTime("2026-01-02T09:30", "Asia/Shanghai", "+08:00", 1767317400000),
        created = "2026-01-02T10:00+08:00[Asia/Shanghai]",
        modified = null,
        deleted = null,
    )

    @Test fun exactLegacyAliasRequiresWholeTokenRecordAndCurrentVersion() {
        val legacy = EvidenceVersion.legacyV2Source(record, reportRevision = 4, reportLifecycle = 2)
        val current = EvidenceVersion.source(record, setOf("actor/7/head"), setOf("actor/1/life"))
        val alias = SourceAlias("origin", record.id, legacy, current)

        assertTrue(EvidenceVersion.exactMatch(legacy, current, listOf(alias), record.id))
        assertFalse(EvidenceVersion.exactMatch("2:${"0".repeat(64)}", current, listOf(alias), record.id))
        assertFalse(EvidenceVersion.exactMatch(legacy, "v3:${"1".repeat(64)}:${"2".repeat(64)}", listOf(alias), record.id))
        assertFalse(EvidenceVersion.exactMatch(legacy, current, listOf(alias), "another-record"))
    }

    @Test fun onlyExactlyCurrentLegacyInputHashIsPromoted() {
        val source = ReportSource(record.id, EvidenceVersion.legacyV2Source(record, 0, 0))
        val answered = ReportQuestion("answered", listOf(record.id), "结果？", answer = "已完成", applicable = true)
        val skipped = ReportQuestion("skipped", listOf(record.id), "计划？", skipped = true, applicable = false)
        val legacyCurrent = EvidenceVersion.legacyV2InputHash(listOf(source), listOf(answered, skipped))
        val v3Current = EvidenceVersion.inputHash(listOf(source.copy(version = "v3:${"1".repeat(64)}:${"2".repeat(64)}")), listOf(answered, skipped))

        assertEquals(v3Current, EvidenceVersion.preserveLegacyInputHash(legacyCurrent, legacyCurrent, v3Current))
        assertEquals("legacy-v2:stale-digest", EvidenceVersion.preserveLegacyInputHash("stale-digest", legacyCurrent, v3Current))
        assertEquals("legacy-v2:already", EvidenceVersion.preserveLegacyInputHash("legacy-v2:already", legacyCurrent, v3Current))
    }
}
