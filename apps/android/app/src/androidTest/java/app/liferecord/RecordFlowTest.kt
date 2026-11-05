package app.liferecord

import app.liferecord.core.*

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class RecordFlowTest {
    @get:Rule val compose = createComposeRule()
    private val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val name = "flow-${UUID.randomUUID()}.db"
    private var failure: String? = null
    private val database by lazy { AndroidDatabase(application, name) }
    private val store by lazy { RecordStore(database, java.time.Clock.fixed(java.time.Instant.parse("2027-01-01T00:00:00Z"), java.time.ZoneId.of("Asia/Shanghai"))) { if (it == failure) throw android.database.sqlite.SQLiteFullException("injected") } }
    private val runtime by lazy { AppRuntime(application, database, AiSettings(object : AiConfigurationStorage {
        override fun read(): AiConfiguration? = null
        override fun write(value: AiConfiguration, replaceUnreadable: Boolean) = Unit
    })) }
    private lateinit var vm: RecordViewModel
    private fun awaitState(condition: () -> Boolean) {
        compose.waitUntil(15000) { compose.runOnIdle(condition) }
    }
    @Before fun initializeScheduler() {
        try { androidx.work.WorkManager.getInstance(application) }
        catch (_: IllegalStateException) { androidx.work.WorkManager.initialize(application, androidx.work.Configuration.Builder().build()) }
    }
    @After fun cleanup() { database.close(); application.deleteDatabase(name) }
    @Test fun failedSaveRetryDraftAndDeletion() {
        compose.runOnUiThread { vm = RecordViewModel(application, store) }
        val reports = ReportViewModel(application, runtime)
        compose.setContent { LifeRecordTheme { RecordApp(vm, reports) } }
        awaitState { vm.data != null && !vm.busy }
        compose.onNodeWithText("新增记录").performClick()
        awaitState { vm.page == "edit" && !vm.busy }
        compose.onNodeWithTag("body").performTextInput("合成：练习后计划明天核对")
        compose.onNodeWithTag("event-time").performScrollTo().performTextReplacement("2026-01-02 09:30")
        awaitState { store.snapshot().drafts.singleOrNull()?.local == "2026-01-02 09:30" }
        assertEquals("合成：练习后计划明天核对", store.snapshot().drafts.single().text)
        failure = "save"
        compose.onNodeWithTag("save").performClick()
        awaitState { !vm.busy }
        compose.onNodeWithTag("body").assertTextContains("合成：练习后计划明天核对")
        assertTrue(vm.data!!.records.isEmpty())
        failure = null
        compose.onNodeWithTag("save").performClick()
        awaitState { vm.page == "detail" && !vm.busy }
        val original = vm.data!!.records.single()
        compose.onNodeWithText("编辑", useUnmergedTree = true).performScrollTo().performClick()
        awaitState { vm.page == "edit" && !vm.busy }
        compose.onNodeWithTag("body").performTextReplacement("合成：更正事实")
        compose.onNodeWithContentDescription("返回").performClick()
        awaitState { vm.page == "detail" && !vm.busy }
        compose.onNodeWithTag("record-body").assertTextEquals(original.text)
        assertEquals("合成：更正事实", store.snapshot().drafts.single().text)
        compose.onNodeWithText("编辑", useUnmergedTree = true).performScrollTo().performClick()
        awaitState { vm.page == "edit" && !vm.busy }
        compose.onNodeWithTag("body").assertTextContains("合成：更正事实")
        compose.onNodeWithTag("save").performClick()
        awaitState { vm.page == "detail" && !vm.busy }
        assertEquals(original.created, vm.data!!.records.single().created)
        assertEquals(1, vm.data!!.revisions.size)
        compose.onNodeWithText("删除", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("确认删除").performClick()
        awaitState { vm.page == "list" && !vm.busy }
        compose.onNodeWithText("最近删除", useUnmergedTree = true).performClick()
        compose.onNodeWithText("合成：更正事实", useUnmergedTree = true).performClick()
        awaitState { vm.page == "detail" && !vm.busy }
        compose.onNodeWithText("恢复记录").performScrollTo().performClick()
        awaitState { vm.page == "detail" && !vm.busy }
        assertNull(vm.data!!.records.single().deleted)
        assertEquals("合成：更正事实", vm.data!!.records.single().text)
        assertEquals(1, vm.data!!.revisions.size)
    }
}
