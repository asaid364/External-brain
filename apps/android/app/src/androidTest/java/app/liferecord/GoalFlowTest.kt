package app.liferecord

import android.app.Application
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.liferecord.core.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real nonproduction runtime, SQLite, viewmodel and Compose clicks; AI is explicitly synthetic. */
@RunWith(AndroidJUnit4::class)
class GoalFlowTest {
    // Use the rule's automatic clock throughout. Compose 1.7.5 still waits for
    // pending Android root attachment/measure/layout when autoAdvance is false;
    // freezing time cannot fix that host-runner condition and can hide UI updates.
    @get:Rule val compose = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val name = "goal-flow-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2027-01-22T04:00:00Z"), ZoneOffset.UTC)
    private val database = AndroidDatabase(app, name)
    private val configuration = AiConfiguration("https://synthetic.invalid/v1", "synthetic-only", "fixture", false)
    private val settings = AiSettings(object : AiConfigurationStorage {
        private var value = configuration
        override fun read(): AiConfiguration = value
        override fun write(value: AiConfiguration, replaceUnreadable: Boolean) { this.value = value }
    })
    private val sends = AtomicInteger()
    private val ai = object : GoalAnalysisAi {
        override suspend fun generate(configuration: AiConfiguration, input: GoalAnalysisInput,
            beforeSend: suspend () -> Unit): GeneratedGoalReview {
            AiSettings.validateCredentials(configuration)
            val record = input.evidence.first { it.dependency.ref.kind == GoalEvidenceKind.RECORD }.dependency.ref.id
            val result = GeneratedGoalReview(GoalDirection.ADVANCING,
                GoalEvidenceClaim("模拟AI：原型交付已有事实依据", listOf(record)), emptyList(),
                listOf(GoalEvidenceClaim("原型已交付", listOf(record))), emptyList(),
                listOf(GoalEvidenceClaim("交付原型是有依据的推进", listOf(record))),
                listOf(GoalMilestoneSuggestion(input.goal.milestones.first().id, "模拟AI：请核对原型交付", listOf(record))),
                emptyList(), listOf("没有记录的日期保持未知"))
            beforeSend()
            sends.incrementAndGet()
            return result
        }
    }
    private val runtime = AppRuntime(app, database, settings, production = false, goalAi = ai, goalClock = clock)
    private val store = RecordStore(database, clock)
    private lateinit var model: GoalViewModel
    private lateinit var goal: GoalSpec
    private lateinit var recordId: String
    private lateinit var key: GoalReviewKey

    // Compose 1.7.5's Robolectric strategy requests Android layout but receives no
    // draw passes. Dialog layout can become pending again inside its idle loop,
    // after a one-shot traversal. Keep the same Espresso queue drain, Android
    // layout request, master timeout and ComposeIdlingResource idle predicate;
    // additionally execute pending attached-root layout on EVERY idle iteration.
    // Shared device sources cannot import host internals, hence reflection here.
    @Before fun installHostTraversalBridge() {
        if (hostIdleMainLooper == null) return
        val environment = field(compose, "environment")
        val resource = field(environment, "composeIdlingResource")
        val strategyField = environment.javaClass.superclass.getDeclaredField("idlingStrategy")
            .apply { isAccessible = true }
        val original = strategyField.get(environment)
        val strategyType = Class.forName("androidx.compose.ui.test.IdlingStrategy")
        val requestLayout = original.javaClass.getDeclaredMethod("requestLayoutIfNeeded")
            .apply { isAccessible = true }
        val isIdle = resource.javaClass.getMethod("isIdleNow")
        val bridge = java.lang.reflect.Proxy.newProxyInstance(strategyType.classLoader,
            arrayOf(strategyType)) { _, method, arguments ->
            if (method.name == "runUntilIdle") {
                compose.runOnUiThread {
                    val policy = Class.forName("androidx.test.espresso.IdlingPolicies")
                        .getMethod("getMasterIdlingPolicy").invoke(null)
                    val unit = policy.javaClass.getMethod("getIdleTimeoutUnit").invoke(policy)
                        as java.util.concurrent.TimeUnit
                    val timeout = unit.toMillis(policy.javaClass.getMethod("getIdleTimeout")
                        .invoke(policy) as Long)
                    val started = System.nanoTime()
                    var idle: Boolean
                    do {
                        if ((System.nanoTime() - started) / 1_000_000 >= timeout) {
                            throw (Class.forName("androidx.test.espresso.AppNotIdleException")
                                .getMethod("create", List::class.java, String::class.java)
                                .invoke(null, emptyList<String>(),
                                    "Compose did not get idle with host traversal bridge. " +
                                        resource.javaClass.getMethod("getDiagnosticMessageIfBusy")
                                            .invoke(resource)) as Throwable)
                        }
                        Class.forName("androidx.test.espresso.Espresso")
                            .getMethod("onIdle").invoke(null)
                        requestLayout.invoke(original)
                        measurePendingHostRoots()
                        idle = isIdle.invoke(resource) as Boolean
                    } while (!idle)
                }
                null
            } else {
                method.invoke(original, *(arguments ?: emptyArray()))
            }
        }
        strategyField.set(environment, bridge)
    }

    @After fun close() {
        // IO completions resume viewModelScope on the Android main looper. Do not
        // block that runner thread in join() while Robolectric has its looper paused.
        if (::model.isInitialized) compose.runOnUiThread { model.viewModelScope.cancel() }
        runtime.scope.cancel()
        await {
            (!::model.isInitialized || model.viewModelScope.coroutineContext[Job]?.isCompleted != false) &&
                runtime.scope.coroutineContext[Job]?.isCompleted != false
        }
        database.close()
        app.deleteDatabase(name)
    }

    private fun mountAndGenerate() {
        runBlocking { withTimeout(10_000) { runtime.ready.await() } }
        val session = runtime.goals.openEditor()
        goal = GoalSpec(session.goalId, "SYNTH-GOAL-${UUID.randomUUID()}", "交付可核验的小工具",
            "2027-01-01", "2027-01-21", "UTC", 7,
            listOf(GoalMilestone(UUID.randomUUID().toString(), "交付可运行原型"),
                GoalMilestone(UUID.randomUUID().toString(), "用独立数据验证结果")), false)
        runtime.goals.save(session, goal, UUID.randomUUID().toString())
        val record = store.beginNew()
        recordId = store.save(record, record.draft.copy(text = "合成：可运行原型已交付",
            local = "2027-01-15 09:00", zone = "UTC", offset = "Z"), UUID.randomUUID().toString())
        key = GoalReviewKey(goal.id, "2027-01-15", "2027-01-21")
        runtime.goals.grantAnalysis(goal.id, GoalCodec.specHash(goal), configuration.endpoint,
            configuration.model, true, false)
        compose.runOnIdle { model = GoalViewModel(app, runtime) }
        compose.setContent { LifeRecordTheme { GoalScreen(model.state, model.goalActions()) } }
        await { !model.state.isLoading && model.state.goals.size == 1 }
        click(goal.title)
        await { model.state.detail != null }
        click("开始回顾分析")
        await { model.state.generationConfirmation != null }
        awaitNode(hasText("费用与分析确认") and hasAnyAncestor(isDialog()))
        assertEquals(0, sends.get())
        compose.onNodeWithText("确认继续").performClick()
        await { model.state.detail?.reviews?.size == 1 }
        assertEquals(GoalRunState.SUCCEEDED, runtime.goals.run(key)?.state)
        assertEquals(1, sends.get())
        assertProgress(0)
    }

    // Shared androidTest sources cannot import Robolectric. Only its host runner
    // exposes this public test API; real instrumentation uses Android's own loop.
    private val hostIdleMainLooper by lazy {
        try {
            Class.forName("org.robolectric.shadows.ShadowLooper").getMethod("idleMainLooper")
        } catch (_: ClassNotFoundException) {
            null
        }
    }
    private fun drainHostMainQueue() {
        hostIdleMainLooper?.invoke(null)
    }
    private fun await(predicate: () -> Boolean) = compose.waitUntil(10_000) {
        // waitUntil advances Compose's frame clock, not Robolectric's paused
        // Android Handler queue. IO -> viewModelScope continuations need both.
        drainHostMainQueue()
        compose.runOnUiThread(predicate)
    }
    private fun awaitNode(matcher: SemanticsMatcher) {
        // Viewmodel state can be ready before Android attaches a dialog window.
        // Wait for its semantics too; do not mistake state readiness for visible UI.
        compose.waitUntil(10_000) {
            drainHostMainQueue()
            compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1
        }
        compose.onNode(matcher).assertIsDisplayed()
    }

    private fun field(owner: Any, name: String): Any {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val found = type.declaredFields.firstOrNull { it.name == name }
            if (found != null) {
                found.isAccessible = true
                return checkNotNull(found.get(owner)) { "Null ${owner.javaClass.name}.$name" }
            }
            type = type.superclass
        }
        error("Missing ${owner.javaClass.name}.$name")
    }

    private fun measurePendingHostRoots() {
        val environment = field(compose, "environment")
        val registry = field(environment, "composeRootRegistry")
        val roots = registry.javaClass.getMethod("getRegisteredComposeRoots")
            .invoke(registry) as Set<*>
        roots.forEach { candidate ->
            val root = checkNotNull(candidate)
            val view = root.javaClass.getMethod("getView").invoke(root) as View
            val pending = root.javaClass.getMethod("getHasPendingMeasureOrLayout")
                .invoke(root) as Boolean
            if (view.isAttachedToWindow && pending) {
                root.javaClass.getMethod("measureAndLayoutForTest").invoke(root)
            }
        }
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun assertProgress(completed: Int) {
        await { model.state.detail?.summary?.completed == completed }
        assertEquals(completed, runtime.goals.detail(goal.id).summary.completed)
        compose.onNodeWithText("$completed / 2 项").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("${completed * 50}%").performScrollTo().assertIsDisplayed()
    }
    private fun confirmSuggestion() {
        click("核对证据→确认达成")
        awaitNode(hasText("确认里程碑达成") and hasAnyAncestor(isDialog()))
        compose.onNodeWithText("完成说明（选填，标注为用户声明）")
            .performTextReplacement("合成用户声明：已核对原型交付证据")
        assertEquals(0, runtime.goals.detail(goal.id).summary.completed)
        compose.onNode(hasText("确认达成") and hasAnyAncestor(isDialog())).performClick()
        assertProgress(1)
        assertEquals("合成用户声明：已核对原型交付证据",
            runtime.goals.detail(goal.id).confirmations.single {
                it.value.milestoneId == goal.milestones.first().id
            }.value.note)
    }

    @Test fun aiProposalNeedsUserConfirmationAndChangedCriterionDoesNotReuseOldAchievement() {
        mountAndGenerate()
        confirmSuggestion()
        click("撤回确认")
        assertProgress(0)
        confirmSuggestion()
        compose.onNodeWithContentDescription("编辑目标").performClick()
        await { model.state.editor != null }
        compose.onNodeWithText("标准 1").performScrollTo().performTextReplacement("交付可运行原型并附使用说明")
        compose.onNodeWithText("保存").performClick()
        await { model.state.editor == null }
        assertProgress(0)
        val detail = runtime.goals.detail(goal.id)
        assertNotEquals(goal.milestones.first().id, detail.summary.spec!!.milestones.first().id)
        val old = detail.confirmations.single { it.value.milestoneId == goal.milestones.first().id }
        assertTrue(old.value.confirmed)
        assertEquals("交付可运行原型", old.value.criterionSnapshot)
        assertEquals(1, sends.get())
    }

    @Test fun dirtyBackProtectsInputAndChangedThenDeletedSourceLeavesHistoryAndProgressIntact() {
        mountAndGenerate()
        confirmSuggestion()
        compose.onNodeWithContentDescription("编辑目标").performClick()
        await { model.state.editor != null }
        val unsaved = "本机尚未保存的目标标题"
        compose.onNodeWithText("目标标题 *").performScrollTo().performTextReplacement(unsaved)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertExists()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("目标标题 *").assertTextContains(unsaved)
        assertEquals(goal.title, runtime.goals.detail(goal.id).summary.spec!!.title)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        await { model.state.editor == null }

        compose.runOnIdle {
            val edit = store.beginEdit(recordId)
            store.save(edit, edit.draft.copy(text = "合成：原型交付后补充使用说明"), UUID.randomUUID().toString())
            model.refreshGoals()
        }
        await { model.state.currentPeriodStatus == "stale" }
        compose.onNodeWithText("此回顾基于旧数据快照生成，部分来源已修改，需重新分析。").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("证据 记录")[0].performScrollTo().performClick()
        await { model.state.evidenceText?.contains("当前内容已改变，本应用未另存生成时原文") == true }
        assertTrue("Expected changed-source warning in preview state: ${model.state.evidenceText}",
            compose.runOnUiThread {
                model.state.evidenceText?.contains("当前内容已改变，本应用未另存生成时原文") == true
            })
        awaitNode(hasText("当前内容已改变，本应用未另存生成时原文", substring = true) and hasAnyAncestor(isDialog()))
        // A refresh while the dialog is open must re-read its source rather than
        // dismissing the window or retaining the prior source body.
        compose.runOnIdle {
            model.refreshGoals()
            assertEquals("正在重新核对来源…", model.state.evidenceText)
        }
        awaitNode(hasText("当前内容已改变，本应用未另存生成时原文", substring = true) and hasAnyAncestor(isDialog()))
        compose.runOnIdle {
            store.moveToTrash(recordId, store.sync.headIds("record", recordId), UUID.randomUUID().toString())
            model.refreshGoals()
            assertEquals("正在重新核对来源…", model.state.evidenceText)
        }
        assertEquals(GoalEvidence.UNAVAILABLE, runtime.goals.reviewStatus(runtime.goals.detail(goal.id).reviews.single()))
        await { model.state.currentPeriodStatus == "unavailable" }
        awaitNode(hasText("来源已不可用；本应用未另存生成时原文") and hasAnyAncestor(isDialog()))
        assertFalse(compose.runOnUiThread { model.state.evidenceText.orEmpty().contains("原型交付后补充使用说明") })
        compose.onNodeWithText("关闭").performClick()
        assertProgress(1)
        compose.onNodeWithText("此回顾引用的原始记录已删除或不可用。").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("证据 记录")[0].performScrollTo().performClick()
        await { model.state.evidenceText == "来源已不可用；本应用未另存生成时原文" }
        assertEquals("来源已不可用；本应用未另存生成时原文",
            compose.runOnUiThread { model.state.evidenceText })
        awaitNode(hasText("来源已不可用；本应用未另存生成时原文") and hasAnyAncestor(isDialog()))
        assertEquals(1, runtime.goals.detail(goal.id).reviews.size)
        assertEquals(1, sends.get())
    }

    @Test fun actualNewGoalFormSavesExactTextAndConfirmationSurvivesDatabaseReopen() {
        runBlocking { withTimeout(10_000) { runtime.ready.await() } }
        compose.runOnIdle { model = GoalViewModel(app, runtime) }
        compose.setContent { LifeRecordTheme { GoalScreen(model.state, model.goalActions()) } }
        await { !model.state.isLoading }
        compose.onNodeWithText("新建第一个目标").performClick()
        await { model.state.editor != null }
        val title = "SYNTH-GOAL-${UUID.randomUUID()}"
        fun replace(label: String, text: String) {
            compose.onNodeWithText(label).performScrollTo().performTextReplacement(text)
        }
        // Semantic text replacement avoids platform IME transliteration/autocorrection entirely.
        replace("目标标题 *", title)
        replace("期望成果（清晰可验证的结果）*", "交付合成小工具并保留验证结果")
        replace("起始日期 *", "2027-01-01")
        replace("截止日期 *", "2027-01-21")
        replace("回顾时区 *", "UTC")
        replace("周期日数 *", "7")
        replace("标准 1", "交付可运行原型")
        click("＋ 添加完成标准")
        replace("标准 2", "用独立数据验证结果")
        compose.onNodeWithText("保存").performClick()
        await { model.state.editor == null && model.state.detail != null }
        goal = model.state.detail!!.summary.spec!!
        assertEquals(title, goal.title)
        assertEquals("交付合成小工具并保留验证结果", goal.desiredResult)
        assertEquals(listOf("交付可运行原型", "用独立数据验证结果"), goal.milestones.map { it.criterion })
        assertEquals("2027-01-01", goal.startDate)
        assertEquals("2027-01-21", goal.endDate)
        assertEquals("UTC", goal.reviewZone)
        assertEquals(7, goal.cadenceDays)
        assertProgress(0)
        compose.onAllNodesWithText("确认达成")[0].performScrollTo().performClick()
        awaitNode(hasText("确认里程碑达成") and hasAnyAncestor(isDialog()))
        compose.onNode(hasText("确认达成") and hasAnyAncestor(isDialog())).performClick()
        assertProgress(1)
        val reopened = AndroidDatabase(app, name)
        try {
            val detail = GoalRepository(reopened, clock).detail(goal.id)
            assertEquals(title, detail.summary.spec!!.title)
            assertEquals(1, detail.summary.completed)
            assertEquals(2, detail.summary.total)
            assertEquals(50, detail.summary.percent)
        } finally { reopened.close() }
        assertEquals(0, sends.get())
    }
}
