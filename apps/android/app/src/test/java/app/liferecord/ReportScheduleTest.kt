package app.liferecord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReportScheduleTest {
    @Test
    fun scheduleWindowAndNextDayFollowSelectedZone() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun at(time: String) = ZonedDateTime.of(LocalDate.parse("2026-11-02"), LocalTime.parse(time), zone)

        assertFalse(ReportSchedule.inWindow(at("00:09")))
        assertTrue(ReportSchedule.inWindow(at("00:10")))
        assertTrue(ReportSchedule.inWindow(at("00:59")))
        assertFalse(ReportSchedule.inWindow(at("01:00")))
        assertEquals(at("00:10"), ReportSchedule.next(at("00:09")))
        assertEquals(at("00:10").plusDays(1), ReportSchedule.next(at("00:10")))
    }
}
