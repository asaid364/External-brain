package app.liferecord

import app.liferecord.core.GoalMilestone
import app.liferecord.core.GoalReviewKey
import app.liferecord.core.GoalSpec
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class GoalReviewScheduleTest {
    private fun goal(end: String = "2027-02-04", zone: String = "UTC") = GoalSpec(
        UUID.randomUUID().toString(), "合成交付", "合成结果", "2027-01-01", end, zone, 7,
        listOf(GoalMilestone(UUID.randomUUID().toString(), "可核验结果")), false,
    )
    private fun clock(at: String) = Clock.fixed(Instant.parse(at), ZoneOffset.UTC)

    @Test fun fourMissedWindowsSelectOnlyFourthAndRepeatedResumeDoesNotWalkBackwards() {
        val goal = goal()
        val now = clock("2027-01-29T04:00:00Z")
        val latest = GoalReviewKey(goal.id, "2027-01-22", "2027-01-28")
        repeat(4) {
            assertEquals(latest, GoalReviewSchedule.plan(goal, now, true)?.key)
        }
        // A paid/unknown/failed/cancelled attempt or a synchronized review blocks this window.
        val queried = mutableListOf<GoalReviewKey>()
        val next = GoalReviewSchedule.plan(goal, now, true, blocked = { queried += it; true })!!
        assertEquals(listOf(latest), queried)
        assertEquals(GoalReviewKey(goal.id, "2027-01-29", "2027-02-04"), next.key)
        assertEquals(Instant.parse("2027-02-05T01:10:00Z"), next.at)
        // The fifth period can be selected normally when its own due time arrives.
        assertEquals(next.key, GoalReviewSchedule.plan(goal, clock("2027-02-05T04:00:00Z"), true)?.key)
    }

    @Test fun completeAtMidnightWaitsUntil0110AndNeverFallsBackToOlderPeriod() {
        val goal = goal()
        val before = GoalReviewSchedule.plan(goal, clock("2027-01-29T00:30:00Z"), true)!!
        assertEquals("2027-01-22", before.key.fromDate)
        assertEquals(Instant.parse("2027-01-29T01:10:00Z"), before.at)
        assertEquals(before, GoalReviewSchedule.plan(goal, clock("2027-01-29T01:10:00Z"), true))
        assertEquals(Instant.parse("2027-01-29T02:00:00Z"),
            GoalReviewSchedule.plan(goal, clock("2027-01-29T02:00:00Z"), true)?.at)
    }

    @Test fun noEvidenceWaitsForNextPeriodButContentMayFreelyRecheckLatest() {
        val goal = goal()
        val now = clock("2027-01-29T04:00:00Z")
        val afterWorker = GoalReviewSchedule.plan(goal, now, true, skipCurrent = true)!!
        assertEquals("2027-01-29", afterWorker.key.fromDate)
        val afterContent = GoalReviewSchedule.plan(goal, now, true)!!
        assertEquals("2027-01-22", afterContent.key.fromDate)
        assertEquals(now.instant(), afterContent.at)
        assertNull(GoalReviewSchedule.plan(goal("2027-01-28"), now, true, skipCurrent = true))
    }

    @Test fun offlineOrCooldownOnlyDefersTheSameUnsentLatestWindow() {
        val goal = goal()
        val now = clock("2027-01-29T04:00:00Z")
        val retry = Instant.parse("2027-01-29T04:30:00Z")
        val delayed = GoalReviewSchedule.plan(goal, now, true, retryNotBefore = retry)!!
        assertEquals("2027-01-22", delayed.key.fromDate)
        assertEquals(retry, delayed.at)
        // Sticky paid risk wins even when a retry time exists.
        assertEquals("2027-01-29", GoalReviewSchedule.plan(goal, now, true,
            blocked = { true }, retryNotBefore = retry)?.key?.fromDate)
    }

    @Test fun disabledArchivedAndLastCompletedWindowDoNotLeavePaidWork() {
        val goal = goal("2027-01-28")
        val now = clock("2027-01-29T04:00:00Z")
        assertNull(GoalReviewSchedule.plan(goal, now, false))
        assertNull(GoalReviewSchedule.plan(goal.copy(archived = true), now, true))
        assertNull(GoalReviewSchedule.plan(goal, now, true, blocked = { true }))
        assertEquals("2027-01-22", GoalReviewSchedule.plan(goal, now, true)?.key?.fromDate)
    }

    @Test fun firstWindowShortFinalWindowAndGoalZoneControlDueTime() {
        val goal = goal("2027-01-10", "America/New_York")
        val first = GoalReviewSchedule.plan(goal, clock("2027-01-01T00:00:00Z"), true)!!
        assertEquals(GoalReviewKey(goal.id, "2027-01-01", "2027-01-07"), first.key)
        assertEquals(Instant.parse("2027-01-08T06:10:00Z"), first.at)
        val final = GoalReviewSchedule.plan(goal, clock("2027-01-11T05:30:00Z"), true)!!
        assertEquals(GoalReviewKey(goal.id, "2027-01-08", "2027-01-10"), final.key)
        assertEquals(Instant.parse("2027-01-11T06:10:00Z"), final.at)
    }

    @Test fun dailyCadenceUsesDstZoneRatherThanFixed24HourDelay() {
        val goal = goal().copy(startDate = "2027-03-13", endDate = "2027-03-16",
            cadenceDays = 1, reviewZone = "America/New_York")
        val next = GoalReviewSchedule.plan(goal, clock("2027-03-14T07:00:00Z"), true, skipCurrent = true)!!
        assertEquals(GoalReviewKey(goal.id, "2027-03-14", "2027-03-14"), next.key)
        assertEquals(Instant.parse("2027-03-15T05:10:00Z"), next.at)
    }
}
