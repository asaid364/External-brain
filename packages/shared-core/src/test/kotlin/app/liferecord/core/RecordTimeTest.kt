package app.liferecord.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RecordTimeTest {
    private val now = Instant.parse("2026-11-03T00:00:00Z")
    private fun draft(local: String, zone: String = "Asia/Tokyo", offset: String? = null) = Draft("synthetic", null, "合成计划：明天练习", local, zone, offset)
    @Test fun originalLocalDateSurvivesZoneChange() {
        val event = RecordTime.resolve(draft("2026-11-02 00:30"), now)
        assertEquals("2026-11-02T00:30", event.local)
        assertEquals("Asia/Tokyo", event.zone)
        assertEquals("+09:00", event.offset)
        assertEquals(Instant.parse("2026-11-01T15:30:00Z").toEpochMilli(), event.instant)
    }
    @Test fun rejectsFutureByInstantNotClockText() {
        val future = draft("2026-11-03 10:00", "Asia/Tokyo")
        assertThrows(IllegalArgumentException::class.java) { RecordTime.resolve(future, now) }
        val past = draft("2026-11-02 23:59", "America/New_York")
        assertEquals("-05:00", RecordTime.resolve(past, Instant.parse("2026-11-03T06:00:00Z")).offset)
    }
    @Test fun rejectsGapAndRequiresOverlapChoice() {
        assertThrows(IllegalArgumentException::class.java) { RecordTime.resolve(draft("2026-03-08 02:30", "America/New_York"), now) }
        val overlap = draft("2026-11-01 01:30", "America/New_York")
        assertThrows(IllegalArgumentException::class.java) { RecordTime.resolve(overlap, now) }
        val first = RecordTime.resolve(overlap.copy(offset = "-04:00"), now)
        val second = RecordTime.resolve(overlap.copy(offset = "-05:00"), now)
        assertEquals(3600000L, second.instant - first.instant)
        assertEquals(first.local, second.local)
    }
    @Test fun invalidCalendarDateIsNotNormalized() {
        assertThrows(IllegalArgumentException::class.java) { RecordTime.resolve(draft("2026-02-30 10:00"), now) }
    }
}
