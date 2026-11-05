package app.liferecord.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GoalReviewPeriodsTest {
    private fun goal(
        start: String = "2027-01-01",
        end: String = "2027-01-21",
        cadenceDays: Int = 7,
        zone: String = "UTC",
    ) = GoalSpec(
        id = "8d459ef3-c80e-4eba-825f-0f0a5928c772",
        title = "合成目标",
        desiredResult = "交付合成结果",
        startDate = start,
        endDate = end,
        reviewZone = zone,
        cadenceDays = cadenceDays,
        milestones = listOf(GoalMilestone("96595bfe-51aa-49a1-8b5e-b035197ae451", "完成合成标准")),
        archived = false,
    )

    @Test fun completedUsesInclusiveNaturalDateWindowsAndExcludesCurrentDay() {
        val goal = goal()

        assertEquals(
            listOf(
                GoalReviewKey(goal.id, "2027-01-01", "2027-01-07"),
                GoalReviewKey(goal.id, "2027-01-08", "2027-01-14"),
            ),
            GoalReviewPeriods.completed(goal, Instant.parse("2027-01-21T23:59:59Z")),
        )
        assertEquals(
            GoalReviewKey(goal.id, "2027-01-15", "2027-01-21"),
            GoalReviewPeriods.latestCompleted(goal, Instant.parse("2027-01-22T04:00:00Z")),
        )
    }

    @Test fun finalWindowIsTruncatedAtGoalEnd() {
        val goal = goal(end = "2027-01-10")
        val now = Instant.parse("2027-01-11T00:30:00Z")

        assertEquals(
            listOf(
                GoalReviewKey(goal.id, "2027-01-01", "2027-01-07"),
                GoalReviewKey(goal.id, "2027-01-08", "2027-01-10"),
            ),
            GoalReviewPeriods.completed(goal, now),
        )
        assertEquals(
            Instant.parse("2027-01-11T01:10:00Z"),
            GoalReviewPeriods.nextDue(goal, now),
        )
        assertEquals(
            Instant.parse("2027-01-11T01:10:00Z"),
            GoalReviewPeriods.nextDue(goal, Instant.parse("2027-01-11T01:10:00Z")),
        )
        assertNull(GoalReviewPeriods.nextDue(goal, Instant.parse("2027-01-11T01:10:00.001Z")))
    }

    @Test fun reviewZoneDeterminesWhetherAWindowIsComplete() {
        val goal = goal(end = "2027-01-07", zone = "America/Los_Angeles")
        val utcJanuaryEighth = Instant.parse("2027-01-08T00:30:00Z")

        assertTrue(GoalReviewPeriods.completed(goal, utcJanuaryEighth).isEmpty())
        assertNull(GoalReviewPeriods.latestCompleted(goal, utcJanuaryEighth))
        assertEquals(
            Instant.parse("2027-01-08T09:10:00Z"),
            GoalReviewPeriods.nextDue(goal, utcJanuaryEighth),
        )
    }

    @Test fun automaticDueIsAt0110WithStandardDstResolution() {
        val goal = goal(start = "2026-10-31", end = "2026-10-31", cadenceDays = 1, zone = "America/New_York")
        val key = GoalReviewKey(goal.id, "2026-10-31", "2026-10-31")

        // 2026-11-01 01:10 occurs twice; atZone resolves the overlap to the earlier offset.
        assertEquals(Instant.parse("2026-11-01T05:10:00Z"), GoalReviewPeriods.due(key, goal))
        assertEquals(
            Instant.parse("2026-11-01T05:10:00Z"),
            GoalReviewPeriods.nextDue(goal, Instant.parse("2026-11-01T04:59:00Z")),
        )
    }

    @Test fun containsAcceptsOnlyCanonicalWindowsForTheGoal() {
        val goal = goal(end = "2027-01-10")

        assertTrue(GoalReviewPeriods.contains(goal, GoalReviewKey(goal.id, "2027-01-01", "2027-01-07")))
        assertTrue(GoalReviewPeriods.contains(goal, GoalReviewKey(goal.id, "2027-01-08", "2027-01-10")))
        assertFalse(GoalReviewPeriods.contains(goal, GoalReviewKey(goal.id, "2027-01-02", "2027-01-08")))
        assertFalse(GoalReviewPeriods.contains(goal, GoalReviewKey(goal.id, "2027-01-08", "2027-01-09")))
        assertFalse(
            GoalReviewPeriods.contains(
                goal,
                GoalReviewKey("7f911a14-6df1-4687-b184-3c26f47f9874", "2027-01-01", "2027-01-07"),
            ),
        )
    }

    @Test fun latestAndNextUseDirectArithmeticAcrossLongGoals() {
        val goal = goal(start = "1900-01-01", end = "2500-12-31", cadenceDays = 13)
        val now = Instant.parse("2400-06-15T00:30:00Z")
        val latest = GoalReviewPeriods.latestCompleted(goal, now)!!

        assertTrue(GoalReviewPeriods.contains(goal, latest))
        assertTrue(latest.throughDate < "2400-06-15")
        val next = GoalReviewPeriods.nextDue(goal, now)!!
        assertTrue(next.isAfter(now))
        assertTrue(next.isBefore(now.plusSeconds(14L * 24L * 60L * 60L)))
    }
}
