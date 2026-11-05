package app.liferecord

import app.liferecord.core.*

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

/** UI contract under controlled AI, isolated synthetic DB; does not claim a live provider was exercised. */
@RunWith(AndroidJUnit4::class)
class ReportFlowTest {
    @get:Rule val compose = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val name = "report-ui-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val database by lazy { AndroidDatabase(app, name) }
    private val store by lazy { RecordStore(database, clock) }
    private val repo by lazy { ReportRepository(database) }
    private val state = mutableStateOf(ReportUiState(date = "2026-01-02", configured = true, loading = false))
    @After fun cleanup() { database.close(); app.deleteDatabase(name) }
    @Test fun savedOutcomeUpdatesBodyAndSourceDeletionMarksOldReportStale() {
        val opened = store.beginNew()
        val id = store.save(opened, opened.draft.copy(text = "合成日报UI：阅读教程，尚未记录结果", local = "2026-01-02 09:00", zone = "UTC", offset = "Z"), UUID.randomUUID().toString())
        var calls = 0
        val ai = object : ReportAi {
            override suspend fun generate(configuration: AiConfiguration, input: ReportInput): GeneratedReport {
                calls++
                val answer = input.questions.firstOrNull { it.answer.isNotBlank() }
                return GeneratedReport(listOf(ReportParagraph(if (answer == null) "阅读教程，结果尚未记录。" else "阅读教程后完成了示例练习。", listOf(id), listOfNotNull(answer?.id))), if (answer == null) listOf(ReportQuestion(AiClient.questionId(listOf(id)), listOf(id), "阅读后形成了什么成果？")) else emptyList())
            }
            override suspend fun test(configuration: AiConfiguration) = Unit
        }
        val engine = ReportEngine(repo, { AiConfiguration("https://synthetic.invalid/v1", "synthetic", "fixture", true) }, ai, clock)
        fun refresh() { state.value = state.value.copy(day = repo.day("2026-01-02")) }
        refresh()
        compose.setContent { LifeRecordTheme {
            ReportScreen(state.value, {}, { force, clear -> runBlocking { engine.request("2026-01-02", force, clear); engine.awaitCompletion("2026-01-02") }; refresh() }, {},
                { q, text, heads, intent -> repo.answer("2026-01-02", q, text, heads, intent); refresh() },
                { q, skip, heads, intent -> repo.skip("2026-01-02", q, skip, heads, intent); refresh() }, {}, {}, { refresh() })
        } }
        compose.onNodeWithText("生成事实日报").performClick()
        compose.onNodeWithText("阅读后形成了什么成果？").performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("完成了示例练习")
        compose.onNodeWithText("保存补充答案").performScrollTo().performClick()
        assertEquals("完成了示例练习", repo.day("2026-01-02").questions.single().answer)
        compose.onAllNodesWithText("更新日报")[0].performClick()
        compose.onNodeWithText("阅读教程后完成了示例练习。").performScrollTo().assertIsDisplayed()
        assertEquals(2, calls)
        compose.runOnIdle {
            val heads = store.sync.headIds("record", id)
            store.moveToTrash(id, heads, UUID.randomUUID().toString())
            refresh()
        }
        assertTrue(repo.day("2026-01-02").changed)
        assertNotNull(repo.day("2026-01-02").report)
    }
}
