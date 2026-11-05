package app.liferecord.desktop

import app.liferecord.core.GoalMilestone
import app.liferecord.core.GoalRepository
import app.liferecord.core.GoalReviewCoordinationError
import app.liferecord.core.GoalReviewCoordinationException
import app.liferecord.core.GoalReviewCoordinator
import app.liferecord.core.GoalReviewKey
import app.liferecord.core.GoalSpec
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GoalReviewCoordinatorTest {
    @Test fun leaseIsSingleFlightExpiresUnknownAndOnlyExplicitTakeoverReplacesItsToken() {
        val root = Files.createTempDirectory("goal-review-coordinator-")
        val clock = MutableClock(Instant.parse("2027-01-22T04:00:00Z"))
        try {
            WindowsDatabase(root.resolve("records.db")).use { database ->
                val goals = GoalRepository(database, clock)
                val key = createGoal(goals)
                val inputHash = goals.snapshot(key).hash
                val coordinator = GoalReviewCoordinator(database, goals, clock)

                val first = coordinator.claim(key, "run-one", "phone-database", inputHash)
                assertEquals(first, coordinator.claim(key, "run-one", "phone-database", inputHash))
                assertEquals(GoalReviewCoordinator.LEASE_MILLIS, first.expiresAt - clock.millis())
                assertEquals(30_000L, GoalReviewCoordinator.HEARTBEAT_MILLIS)
                assertCoordinationError(GoalReviewCoordinationError.GENERATION_RUNNING) {
                    coordinator.claim(key, "run-two", "desktop-database", inputHash)
                }

                clock.now = clock.now.plusSeconds(30)
                val heartbeat = coordinator.heartbeat(key, first.runId, first.token)
                assertEquals(clock.millis() + GoalReviewCoordinator.LEASE_MILLIS, heartbeat.expiresAt)

                clock.now = clock.now.plusSeconds(181)
                assertEquals("UNKNOWN", coordinator.current(key)!!.state)
                assertCoordinationError(GoalReviewCoordinationError.GENERATION_UNKNOWN) {
                    coordinator.claim(key, "run-two", "desktop-database", inputHash)
                }
                val replacement = coordinator.claim(key, "run-two", "desktop-database", inputHash, takeover = true)
                assertNotEquals(first.token, replacement.token)
                assertCoordinationError(GoalReviewCoordinationError.LEASE_INVALID) {
                    coordinator.finish(key, first.runId, first.token)
                }
                assertEquals("FINISHED", coordinator.finish(key, replacement.runId, replacement.token).state)
                assertCoordinationError(GoalReviewCoordinationError.LEASE_INVALID) {
                    coordinator.claim(key, replacement.runId, replacement.holder, replacement.inputHash)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun inputMustMatchCurrentSnapshotAndRestartMakesRunningLeaseUnknown() {
        val root = Files.createTempDirectory("goal-review-restart-")
        val clock = MutableClock(Instant.parse("2027-01-22T04:00:00Z"))
        try {
            WindowsDatabase(root.resolve("records.db")).use { database ->
                val goals = GoalRepository(database, clock)
                val key = createGoal(goals)
                val hash = goals.snapshot(key).hash
                val first = GoalReviewCoordinator(database, goals, clock)
                assertCoordinationError(GoalReviewCoordinationError.INPUT_MISMATCH) {
                    first.claim(key, "run-bad", "phone-database", "0".repeat(64))
                }
                first.claim(key, "run-one", "phone-database", hash)

                val restarted = GoalReviewCoordinator(database, goals, clock)
                assertEquals("UNKNOWN", restarted.current(key)!!.state)
                assertCoordinationError(GoalReviewCoordinationError.GENERATION_UNKNOWN) {
                    restarted.claim(key, "run-two", "desktop-database", hash)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun createGoal(goals: GoalRepository): GoalReviewKey {
        val editor = goals.openEditor()
        goals.save(
            editor,
            GoalSpec(
                editor.goalId,
                "交付合成小工具",
                "交付可运行原型并完成独立验证",
                "2027-01-01",
                "2027-01-21",
                "UTC",
                7,
                listOf(GoalMilestone(UUID.randomUUID().toString(), "交付可运行原型")),
                archived = false,
            ),
            UUID.randomUUID().toString(),
        )
        return GoalReviewKey(editor.goalId, "2027-01-15", "2027-01-21")
    }

    private fun assertCoordinationError(
        expected: GoalReviewCoordinationError,
        block: () -> Unit,
    ) {
        val failure = assertThrows(GoalReviewCoordinationException::class.java) { block() }
        assertEquals(expected, failure.error)
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }
}
