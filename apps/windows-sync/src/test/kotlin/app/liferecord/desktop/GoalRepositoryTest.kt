package app.liferecord.desktop

import app.liferecord.core.*
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class GoalRepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2027-01-22T04:00:00Z"), ZoneOffset.UTC)

    @Test fun saveConfirmCriterionReplacementAndReopenPreserveOnlyUserConfirmedCurrentProgress() {
        val root = Files.createTempDirectory("goal-repository-")
        val path = root.resolve("records.db")
        var goalId = ""
        val firstMilestone = uuid()
        try {
            WindowsDatabase(path).use { db ->
                val repo = GoalRepository(db, clock)
                val opened = repo.openEditor()
                goalId = opened.goalId
                val initial = goal(opened.goalId, firstMilestone, " 交付可运行原型 ")
                val intent = uuid()
                assertEquals(opened.goalId, repo.save(opened, initial, intent))
                assertEquals(opened.goalId, repo.save(opened, initial, intent))
                assertEquals(1, SyncRepository(db).readEntity(GoalCodec.GOAL_KIND, opened.goalId).heads.size)

                val confirmationIntent = uuid()
                repo.confirmMilestone(opened.goalId, firstMilestone, GoalCodec.criterionHash("交付可运行原型"), true,
                    " 用户声明 ", emptyList(), emptySet(), confirmationIntent)
                repo.confirmMilestone(opened.goalId, firstMilestone, GoalCodec.criterionHash("交付可运行原型"), true,
                    " 用户声明 ", emptyList(), emptySet(), confirmationIntent)
                assertEquals(1, SyncRepository(db).readEntity(GoalCodec.CONFIRMATION_KIND, "$goalId/$firstMilestone").heads.size)
                assertEquals(100, repo.detail(opened.goalId).summary.percent)

                val replacement = uuid()
                val edit = repo.openEditor(opened.goalId)
                repo.save(edit, edit.initial!!.copy(milestones = listOf(GoalMilestone(replacement, "完成独立验证"))), uuid())
                val afterReplacement = repo.detail(opened.goalId)
                assertEquals(0, afterReplacement.summary.completed)
                assertEquals(0, afterReplacement.summary.percent)
                assertEquals("交付可运行原型", afterReplacement.confirmations.single().value.criterionSnapshot)

                val returnedTextWithNewIdentity = uuid()
                val secondEdit = repo.openEditor(opened.goalId)
                repo.save(secondEdit, secondEdit.initial!!.copy(milestones = listOf(GoalMilestone(returnedTextWithNewIdentity, "交付可运行原型"))), uuid())
                assertEquals(0, repo.detail(opened.goalId).summary.completed)
                val archiveBase = repo.detail(opened.goalId).summary.heads
                val archiveIntent = uuid()
                repo.setArchived(opened.goalId, true, archiveBase, archiveIntent)
                repo.setArchived(opened.goalId, true, archiveBase, archiveIntent)
                assertTrue(repo.list().isEmpty())
                assertTrue(repo.list(includeArchived = true).single().spec!!.archived)
                val reactivateBase = repo.detail(opened.goalId).summary.heads
                repo.setArchived(opened.goalId, false, reactivateBase, uuid())
                assertFalse(repo.list().single().spec!!.archived)
            }
            WindowsDatabase(path).use { db ->
                val detail = GoalRepository(db, clock).detail(goalId)
                assertEquals(0, detail.summary.completed)
                assertEquals(1, detail.summary.total)
                assertEquals("交付可运行原型", detail.confirmations.single().value.criterionSnapshot)
                assertEquals("用户声明", detail.confirmations.single().value.note)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun staleEditorsAndConcurrentConfirmationsStayConflictedUntilExplicitResolution() {
        val root = Files.createTempDirectory("goal-conflicts-")
        try {
            WindowsDatabase(root.resolve("records.db")).use { db ->
                val repo = GoalRepository(db, clock)
                val milestone = uuid()
                val opened = repo.openEditor()
                repo.save(opened, goal(opened.goalId, milestone, "标准A"), uuid())
                val stale = repo.openEditor(opened.goalId)
                repo.save(stale, stale.initial!!.copy(title = "后台版本B"), uuid())
                repo.save(stale, stale.initial!!.copy(title = "前台版本C"), uuid())

                val conflicted = repo.detail(opened.goalId)
                assertTrue(conflicted.summary.conflicted)
                assertNull(conflicted.summary.percent)
                assertEquals(2, conflicted.conflicts.single { it.kind == GoalCodec.GOAL_KIND }.heads.size)
                assertFalse(db.query("SELECT 1 FROM goals WHERE id=?", listOf(opened.goalId)).use { it.next() })

                val goalState = SyncRepository(db).readEntity(GoalCodec.GOAL_KIND, opened.goalId)
                val integrated = GoalCodec.decodeGoal(goalState.heads.first().payload).copy(title = "整合版本")
                repo.resolveGoal(opened.goalId, GoalCodec.encodeGoal(integrated), goalState.heads.mapTo(linkedSetOf()) { it.id }, uuid())
                assertEquals("整合版本", repo.detail(opened.goalId).summary.spec!!.title)

                val confirmationKey = "${opened.goalId}/$milestone"
                repo.confirmMilestone(opened.goalId, milestone, GoalCodec.criterionHash("标准A"), true, "A", emptyList(), emptySet(), uuid())
                val headA = SyncRepository(db).readEntity(GoalCodec.CONFIRMATION_KIND, confirmationKey).heads.single().id
                repo.confirmMilestone(opened.goalId, milestone, GoalCodec.criterionHash("标准A"), false, "后台B", emptyList(), setOf(headA), uuid())
                val afterB = SyncRepository(db).readEntity(GoalCodec.CONFIRMATION_KIND, confirmationKey)
                assertEquals(1, afterB.heads.size)
                assertTrue(headA in afterB.retired)

                repo.confirmMilestone(opened.goalId, milestone, GoalCodec.criterionHash("标准A"), true, "基于旧A的前台C", emptyList(), setOf(headA), uuid())
                val confirmationState = SyncRepository(db).readEntity(GoalCodec.CONFIRMATION_KIND, confirmationKey)
                assertTrue(confirmationState.unresolved)
                assertEquals(setOf("后台B", "基于旧A的前台C"), confirmationState.heads.mapTo(linkedSetOf()) { GoalCodec.decodeConfirmation(it.payload).note })
                assertNull(repo.detail(opened.goalId).summary.percent)
                assertFalse(db.query("SELECT 1 FROM goal_confirmations WHERE goal_id=? AND milestone_id=?", listOf(opened.goalId, milestone)).use { it.next() })

                val withdrawn = confirmationState.heads.map { GoalCodec.decodeConfirmation(it.payload) }.single { !it.confirmed }
                repo.resolveConfirmation(opened.goalId, milestone, GoalCodec.encodeConfirmation(withdrawn), confirmationState.heads.mapTo(linkedSetOf()) { it.id }, uuid())
                val resolved = repo.detail(opened.goalId)
                assertFalse(resolved.summary.conflicted)
                assertEquals(0, resolved.summary.percent)
                assertFalse(resolved.confirmations.single().value.confirmed)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun projectMergedStrictlyValidatesImportedIdentityAndProjectsOnlyValidHeads() {
        val root = Files.createTempDirectory("goal-project-merged-")
        try {
            WindowsDatabase(root.resolve("records.db")).use { db ->
                val repo = GoalRepository(db, clock)
                val sync = SyncRepository(db)
                val goalId = uuid()
                val value = goal(goalId, uuid(), "标准A")
                db.transaction { tx ->
                    sync.writeLocalHead(tx, GoalCodec.GOAL_KIND, goalId, GoalCodec.encodeGoal(value),
                        observedBase = emptySet(), intentId = uuid(), resultKey = goalId)
                    repo.projectMerged(tx, setOf(GoalCodec.GOAL_KIND to goalId))
                }
                assertEquals(value, repo.list().single().spec)

                assertThrows(IllegalArgumentException::class.java) {
                    db.transaction { tx ->
                        val wrongIdentity = value.copy(id = uuid())
                        sync.writeLocalHead(tx, GoalCodec.GOAL_KIND, goalId, GoalCodec.encodeGoal(wrongIdentity),
                            observedBase = emptySet(), intentId = uuid(), resultKey = goalId)
                        repo.projectMerged(tx, setOf(GoalCodec.GOAL_KIND to goalId))
                    }
                }
                assertEquals(1, sync.readEntity(GoalCodec.GOAL_KIND, goalId).heads.size)
                assertEquals(value, repo.list().single().spec)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun v3UpgradeCreatesGoalTablesResetsConsentOnceAndReopenIsIdempotent() {
        val root = Files.createTempDirectory("goal-schema-upgrade-")
        val path = root.resolve("records.db")
        try {
            WindowsDatabase(path).use { db ->
                db.exec("INSERT INTO sync_peer(peer_id,send_consent,receive_consent) VALUES('peer',1,1)")
            }
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("DROP TABLE goal_review_known")
                    sql.execute("DROP TABLE goal_review_coord")
                    sql.execute("DROP TABLE goal_review_runs")
                    sql.execute("DROP TABLE goal_analysis_permissions")
                    sql.execute("DROP TABLE goal_reviews")
                    sql.execute("DROP TABLE goal_confirmations")
                    sql.execute("DROP TABLE goals")
                    sql.execute("PRAGMA user_version=3")
                }
            }
            WindowsDatabase(path).use { db ->
                assertEquals(4L, db.query("PRAGMA user_version").use { assertTrue(it.next()); it.long(0) })
                assertEquals(listOf(0L, 0L), db.query("SELECT send_consent,receive_consent FROM sync_peer WHERE peer_id='peer'").use { assertTrue(it.next()); listOf(it.long(0), it.long(1)) })
                assertTrue(db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='goal_reviews'").use { it.next() })
                db.exec("UPDATE sync_peer SET send_consent=1,receive_consent=1 WHERE peer_id='peer'")
                GoalSchema.install(db)
                assertEquals(listOf(1L, 1L), db.query("SELECT send_consent,receive_consent FROM sync_peer WHERE peer_id='peer'").use { assertTrue(it.next()); listOf(it.long(0), it.long(1)) })
            }
            WindowsDatabase(path).use { db ->
                assertEquals(listOf(1L, 1L), db.query("SELECT send_consent,receive_consent FROM sync_peer WHERE peer_id='peer'").use { assertTrue(it.next()); listOf(it.long(0), it.long(1)) })
            }
        } finally { root.toFile().deleteRecursively() }
    }

    private fun goal(id: String, milestoneId: String, criterion: String) = GoalSpec(
        id, "交付合成小工具", "可运行原型", "2027-01-01", "2027-01-21", "UTC", 7,
        listOf(GoalMilestone(milestoneId, criterion)), false,
    )

    private fun uuid(): String = UUID.randomUUID().toString()
}
