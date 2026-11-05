package app.liferecord

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
enum class ConflictTab {
    ALL,
    RECORD,
    DRAFT,
    REPORT,
    ANSWER,
    GOAL
}

data class RecordConflictHead(
    val headId: String,
    val originName: String,
    val modifiedTime: String,
    val eventTime: String,
    val zone: String,
    val text: String,
    val isDeleted: Boolean = false
)

data class RecordConflictItem(
    val recordId: String,
    val heads: List<RecordConflictHead>
)

data class DraftConflictHead(
    val headId: String,
    val originName: String,
    val updatedAt: String,
    val text: String,
    val local: String,
    val isTerminal: Boolean = false,
    val mode: String? = null,
    val isEndedGeneration: Boolean = false
)

data class DraftConflictItem(
    val slot: String,
    val targetRecordId: String?,
    val heads: List<DraftConflictHead>
)

data class ReportConflictCandidate(
    val headId: String,
    val model: String,
    val generatedAt: String,
    val body: String,
    val sourceCount: Int
)

data class ReportConflictItem(
    val date: String,
    val candidates: List<ReportConflictCandidate>
)

data class AnswerConflictCandidate(
    val headId: String,
    val originName: String,
    val answeredAt: String,
    val text: String,
    val skipped: Boolean
)

data class AnswerConflictItem(
    val date: String,
    val questionId: String,
    val questionText: String,
    val candidates: List<AnswerConflictCandidate>
)

data class ConflictScreenUiState(
    val selectedTab: ConflictTab = ConflictTab.ALL,
    val recordConflicts: List<RecordConflictItem> = emptyList(),
    val draftConflicts: List<DraftConflictItem> = emptyList(),
    val reportConflicts: List<ReportConflictItem> = emptyList(),
    val answerConflicts: List<AnswerConflictItem> = emptyList(),
    val goalConflicts: List<app.liferecord.core.EntityState> = emptyList(),
    val resolvingId: String? = null,
    val message: String = ""
) {
    val totalCount: Int get() = recordConflicts.size + draftConflicts.size + reportConflicts.size + answerConflicts.size + goalConflicts.size
}

/**
 * Android 移动端冲突处理中心
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictScreen(
    state: ConflictScreenUiState,
    onSelectTab: (ConflictTab) -> Unit,
    onResolveRecord: (recordId: String, chosenHeadId: String) -> Unit,
    onIntegrateRecord: (recordId: String, baselineHeadId: String, text: String) -> Unit,
    onResolveDraft: (slot: String, chosenHeadId: String, convertUnselectedToNewDraft: Boolean) -> Unit,
    onDiscardDraftHead: (slot: String, discardedHeadId: String) -> Unit,
    onForkDraft: (slot: String, headId: String) -> Unit,
    onIntegrateDraft: (slot: String, baselineHeadId: String, text: String) -> Unit,
    onResolveReport: (date: String, chosenHeadId: String) -> Unit,
    onResolveAnswer: (date: String, questionId: String, chosenHeadId: String) -> Unit,
    onIntegrateAnswer: (date: String, questionId: String, text: String) -> Unit,
    onBack: () -> Unit,
    onResolveGoalEntity: (kind: String, key: String, headId: String) -> Unit = { _, _, _ -> },
    onIntegrateGoalEntity: (kind: String, key: String, payload: String) -> Unit = { _, _, _ -> },
) {
    BackHandler(onBack = onBack)

    var integratingRecord by remember { mutableStateOf<RecordConflictItem?>(null) }
    var integratingDraft by remember { mutableStateOf<DraftConflictItem?>(null) }
    var integratingAnswer by remember { mutableStateOf<AnswerConflictItem?>(null) }
    var awaitingSubmission by remember { mutableStateOf(false) }
    var hadResolvingId by remember { mutableStateOf(false) }

    // Failure-safety resolution watcher:
    // Tracks transition of resolvingId from non-null to null.
    // On success (state.resolvingId becomes null with blank message): dismisses manual integration modal,
    // closing it cleanly even if unresolved conflict remains (avoids invisible retry on new unseen heads).
    // On error (state.resolvingId becomes null with non-blank message): retains modal so user's manual text
    // is preserved, and displays the error message inside the dialog.
    LaunchedEffect(state.resolvingId, state.message) {
        if (state.resolvingId != null) {
            hadResolvingId = true
        } else if (hadResolvingId) {
            hadResolvingId = false
            if (awaitingSubmission) {
                if (state.message.isBlank()) {
                    integratingRecord = null
                    integratingDraft = null
                    integratingAnswer = null
                    awaitingSubmission = false
                } else {
                    awaitingSubmission = false
                }
            }
        }
    }

    // Purge & resolution guard:
    // When a record/draft is purged or resolved and removed from state, immediately dismiss
    // the integration modal so purged content is not retained in memory or UI.
    // Ordinary updates do not replace or refresh the baseline while the modal is open.
    LaunchedEffect(state.recordConflicts) {
        val current = integratingRecord
        if (current != null && state.recordConflicts.none { it.recordId == current.recordId }) {
            integratingRecord = null
            awaitingSubmission = false
        }
    }

    LaunchedEffect(state.draftConflicts) {
        val current = integratingDraft
        if (current != null && state.draftConflicts.none { it.slot == current.slot }) {
            integratingDraft = null
            awaitingSubmission = false
        }
    }

    LaunchedEffect(state.answerConflicts) {
        val current = integratingAnswer
        if (current != null && state.answerConflicts.none { it.date == current.date && it.questionId == current.questionId }) {
            integratingAnswer = null
            awaitingSubmission = false
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "冲突解决中心 (${state.totalCount} 项待解决)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 正在处理状态指示器
            if (state.resolvingId != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 反馈 / 错误提示
            if (state.message.isNotBlank()) {
                InfoPanel(
                    title = "冲突处理提示",
                    body = state.message
                )
            }

            // 筛选标签栏
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ConflictTab.entries.forEach { tab ->
                    val isSelected = state.selectedTab == tab
                    val label = when (tab) {
                        ConflictTab.ALL -> "全部 (${state.totalCount})"
                        ConflictTab.RECORD -> "记录 (${state.recordConflicts.size})"
                        ConflictTab.DRAFT -> "草稿 (${state.draftConflicts.size})"
                        ConflictTab.REPORT -> "日报 (${state.reportConflicts.size})"
                        ConflictTab.ANSWER -> "问答 (${state.answerConflicts.size})"
                        ConflictTab.GOAL -> "目标 (${state.goalConflicts.size})"
                    }
                    TextButton(
                        onClick = { onSelectTab(tab) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(text = label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            HorizontalDivider()

            if (state.totalCount == 0) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    InfoPanel(
                        title = "无待解决冲突",
                        body = "当前本机无待解决的并发冲突。"
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. 记录正文/时间冲突
                    if (state.selectedTab == ConflictTab.ALL || state.selectedTab == ConflictTab.RECORD) {
                        items(state.recordConflicts, key = { "record-${it.recordId}" }) { item ->
                            RecordConflictCard(
                                item = item,
                                onChooseHead = { headId -> onResolveRecord(item.recordId, headId) },
                                onManualIntegrate = { integratingRecord = item }
                            )
                        }
                    }

                    // 2. 共享草稿冲突
                    if (state.selectedTab == ConflictTab.ALL || state.selectedTab == ConflictTab.DRAFT) {
                        items(state.draftConflicts, key = { "draft-${it.slot}" }) { item ->
                            DraftConflictCard(
                                item = item,
                                onChooseHead = { chosenHeadId, makeNew ->
                                    onResolveDraft(item.slot, chosenHeadId, makeNew)
                                },
                                onDiscardHead = { discardedHeadId ->
                                    onDiscardDraftHead(item.slot, discardedHeadId)
                                },
                                onForkDraft = { headId ->
                                    onForkDraft(item.slot, headId)
                                },
                                onManualIntegrate = { integratingDraft = item }
                            )
                        }
                    }

                    // 3. 日报并发生成冲突 (正文严格禁止手动编辑，仅完整版本二选一)
                    if (state.selectedTab == ConflictTab.ALL || state.selectedTab == ConflictTab.REPORT) {
                        items(state.reportConflicts, key = { "report-${it.date}" }) { item ->
                            ReportConflictCard(
                                item = item,
                                onChooseCandidate = { headId -> onResolveReport(item.date, headId) }
                            )
                        }
                    }

                    // 4. 成果补充问答冲突
                    if (state.selectedTab == ConflictTab.ALL || state.selectedTab == ConflictTab.ANSWER) {
                        items(state.answerConflicts, key = { "answer-${it.date}-${it.questionId}" }) { item ->
                            AnswerConflictCard(
                                item = item,
                                onChooseCandidate = { headId -> onResolveAnswer(item.date, item.questionId, headId) },
                                onManualIntegrate = { integratingAnswer = item }
                            )
                        }
                    }
                    if (state.selectedTab == ConflictTab.ALL || state.selectedTab == ConflictTab.GOAL) {
                        items(state.goalConflicts, key = { "${it.kind}-${it.key}" }) { entity ->
                            GoalEntityConflictCard(entity, state.resolvingId == null,
                                onChoose = { onResolveGoalEntity(entity.kind, entity.key, it) },
                                onIntegrate = { onIntegrateGoalEntity(entity.kind, entity.key, it) })
                        }
                    }
                }
            }
        }
    }

    // 记录手动整合弹窗
    integratingRecord?.let { item ->
        ManualRecordIntegrationDialog(
            item = item,
            errorMessage = state.message.takeIf { it.isNotBlank() },
            isResolving = state.resolvingId != null,
            onConfirm = { baselineHeadId, text ->
                awaitingSubmission = true
                onIntegrateRecord(item.recordId, baselineHeadId, text)
            },
            onDismiss = {
                integratingRecord = null
                awaitingSubmission = false
            }
        )
    }

    // 草稿手动整合弹窗
    integratingDraft?.let { item ->
        ManualDraftIntegrationDialog(
            item = item,
            errorMessage = state.message.takeIf { it.isNotBlank() },
            isResolving = state.resolvingId != null,
            onConfirm = { baselineHeadId, text ->
                awaitingSubmission = true
                onIntegrateDraft(item.slot, baselineHeadId, text)
            },
            onDismiss = {
                integratingDraft = null
                awaitingSubmission = false
            }
        )
    }

    // 问答手动整合弹窗
    integratingAnswer?.let { item ->
        ManualAnswerIntegrationDialog(
            item = item,
            errorMessage = state.message.takeIf { it.isNotBlank() },
            isResolving = state.resolvingId != null,
            onConfirm = { text ->
                awaitingSubmission = true
                onIntegrateAnswer(item.date, item.questionId, text)
            },
            onDismiss = {
                integratingAnswer = null
                awaitingSubmission = false
            }
        )
    }
}

/**
 * 记录冲突卡片
 */
@Composable
private fun RecordConflictCard(
    item: RecordConflictItem,
    onChooseHead: (headId: String) -> Unit,
    onManualIntegrate: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "记录冲突 (ID: ${item.recordId.take(8)}...) · ${item.heads.size} 个并发分支",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = "两端各自保存了不同的内容或发生时间。采纳某一分支或手动整合后，其他当前展示的分支将被归档（retired）；彻底删除（purge）具备永久围栏，不可恢复：",
                style = MaterialTheme.typography.bodyMedium
            )

            item.heads.forEachIndexed { idx, head ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = "分支 ${idx + 1} (${head.originName}) · " + if (head.isDeleted) "已移入最近删除" else "正式有效记录",
                                fontWeight = FontWeight.SemiBold,
                                color = if (head.isDeleted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                            Text("修改于: ${head.modifiedTime}", style = MaterialTheme.typography.labelMedium)
                        }
                        Text("发生时间: ${head.eventTime} (${head.zone})", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        SelectionContainer {
                            Text(head.text, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onChooseHead(head.headId) },
                            modifier = Modifier.align(Alignment.End).defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("采纳此分支 (归档其他)")
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onManualIntegrate,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            ) {
                Text("手动整合文字并重新保存")
            }
        }
    }
}

/**
 * 共享草稿冲突卡片
 */
@Composable
private fun DraftConflictCard(
    item: DraftConflictItem,
    onChooseHead: (chosenHeadId: String, makeOtherNewGeneration: Boolean) -> Unit,
    onDiscardHead: (discardedHeadId: String) -> Unit,
    onForkDraft: (headId: String) -> Unit,
    onManualIntegrate: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "共享草稿冲突 · 槽位 [${item.slot}]",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "两端在未联网时分别编写或修改了草稿。采纳草稿或整合后将归档（retired）其他展示的分支。彻底删除（purge）拥有永久围栏，不可恢复。" +
                    if (item.heads.any { it.isEndedGeneration }) "（提示：此槽位包含另一端已结束的草稿代残留。此类残留不可原地采纳为当前草稿代，请选择“转存为新的草稿代”或“彻底放弃”。）" else "",
                style = MaterialTheme.typography.bodyMedium
            )

            item.heads.forEachIndexed { idx, head ->
                val label = buildString {
                    append("草稿候选 ${idx + 1} (${head.originName})")
                    if (head.isTerminal) append(" [终结候选: ${head.mode ?: "已终结"}]")
                    else if (head.isEndedGeneration) append(" [已结束代残留]")
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, fontWeight = FontWeight.SemiBold)
                            Text("更新于: ${head.updatedAt}", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        SelectionContainer {
                            Text(head.text, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (head.isEndedGeneration && !head.isTerminal && head.mode == null) {
                                Button(
                                    onClick = { onForkDraft(head.headId) },
                                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text("转存为新的草稿代")
                                }
                                TextButton(
                                    onClick = { onDiscardHead(head.headId) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text("彻底放弃此候选")
                                }
                            } else if (head.isTerminal || head.mode != null) {
                                Button(
                                    onClick = { onChooseHead(head.headId, false) },
                                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text("选用终结状态 (归档其他)")
                                }
                                TextButton(
                                    onClick = { onDiscardHead(head.headId) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text("彻底放弃此候选")
                                }
                            } else {
                                Button(
                                    onClick = { onChooseHead(head.headId, true) },
                                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text("采纳此草稿 (未选转新草稿代)")
                                }
                                OutlinedButton(
                                    onClick = { onChooseHead(head.headId, false) },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text("仅采纳 (归档其他)")
                                }
                                TextButton(
                                    onClick = { onDiscardHead(head.headId) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text("彻底放弃")
                                }
                            }
                        }
                    }
                }
            }

            val hasTextCandidates = item.heads.any { !it.isTerminal && it.mode == null && !it.isEndedGeneration }
            if (hasTextCandidates) {
                OutlinedButton(
                    onClick = onManualIntegrate,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("手动整合草稿文字并重新保存")
                }
            }
        }
    }
}

/**
 * 日报冲突卡片 (禁止编辑正文，仅完整版本二选一)
 */
@Composable
private fun ReportConflictCard(
    item: ReportConflictItem,
    onChooseCandidate: (headId: String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "日报并发生成冲突 · 日期: ${item.date}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "两端各自通过 AI 生成了该日期的日报正文。日报正文严格只读，请选择一份作为当前正式日报。选用后未选候选的正文载荷将被清空归档（retired）；彻底删除（purge）不可恢复：",
                style = MaterialTheme.typography.bodyMedium
            )

            item.candidates.forEachIndexed { idx, cand ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("候选 ${idx + 1} (${cand.model})", fontWeight = FontWeight.SemiBold)
                            Text(cand.generatedAt, style = MaterialTheme.typography.labelMedium)
                        }
                        Text("关联事实记录: ${cand.sourceCount} 条", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(6.dp))
                        SelectionContainer {
                            Text(cand.body, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onChooseCandidate(cand.headId) },
                            modifier = Modifier.align(Alignment.End).defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("选用此版本作为当前日报 (清空并归档其他)")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 成果补充问答冲突卡片
 */
@Composable
private fun AnswerConflictCard(
    item: AnswerConflictItem,
    onChooseCandidate: (headId: String) -> Unit,
    onManualIntegrate: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "成果补充问答冲突 · ${item.date}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "采纳某一回答或手动整合将产生新版本，未选回答分支将被归档（retired）；彻底删除具备永久围栏，不可恢复：",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("Q: ${item.questionText}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)

            item.candidates.forEachIndexed { idx, cand ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("回答 ${idx + 1} (${cand.originName})", fontWeight = FontWeight.SemiBold)
                            Text(cand.answeredAt, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        SelectionContainer {
                            Text(if (cand.skipped) "[已跳过此问题]" else cand.text, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = { onChooseCandidate(cand.headId) },
                            modifier = Modifier.align(Alignment.End).defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("采纳此回答 (归档其他分支)")
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onManualIntegrate,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            ) {
                Text("手动整合回答文字并重新保存")
            }
        }
    }
}

/**
 * 手动记录文字整合对话框
 */
@Composable
private fun ManualRecordIntegrationDialog(
    item: RecordConflictItem,
    errorMessage: String? = null,
    isResolving: Boolean = false,
    onConfirm: (baselineHeadId: String, text: String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedHeadId by remember(item) {
        mutableStateOf(item.heads.firstOrNull()?.headId.orEmpty())
    }
    val selectedHead = item.heads.find { it.headId == selectedHeadId }
    var text by remember(item, selectedHeadId) {
        mutableStateOf(selectedHead?.text.orEmpty())
    }

    AlertDialog(
        onDismissRequest = { if (!isResolving) onDismiss() },
        title = {
            Text("手动整合记录正文", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Text(
                            text = "保存失败: $errorMessage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "注意：保存整合正文将以所选基准分支保留发生时间、删除状态（选定分支若已移入删除则保持删除，不会隐式恢复）与元数据，并产生新版本。界面展示的其他所有冲突分支均将被明确归档（retired）。彻底删除（purge）具备永久围栏，不可通过整合恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                Text("请选择一个分支作为时间、删除状态与身份基准（仅替换正文，不合并时间）：", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)

                item.heads.forEach { head ->
                    val isSelected = head.headId == selectedHeadId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isResolving) {
                                selectedHeadId = head.headId
                                text = head.text
                            },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                enabled = !isResolving,
                                onClick = {
                                    selectedHeadId = head.headId
                                    text = head.text
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "基准: ${head.originName} · " + (if (head.isDeleted) "已移入最近删除" else "正式有效记录") + " · 发生于: ${head.eventTime} (${head.zone})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (head.isDeleted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "修改时间: ${head.modifiedTime}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !isResolving,
                    label = { Text("整合后的正文") },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    supportingText = { Text("必填非空；保存后仅替换所选分支的正文内容") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedHeadId, text.trim()) },
                enabled = !isResolving && text.isNotBlank() && selectedHeadId.isNotBlank(),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text(if (isResolving) "正在保存..." else "保存整合版本 (归档其他)")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isResolving,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("取消")
            }
        }
    )
}

/**
 * 手动共享草稿文字整合对话框
 * 严禁提供终结候选（已保存/已放弃）作为文本基准
 */
@Composable
private fun ManualDraftIntegrationDialog(
    item: DraftConflictItem,
    errorMessage: String? = null,
    isResolving: Boolean = false,
    onConfirm: (baselineHeadId: String, text: String) -> Unit,
    onDismiss: () -> Unit
) {
    val textCandidates = remember(item) {
        item.heads.filter { !it.isTerminal && it.mode == null && !it.isEndedGeneration }
    }
    var selectedHeadId by remember(item, textCandidates) {
        mutableStateOf(textCandidates.firstOrNull()?.headId.orEmpty())
    }
    val selectedHead = textCandidates.find { it.headId == selectedHeadId }
    var text by remember(item, selectedHeadId) {
        mutableStateOf(selectedHead?.text.orEmpty())
    }

    AlertDialog(
        onDismissRequest = { if (!isResolving) onDismiss() },
        title = {
            Text("手动整合草稿文字", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Text(
                            text = "保存失败: $errorMessage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "注意：保存整合草稿将产生新草稿版本，本界面展示的其他草稿分支均将被明确归档（retired）。如需保留未选分支正文，请使用卡片中的“未选转新草稿代”。终结候选不作为文本基准；彻底删除不可逆。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                if (textCandidates.isEmpty()) {
                    Text(
                        text = "该草稿槽位当前没有可编辑的活动文本草稿候选（候选均为终结候选或已结束代残留）。请直接在卡片中选择“转存为新的草稿代”、选用终结状态或彻底放弃候选。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = "请选择一个非终结草稿作为身份与时间基准（仅替换文字）：",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    textCandidates.forEach { head ->
                        val isSelected = head.headId == selectedHeadId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isResolving) {
                                    selectedHeadId = head.headId
                                    text = head.text
                                },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    enabled = !isResolving,
                                    onClick = {
                                        selectedHeadId = head.headId
                                        text = head.text
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "基准草稿: ${head.originName} · 更新于: ${head.updatedAt}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "本地时间: ${head.local}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        enabled = !isResolving,
                        label = { Text("整合后的草稿正文") },
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        supportingText = { Text("必填非空；保存后仅替换所选草稿分支的正文") }
                    )
                }
            }
        },
        confirmButton = {
            if (textCandidates.isNotEmpty()) {
                Button(
                    onClick = { onConfirm(selectedHeadId, text.trim()) },
                    enabled = !isResolving && text.isNotBlank() && selectedHeadId.isNotBlank(),
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text(if (isResolving) "正在保存..." else "保存整合草稿 (归档其他)")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isResolving,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("取消")
            }
        }
    )
}

/**
 * 手动整合成果补充问答对话框
 */
@Composable
private fun ManualAnswerIntegrationDialog(
    item: AnswerConflictItem,
    errorMessage: String? = null,
    isResolving: Boolean = false,
    onConfirm: (text: String) -> Unit,
    onDismiss: () -> Unit
) {
    val initialText = remember(item) {
        item.candidates.firstOrNull { it.text.isNotBlank() }?.text.orEmpty()
    }
    var text by remember(item) { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = { if (!isResolving) onDismiss() },
        title = {
            Text("手动整合补充答案", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Text(
                            text = "保存失败: $errorMessage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "注意：保存整合回答将产生新版本，本界面展示的其他回答分支均将被明确归档（retired）。原题面与存证元数据将继续保留；彻底删除不可逆。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                Text(
                    text = "针对问题: ${item.questionText}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "日期: ${item.date} · 保留题面与首选分支存证",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !isResolving,
                    label = { Text("整合后的回答内容") },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    supportingText = { Text("必填非空；保存后将作为该问题的当前正式回答") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = !isResolving && text.isNotBlank(),
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text(if (isResolving) "正在保存..." else "保存整合回答 (归档其他)")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isResolving,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun GoalEntityConflictCard(entity: app.liferecord.core.EntityState, enabled: Boolean,
    onChoose: (String) -> Unit, onIntegrate: (String) -> Unit) {
    var selected by remember(entity.kind, entity.key) { mutableStateOf<String?>(null) }
    var integrated by remember(entity.kind, entity.key) { mutableStateOf<String?>(null) }
    var confirm by remember(entity.kind, entity.key) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when (entity.kind) { "goal" -> "目标定义"; "goal-confirmation" -> "里程碑用户声明"; else -> "目标周期回顾" }, fontWeight = FontWeight.Bold)
        Text(entity.key, style = MaterialTheme.typography.bodySmall)
        Text("只终结已显示候选；不按时间选择胜者。回顾只能选择完整候选。")
        entity.heads.forEach { head ->
            Surface(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("来源：${head.id.substringBefore('/')} · ${head.id}")
                    SelectionContainer { Text(head.payload.toString(), style = MaterialTheme.typography.bodySmall) }
                    Row {
                        TextButton(enabled = enabled, onClick = { selected = head.id; integrated = null; confirm = true }) { Text("采用完整候选") }
                        if (entity.kind != "goal-review") TextButton(enabled = enabled,
                            onClick = { selected = head.id; integrated = head.payload.toString() }) { Text("整合定义或声明") }
                    }
                }
            }
        }
    }
    if (integrated != null) AlertDialog(onDismissRequest = { integrated = null },
        title = { Text("整合完整内容") },
        text = { Column { Text("保留JSON字段与合法ID；目标标准文本改变须分配新标准ID。提交前将严格校验，失败保留输入。")
            OutlinedTextField(value = integrated.orEmpty(), onValueChange = { integrated = it }, modifier = Modifier.fillMaxWidth().height(300.dp)) } },
        confirmButton = { TextButton(enabled = enabled, onClick = { confirm = true }) { Text("核对后提交") } },
        dismissButton = { TextButton(onClick = { integrated = null }) { Text("返回") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("确认解决已显示候选") },
        text = { Text("本次仅终结当前所见 ${entity.heads.size} 个候选；未见的新候选仍保留。用户确认的完成进度可能重新计算。") },
        confirmButton = { TextButton(enabled = enabled, onClick = {
            confirm = false
            val text = integrated
            if (text != null) onIntegrate(text) else selected?.let(onChoose)
        }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}
