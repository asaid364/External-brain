package app.liferecord.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.liferecord.core.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.Timeout

/** Actual desktop surface and runtime actions; all facts/AI/configuration are isolated synthetic data. */
class GoalSurfaceTest {
    val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(Timeout.seconds(120)).around(compose)
    private val directory = Files.createTempDirectory("goal-surface-${UUID.randomUUID()}")
    private val clock = Clock.fixed(Instant.parse("2027-01-22T04:00:00Z"), ZoneOffset.UTC)
    private val sends = AtomicInteger()
    private val configuration = AiConfiguration("https://synthetic.invalid/v1", "synthetic-only", "fixture", false)
    private val ai = object : GoalAnalysisAi {
        override suspend fun generate(configuration: AiConfiguration, input: GoalAnalysisInput,
            beforeSend: suspend () -> Unit): GeneratedGoalReview {
            AiSettings.validateCredentials(configuration)
            val ref = input.evidence.first { it.dependency.ref.kind == GoalEvidenceKind.RECORD }.dependency.ref.id
            val result = GeneratedGoalReview(GoalDirection.ADVANCING,
                GoalEvidenceClaim("模拟AI：原型交付已有事实依据", listOf(ref)), emptyList(),
                listOf(GoalEvidenceClaim("原型已交付", listOf(ref))), emptyList(),
                listOf(GoalEvidenceClaim("交付原型是有依据的推进", listOf(ref))),
                listOf(GoalMilestoneSuggestion(input.goal.milestones.first().id, "模拟AI：请核对原型交付", listOf(ref))),
                emptyList(), listOf("没有记录的日期保持未知"))
            beforeSend()
            sends.incrementAndGet()
            return result
        }
    }
    private val runtime = DesktopRuntime(directory, clock, ai, clock)
    private lateinit var goal: GoalSpec
    private lateinit var recordId: String
    private lateinit var key: GoalReviewKey

    @After fun close() {
        runBlocking { withTimeout(10_000) { runtime.close() } }
        directory.toFile().deleteRecursively()
    }

    private fun mount() {
        val session = runtime.goals.openEditor()
        goal = GoalSpec(session.goalId, "SYNTH-GOAL-${UUID.randomUUID()}", "交付可核验的小工具",
            "2027-01-01", "2027-01-21", "UTC", 7,
            listOf(GoalMilestone(UUID.randomUUID().toString(), "交付可运行原型"),
                GoalMilestone(UUID.randomUUID().toString(), "用独立数据验证结果")), false)
        runtime.goals.save(session, goal, UUID.randomUUID().toString())
        val record = runtime.store.beginNew()
        recordId = runtime.store.save(record, record.draft.copy(text = "合成：可运行原型已交付",
            local = "2027-01-15 09:00", zone = "UTC", offset = "Z"), UUID.randomUUID().toString())
        key = GoalReviewKey(goal.id, "2027-01-15", "2027-01-21")
        runtime.goals.grantAnalysis(goal.id, GoalCodec.specHash(goal), configuration.endpoint,
            configuration.model, true, false)
        // Exercise the real engine without opening native Swing fee prompts or writing an AI key to disk.
        runBlocking {
            val engine = GoalAnalysisEngine(runtime.goals, { configuration }, ai, clock)
            engine.request(key)
            engine.awaitCompletion(key)
        }
        assertEquals(GoalRunState.SUCCEEDED, runtime.goals.run(key)?.state)
        // Start before mounting: initial isLoading draws an indeterminate spinner, so
        // runOnIdle cannot be used to start the runtime that would dismiss that spinner.
        runtime.start()
        runtime.selectGoal(goal.id)
        await { runtime.goalState.value.detail?.reviews?.size == 1 && !runtime.goalState.value.isLoading }
        compose.setContent {
            val state by runtime.goalState.collectAsState()
            LifeRecordDesktopTheme { GoalsScreen(state, runtime.goalActions()) }
        }
        compose.waitForIdle()
    }

    private fun await(predicate: () -> Boolean) = runBlocking {
        // Runtime StateFlow work runs on Swing/IO, not the Compose frame clock.
        // Keep the deadline independent of renderNextFrame/invokeAndWait.
        withTimeout(10_000) { while (!predicate()) delay(10) }
    }
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    private fun assertProgress(completed: Int) {
        await { runtime.goalState.value.detail?.summary?.completed == completed }
        assertEquals(completed, runtime.goals.detail(goal.id).summary.completed)
        compose.onNodeWithText("$completed / 2 项").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${completed * 50}%").assertExists()
    }
    private fun confirmSuggestion() {
        click("核对证据→确认达成")
        compose.onNodeWithText("确认里程碑达成").assertExists()
        assertEquals(0, runtime.goals.detail(goal.id).summary.completed)
        compose.onNode(hasText("确认达成") and hasAnyAncestor(isDialog())).performClick()
        assertProgress(1)
    }

    @Test fun suggestionIsNotProgressAndUserCanConfirmWithdrawAndReplaceCriterion() {
        mount()
        assertEquals(1, sends.get())
        assertProgress(0)
        confirmSuggestion()
        click("撤回确认")
        assertProgress(0)
        confirmSuggestion()

        click("编辑目标")
        await { runtime.goalState.value.editor != null }
        compose.onNodeWithText("标准 1").performScrollTo().performTextReplacement("交付可运行原型并附使用说明")
        click("保存")
        await { runtime.goalState.value.editor == null }
        assertProgress(0)
        val detail = runtime.goals.detail(goal.id)
        assertNotEquals(goal.milestones.first().id, detail.summary.spec!!.milestones.first().id)
        val historical = detail.confirmations.single { it.value.milestoneId == goal.milestones.first().id }
        assertTrue(historical.value.confirmed)
        assertEquals("交付可运行原型", historical.value.criterionSnapshot)
        assertEquals(1, sends.get())
    }

    @Test fun dirtyEditorPreservesInputAndDeletedSourceKeepsReviewWithWarning() {
        mount()
        confirmSuggestion()
        click("编辑目标")
        await { runtime.goalState.value.editor != null }
        val localTitle = "本机尚未保存的目标标题"
        compose.onNodeWithText("目标标题 *").performScrollTo().performTextReplacement(localTitle)
        click("取消")
        compose.onNodeWithText("放弃未保存的修改？").assertExists()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("目标标题 *").assertTextContains(localTitle)
        assertEquals(goal.title, runtime.goals.detail(goal.id).summary.spec!!.title)
        click("取消")
        compose.onNodeWithText("放弃修改").performClick()
        await { runtime.goalState.value.editor == null }

        compose.runOnIdle {
            runtime.store.moveToTrash(recordId, runtime.store.sync.headIds("record", recordId), UUID.randomUUID().toString())
            runtime.refreshGoals()
        }
        assertEquals(GoalEvidence.UNAVAILABLE, runtime.goals.reviewStatus(runtime.goals.detail(goal.id).reviews.single()))
        await { runtime.goalState.value.currentPeriodStatus == "unavailable" }
        assertProgress(1)
        assertEquals(1, runtime.goals.detail(goal.id).reviews.size)
        val warning = compose.onNodeWithText("来源已不可用", substring = true)
        warning.performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("证据 记录")[0].performScrollTo().performClick()
        compose.onNodeWithText("证据详情").assertExists()
        compose.onNodeWithText("来源已不可用；本应用未另存生成时原文").assertExists()
        assertEquals(1, sends.get())
    }
}
