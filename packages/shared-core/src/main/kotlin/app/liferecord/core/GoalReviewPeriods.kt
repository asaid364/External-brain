package app.liferecord.core

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Calendar windows used for goal reviews. Window dates are inclusive. */
object GoalReviewPeriods {
    private val automaticTime = LocalTime.of(1, 10)

    fun completed(goal: GoalSpec, now: Instant): List<GoalReviewKey> {
        val schedule = Schedule.from(goal)
        val today = now.atZone(schedule.zone).toLocalDate()
        val result = mutableListOf<GoalReviewKey>()
        var index = 0L
        while (true) {
            val key = schedule.keyAt(index) ?: break
            if (!LocalDate.parse(key.throughDate).isBefore(today)) break
            result += key
            index++
        }
        return result
    }

    /** Returns the first automatic review time at or after [now]. */
    fun nextDue(goal: GoalSpec, now: Instant): Instant? {
        val schedule = Schedule.from(goal)
        val latest = latestCompleted(schedule, now)
        if (latest == null) return schedule.due(schedule.keyAt(0L)!!)

        val latestDue = schedule.due(latest)
        if (!latestDue.isBefore(now)) return latestDue

        val nextFrom = LocalDate.parse(latest.throughDate).plusDays(1L)
        if (nextFrom.isAfter(schedule.end)) return null
        return schedule.due(schedule.keyStarting(nextFrom))
    }

    fun latestCompleted(goal: GoalSpec, now: Instant): GoalReviewKey? =
        latestCompleted(Schedule.from(goal), now)

    fun due(key: GoalReviewKey, goal: GoalSpec): Instant {
        val schedule = Schedule.from(goal)
        require(schedule.contains(key)) { "回顾窗口不属于目标周期" }
        return schedule.due(key)
    }

    fun contains(goal: GoalSpec, key: GoalReviewKey): Boolean = Schedule.from(goal).contains(key)

    private fun latestCompleted(schedule: Schedule, now: Instant): GoalReviewKey? {
        val today = now.atZone(schedule.zone).toLocalDate()
        if (!today.isAfter(schedule.start)) return null

        val index = if (schedule.end.isBefore(today)) {
            ChronoUnit.DAYS.between(schedule.start, schedule.end) / schedule.cadenceDays
        } else {
            val completedCount = ChronoUnit.DAYS.between(schedule.start, today) / schedule.cadenceDays
            if (completedCount == 0L) return null
            completedCount - 1L
        }
        return schedule.keyAt(index)
    }

    private data class Schedule(
        val goalId: String,
        val start: LocalDate,
        val end: LocalDate,
        val zone: ZoneId,
        val cadenceDays: Long,
    ) {
        fun keyAt(index: Long): GoalReviewKey? {
            require(index >= 0L) { "回顾周期序号不能为负数" }
            val from = start.plusDays(Math.multiplyExact(index, cadenceDays))
            if (from.isAfter(end)) return null
            return keyStarting(from)
        }

        fun keyStarting(from: LocalDate): GoalReviewKey {
            val remainingDays = ChronoUnit.DAYS.between(from, end)
            val through = if (cadenceDays - 1L >= remainingDays) end else from.plusDays(cadenceDays - 1L)
            return GoalReviewKey(goalId, from.toString(), through.toString())
        }

        fun due(key: GoalReviewKey): Instant =
            LocalDate.parse(key.throughDate).plusDays(1L).atTime(automaticTime).atZone(zone).toInstant()

        fun contains(key: GoalReviewKey): Boolean {
            if (key.goalId != goalId) return false
            return try {
                val from = LocalDate.parse(key.fromDate)
                val through = LocalDate.parse(key.throughDate)
                if (from.isBefore(start) || from.isAfter(end)) return false
                val offset = ChronoUnit.DAYS.between(start, from)
                offset % cadenceDays == 0L && keyStarting(from).throughDate == through.toString()
            } catch (_: DateTimeException) {
                false
            }
        }

        companion object {
            fun from(goal: GoalSpec): Schedule {
                val start = LocalDate.parse(goal.startDate)
                val end = LocalDate.parse(goal.endDate)
                require(!end.isBefore(start)) { "目标截止日期不能早于开始日期" }
                require(goal.cadenceDays > 0) { "回顾周期必须为正整数" }
                val totalDays = ChronoUnit.DAYS.between(start, end) + 1L
                require(goal.cadenceDays.toLong() <= totalDays) { "回顾周期不能超过目标总自然日数" }
                return Schedule(goal.id, start, end, ZoneId.of(goal.reviewZone), goal.cadenceDays.toLong())
            }
        }
    }
}
