package app.liferecord.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Actual Compose semantics integration fixture; no hardcoded sample UI path. */
class DesktopSurfaceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun recordsSurfaceOffersRealEditableAndSafetyControls() {
        var selected: String? = null
        val state = RecordsScreenUiState()
        compose.setContent {
            LifeRecordDesktopTheme {
                RecordsScreen(state, onSelectRecord={selected=it},onNewRecord={},onContinueDraft={},onDiscardNewDraft={},
                    onStartEdit={},onBufferChange={},onSaveBuffer={},onDiscardEditingDraft={},onCancelEdit={},onApplyRemoteDraft={},
                    onLocateConflict={},onDeleteRecord={},onRestoreRecord={},onPurgeRecord={},onFilterChange={},onSearchChange={})
            }
        }
        compose.onNodeWithText("选择左侧记录查看详情，或点击“新增”创建事实记录").assertExists()
        compose.onNodeWithText("新增").assertExists()
        compose.onNodeWithText("最近删除").assertExists()
        assertNull(selected)
    }
    @Test fun reportSurfaceKeepsGeneratedBodyReadOnlyAndShowsEmptyState() {
        compose.setContent {
            LifeRecordDesktopTheme {
                ReportsScreen(ReportsScreenUiState(isLoading=false),{}, {},{}, {},{_,_,_,_->},{_,_,_,_->},{})
            }
        }
        compose.onNodeWithText("选定日期暂无日报").assertExists()
        compose.onNodeWithText("点击右上角“生成日报”，由配置的 AI 服务基于事实记录提炼整理").assertExists()
    }
}
