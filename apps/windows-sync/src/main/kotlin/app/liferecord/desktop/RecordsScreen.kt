package app.liferecord.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.liferecord.core.Draft
import app.liferecord.core.Record
import app.liferecord.core.RecordTime
import app.liferecord.core.Revision

enum class RecordListFilter {
    ALL,
    DRAFTS,
    CONFLICTS,
    TRASH
}

data class RemoteDraftCandidateUiModel(
    val headId: String,
    val originName: String,
    val updatedAt: String,
    val incomingText: String,
    val incomingLocalTime: String
)

data class DesktopEditingBuffer(
    val textValue: TextFieldValue = TextFieldValue(),
    val local: String = "",
    val zone: String = "",
    val offset: String? = null,
    val recordId: String? = null,
    val isDirty: Boolean = false,
    val remoteIncomingHead: RemoteDraftCandidateUiModel? = null
)

data class RecordsScreenUiState(
    val records: List<Record> = emptyList(),
    val drafts: List<Draft> = emptyList(),
    val newDraft: Draft? = null,
    val revisions: List<Revision> = emptyList(),
    val selectedRecordId: String? = null,
    val editingBuffer: DesktopEditingBuffer? = null,
    val isEditing: Boolean = false,
    val filter: RecordListFilter = RecordListFilter.ALL,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val message: String = "",
    val conflictRecordIds: Set<String> = emptySet()
)

/**
 * Windows 桌面端记录管理面板 (双栏布局)
 */
@Composable
fun RecordsScreen(
    state: RecordsScreenUiState,
    onSelectRecord: (String) -> Unit,
    onNewRecord: () -> Unit,
    onContinueDraft: (Draft) -> Unit,
    onDiscardNewDraft: () -> Unit,
    onStartEdit: (Record) -> Unit,
    onBufferChange: (DesktopEditingBuffer) -> Unit,
    onSaveBuffer: (DesktopEditingBuffer) -> Unit,
    onDiscardEditingDraft: () -> Unit,
    onCancelEdit: () -> Unit,
    onApplyRemoteDraft: (headId: String) -> Unit,
    onLocateConflict: (recordId: String) -> Unit,
    onDeleteRecord: (Record) -> Unit,
    onRestoreRecord: (Record) -> Unit,
    onPurgeRecord: (Record) -> Unit,
    onFilterChange: (RecordListFilter) -> Unit,
    onSearchChange: (String) -> Unit
) {
    var showPurgeConfirm by remember { mutableStateOf<Record?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<Record?>(null) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxSize()) {
        // 左栏：记录列表与筛选 (宽 380dp)
        Column(
            modifier = Modifier
                .width(380.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
        ) {
            // 顶栏操作区：搜索 + 新增/继续草稿按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("搜索记录内容或时区...") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (state.newDraft != null) {
                            onContinueDraft(state.newDraft)
                        } else {
                            onNewRecord()
                        }
                    },
                    enabled = !state.isEditing && !state.isLoading
                ) {
                    Text(if (state.newDraft != null) "继续新增草稿" else "新增")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 筛选标签组
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                RecordListFilter.entries.forEach { f ->
                    val isSelected = state.filter == f
                    val label = when (f) {
                        RecordListFilter.ALL -> "全部"
                        RecordListFilter.DRAFTS -> {
                            val draftCount = state.drafts.size + if (state.newDraft != null) 1 else 0
                            "草稿 ($draftCount)"
                        }
                        RecordListFilter.CONFLICTS -> "冲突 (${state.conflictRecordIds.size})"
                        RecordListFilter.TRASH -> "最近删除"
                    }
                    TextButton(
                        onClick = { onFilterChange(f) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(
                            text = label,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

            // 列表主体
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val filteredRecords = remember(state.records, state.filter, state.searchQuery, state.conflictRecordIds) {
                    val query = state.searchQuery.trim()
                    state.records.filter { r ->
                        val matchesFilter = when (state.filter) {
                            RecordListFilter.ALL -> r.deleted == null
                            RecordListFilter.DRAFTS -> r.deleted == null && state.drafts.any { it.recordId == r.id }
                            RecordListFilter.CONFLICTS -> state.conflictRecordIds.contains(r.id)
                            RecordListFilter.TRASH -> r.deleted != null
                        }
                        val matchesQuery = query.isEmpty() || r.text.contains(query, ignoreCase = true) || r.time.zone.contains(query, ignoreCase = true)
                        matchesFilter && matchesQuery
                    }
                }

                val showNewDraftCard = state.newDraft != null && (state.filter == RecordListFilter.ALL || state.filter == RecordListFilter.DRAFTS)

                if (filteredRecords.isEmpty() && !showNewDraftCard) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        val emptyText = when (state.filter) {
                            RecordListFilter.ALL -> "暂无记录。点击右上角“新增”，记下第一条事实。"
                            RecordListFilter.DRAFTS -> "当前没有未保存的草稿。"
                            RecordListFilter.CONFLICTS -> "当前没有待解决的数据冲突。"
                            RecordListFilter.TRASH -> "最近删除为空。已删除记录在此保留。"
                        }
                        DesktopInfoPanel("列表为空", emptyText)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 独立置顶展示未保存的新增草稿 (slot new)
                        if (showNewDraftCard && state.newDraft != null) {
                            item(key = "new-draft-slot-header") {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            DesktopStatusBadge("未保存的新增草稿", isWarning = true)
                                            Text(state.newDraft.local, style = MaterialTheme.typography.labelMedium)
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = if (state.newDraft.text.isBlank()) "[空白草稿]" else state.newDraft.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TextButton(
                                                onClick = onDiscardNewDraft,
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Text("放弃草稿")
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Button(onClick = { onContinueDraft(state.newDraft) }) {
                                                Text("继续编辑")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        items(filteredRecords, key = { it.id }) { record ->
                            val isSelected = record.id == state.selectedRecordId
                            val hasDraft = state.drafts.any { it.recordId == record.id }
                            val isConflict = state.conflictRecordIds.contains(record.id)

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectRecord(record.id) },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = record.time.local.replace('T', ' '),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (isConflict) {
                                                Surface(
                                                    modifier = Modifier.clickable { onLocateConflict(record.id) },
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = "冲突待解决 ▶",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.error,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            if (hasDraft) {
                                                DesktopStatusBadge("有编辑草稿", isWarning = true)
                                            }
                                            if (record.deleted != null) {
                                                DesktopStatusBadge("已删除", isWarning = true)
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = record.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 分割线
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant)
        )

        // 右栏：详情展示或受控编辑器 (自适应宽)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp)
        ) {
            when {
                state.isEditing && state.editingBuffer != null -> {
                    DesktopRecordEditor(
                        buffer = state.editingBuffer,
                        onBufferChange = onBufferChange,
                        onSave = { onSaveBuffer(state.editingBuffer) },
                        onDiscard = { showDiscardConfirm = true },
                        onCancel = onCancelEdit,
                        onApplyRemoteDraft = onApplyRemoteDraft
                    )
                }
                state.selectedRecordId != null -> {
                    val selectedRecord = state.records.find { it.id == state.selectedRecordId }
                    if (selectedRecord != null) {
                        val recordRevisions = state.revisions.filter { it.recordId == selectedRecord.id }
                        val isConflict = state.conflictRecordIds.contains(selectedRecord.id)
                        DesktopRecordDetail(
                            record = selectedRecord,
                            revisions = recordRevisions,
                            isConflict = isConflict,
                            onEdit = { onStartEdit(selectedRecord) },
                            onLocateConflict = { onLocateConflict(selectedRecord.id) },
                            onDelete = { showDeleteConfirm = selectedRecord },
                            onRestore = { onRestoreRecord(selectedRecord) },
                            onPurge = { showPurgeConfirm = selectedRecord }
                        )
                    } else {
                        DesktopInfoPanel("未选中记录", "请从左侧列表选择一条记录以查看详情。")
                    }
                }
                else -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "选择左侧记录查看详情，或点击“新增”创建事实记录",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // 二次确认弹窗
    if (showDiscardConfirm) {
        DesktopConfirmDialog(
            title = "放弃未保存草稿？",
            body = "清除当前编辑草稿的内容。正式记录与历史版本将保持不变。",
            confirmLabel = "放弃草稿",
            onConfirm = {
                showDiscardConfirm = false
                onDiscardEditingDraft()
            },
            onDismiss = { showDiscardConfirm = false },
            danger = true
        )
    }

    showDeleteConfirm?.let { target ->
        DesktopConfirmDialog(
            title = "移入最近删除？",
            body = "记录将移至最近删除，稍后可随时恢复。关联日报可能标记来源变动。",
            confirmLabel = "确认删除",
            onConfirm = {
                showDeleteConfirm = null
                onDeleteRecord(target)
            },
            onDismiss = { showDeleteConfirm = null },
            danger = true
        )
    }

    showPurgeConfirm?.let { target ->
        DesktopConfirmDialog(
            title = "彻底永久删除？",
            body = "此操作不可逆，将清除该记录正文、修改历史及编辑草稿，并通过同步通知另一端；离线端需等待连接确认。已有日报和目标分析可能保留此前衍生内容并显示来源不可用，外部AI已收到的内容无法保证撤回。", 
            confirmLabel = "彻底删除",
            onConfirm = {
                showPurgeConfirm = null
                onPurgeRecord(target)
            },
            onDismiss = { showPurgeConfirm = null },
            danger = true
        )
    }
}

/**
 * 桌面端受控记录编辑器 (TextFieldValue 缓冲与 IME/远端更新保护)
 */
@Composable
private fun DesktopRecordEditor(
    buffer: DesktopEditingBuffer,
    onBufferChange: (DesktopEditingBuffer) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
    onApplyRemoteDraft: (headId: String) -> Unit
) {
    val scrollState = rememberScrollState()
    val offsets = remember(buffer.local, buffer.zone) {
        val dummyDraft = Draft(buffer.recordId ?: "new", buffer.recordId, buffer.textValue.text, buffer.local, buffer.zone, buffer.offset)
        runCatching { RecordTime.offsets(dummyDraft) }.getOrDefault(emptyList())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (buffer.recordId == null) "新增事实记录 (草稿)" else "编辑记录草稿",
                style = MaterialTheme.typography.titleLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel) { Text("取消") }
                TextButton(onClick = onDiscard, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("放弃草稿")
                }
                Button(
                    onClick = onSave,
                    enabled = buffer.textValue.text.isNotBlank()
                ) {
                    Text("保存记录")
                }
            }
        }

        // 远端新草稿到达保护提示
        buffer.remoteIncomingHead?.let { incoming ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "远端 (${incoming.originName}) 同步了新的草稿更新",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "为保护当前正在输入的文字与输入法组合，未覆盖当前输入框。可点击【采纳远端覆盖】或直接保存保留双方为冲突候选。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onApplyRemoteDraft(incoming.headId) }) {
                            Text("采纳远端内容覆盖当前输入")
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = buffer.textValue,
            onValueChange = { onBufferChange(buffer.copy(textValue = it, isDirty = true)) },
            label = { Text("记录内容 (事实正文)") },
            placeholder = { Text("记下刚刚发生的事实、行动或观察...") },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = buffer.local,
                onValueChange = { onBufferChange(buffer.copy(local = it, offset = null, isDirty = true)) },
                label = { Text("发生时间 (年-月-日 时:分)") },
                supportingText = { Text("格式: yyyy-MM-dd HH:mm，不可填未来时间") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            OutlinedTextField(
                value = buffer.zone,
                onValueChange = { onBufferChange(buffer.copy(zone = it, offset = null, isDirty = true)) },
                label = { Text("发生地点时区 (IANA ID)") },
                supportingText = { Text("如 Asia/Shanghai, America/New_York") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        if (offsets.size > 1) {
            DesktopInfoPanel(
                title = "夏令时秋季回拨时间歧义",
                body = "该当地时间在指定时区出现两次。请明确选择所指的具体 UTC 时差：",
                isWarning = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                offsets.forEach { offset ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = buffer.offset == offset.id,
                            onClick = { onBufferChange(buffer.copy(offset = offset.id, isDirty = true)) }
                        )
                        Text(text = "UTC${offset.id}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * 桌面端记录详情与历史
 */
@Composable
private fun DesktopRecordDetail(
    record: Record,
    revisions: List<Revision>,
    isConflict: Boolean,
    onEdit: () -> Unit,
    onLocateConflict: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    val scrollState = rememberScrollState()
    var historyExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isConflict) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("此记录存在未解决的多端并发冲突", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Text("两端各自保存了不同分支。当前展示仅为本地已见某一快照，请前往冲突中心人工裁决。", style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = onLocateConflict,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("进入冲突裁决 ▶")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "记录详情",
                style = MaterialTheme.typography.titleLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (record.deleted == null) {
                    Button(onClick = onEdit) { Text("编辑此记录") }
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("移入删除")
                    }
                } else {
                    Button(onClick = onRestore) { Text("恢复记录") }
                    OutlinedButton(
                        onClick = onPurge,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("彻底删除")
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            SelectionContainer {
                Text(
                    text = record.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        HorizontalDivider()

        DesktopTimeLabel("发生时间", record.time.display())
        DesktopTimeLabel("首次录入", record.created)
        record.modified?.let { DesktopTimeLabel("最近修改", it) }
        record.deleted?.let { DesktopTimeLabel("删除时间", it) }

        if (revisions.isNotEmpty()) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { historyExpanded = !historyExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "修改历史 (${revisions.size} 次变动)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (historyExpanded) "收起 ▲" else "展开 ▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (historyExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    revisions.forEach { rev ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "修改于: ${rev.modified}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("修改前:", style = MaterialTheme.typography.labelLarge)
                                SelectionContainer {
                                    Text(rev.beforeText, style = MaterialTheme.typography.bodyMedium)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("修改后:", style = MaterialTheme.typography.labelLarge)
                                SelectionContainer {
                                    Text(rev.afterText, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
