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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.liferecord.core.DailyReport
import app.liferecord.core.Record
import app.liferecord.core.ReportQuestion
import java.time.LocalDate
import java.util.UUID

enum class DesktopCoordinationStatus {
    IDLE,
    RUNNING_LOCAL,
    RUNNING_REMOTE,
    UNKNOWN_TIMEOUT,
    FINISHED
}

data class ReportsScreenUiState(
    val date: String = LocalDate.now().minusDays(1).toString(),
    val report: DailyReport? = null,
    val questions: List<ReportQuestion> = emptyList(),
    val records: List<Record> = emptyList(),
    val isChanged: Boolean = false,
    val isRunning: Boolean = false,
    val coordinationStatus: DesktopCoordinationStatus = DesktopCoordinationStatus.IDLE,
    val leaseSecondsRemaining: Int = 0,
    val remoteHolderName: String? = null,
    val canRetryLocalSave: Boolean = false,
    val message: String = "",
    val isLoading: Boolean = false
)

/**
 * Windows 桌面端日报面板 (双列布局，正文严格只读)
 */
@Composable
fun ReportsScreen(
    state: ReportsScreenUiState,
    onDateChange: (String) -> Unit,
    onGenerate: (force: Boolean) -> Unit,
    onCancel: () -> Unit,
    onRetryLocalSave: () -> Unit,
    onAnswer: (questionId: String, text: String, baseHeads: Set<String>, intentId: String) -> Unit,
    onSkip: (questionId: String, skip: Boolean, baseHeads: Set<String>, intentId: String) -> Unit,
    onSelectSource: (recordId: String) -> Unit
) {
    var showForceConfirm by remember { mutableStateOf(false) }

    val currentDate = state.date
    val allQuestions = state.questions

    // 答复草稿、基线 observed heads 及稳定意图 ID，按 "$currentDate:$questionId" 隔离持久化
    val answerDrafts = remember { mutableStateMapOf<String, String>() }
    val answerBases = remember { mutableStateMapOf<String, String>() }
    val answerIntents = remember { mutableStateMapOf<String, String>() }

    fun base(q: ReportQuestion): Set<String> {
        val key = "$currentDate:${q.id}"
        return answerBases.getOrPut(key) { q.heads.sorted().joinToString("\u0000") }
            .split('\u0000').filter(String::isNotEmpty).toSet()
    }

    fun submitAnswer(q: ReportQuestion, text: String) {
        val key = "$currentDate:${q.id}"
        val intent = answerIntents.getOrPut(key) { UUID.randomUUID().toString() }
        onAnswer(q.id, text, base(q), intent)
    }

    fun submitSkip(q: ReportQuestion, skip: Boolean) {
        val key = "$currentDate:${q.id}"
        val intent = answerIntents.getOrPut(key) { UUID.randomUUID().toString() }
        onSkip(q.id, skip, base(q), intent)
    }

    // 仅在问答初始化或外部已提交成功后更新基线，决不在同步接收时重置脏草稿
    LaunchedEffect(currentDate, allQuestions) {
        allQuestions.forEach { q ->
            val key = "$currentDate:${q.id}"
            if (!answerDrafts.containsKey(key)) {
                answerDrafts[key] = q.answer
                base(q)
            }
            if (answerDrafts[key] == q.answer && answerIntents.containsKey(key)) {
                answerBases[key] = q.heads.sorted().joinToString("\u0000")
                answerIntents.remove(key)
            }
        }
    }

    // 计算是否存在未保存修改
    val isDirty = allQuestions.any { q ->
        val key = "$currentDate:${q.id}"
        val draft = answerDrafts[key] ?: q.answer
        draft != q.answer
    }

    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    fun guardAction(action: () -> Unit) {
        if (isDirty) {
            pendingAction = action
            showUnsavedDialog = true
        } else {
            action()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
    ) {
        // 顶栏：日期导航与快速切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        guardAction {
                            val prev = runCatching { LocalDate.parse(state.date).minusDays(1).toString() }.getOrDefault(state.date)
                            onDateChange(prev)
                        }
                    }
                ) {
                    Text("◀ 前一天")
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = state.date,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                OutlinedButton(
                    onClick = {
                        guardAction {
                            val next = runCatching { LocalDate.parse(state.date).plusDays(1).toString() }.getOrDefault(state.date)
                            onDateChange(next)
                        }
                    },
                    enabled = runCatching { LocalDate.parse(state.date).isBefore(LocalDate.now()) }.getOrDefault(false)
                ) {
                    Text("后一天 ▶")
                }

                TextButton(
                    onClick = {
                        guardAction { onDateChange(LocalDate.now().minusDays(1).toString()) }
                    }
                ) {
                    Text("昨天")
                }

                TextButton(
                    onClick = {
                        guardAction { onDateChange(LocalDate.now().toString()) }
                    }
                ) {
                    Text("今天")
                }
            }

            // 右侧生成/更新状态动作
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.canRetryLocalSave) {
                    Button(
                        onClick = onRetryLocalSave,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("本地落盘重试 (不耗Token)")
                    }
                }

                when (state.coordinationStatus) {
                    DesktopCoordinationStatus.RUNNING_LOCAL -> {
                        Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Text("取消生成")
                        }
                    }
                    DesktopCoordinationStatus.RUNNING_REMOTE -> {
                        Button(onClick = {}, enabled = false) {
                            Text("手机正在生成中 (${state.leaseSecondsRemaining}s)")
                        }
                    }
                    DesktopCoordinationStatus.UNKNOWN_TIMEOUT -> {
                        Button(
                            onClick = { guardAction { showForceConfirm = true } },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("强制重新生成 (心跳超时)")
                        }
                    }
                    else -> {
                        Button(
                            onClick = { guardAction { onGenerate(false) } },
                            enabled = !state.isRunning && !state.isLoading
                        ) {
                            Text(if (state.report == null) "生成日报" else if (state.isChanged) "更新日报 (源已变)" else "重新生成")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 顶层状态横幅：运行状态 / 跨端协调警告 / 过时提示
        if (state.isRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
        }

        when (state.coordinationStatus) {
            DesktopCoordinationStatus.RUNNING_REMOTE -> {
                DesktopInfoPanel(
                    title = "对端正在执行生成任务",
                    body = "${state.remoteHolderName ?: "手机端"} 正在生成该日期的日报 (租约保护剩余 ${state.leaseSecondsRemaining} 秒)。两端已在线互通，请等待手机生成并同步，避免重复调用计费。",
                    isWarning = true
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            DesktopCoordinationStatus.UNKNOWN_TIMEOUT -> {
                DesktopInfoPanel(
                    title = "跨端生成任务状态未知",
                    body = "手机端生成状态心跳已中断超过 180 秒。当前无法确认对端是否已完成调用。若立即重新生成，可能产生重复计费。",
                    isError = true
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            else -> {
                if (state.isChanged && state.report != null) {
                    DesktopInfoPanel(
                        title = "事实源记录已发生变更",
                        body = "该日期下的记录已发生修改、新增或删除，当前日报正文属于旧快照，建议点击“更新日报”重新整理。",
                        isWarning = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // 双列主体内容区
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 左列 (60%)：日报正文 (严格只读，可选中复制)
            Surface(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (state.isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (state.report == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "选定日期暂无日报",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "点击右上角“生成日报”，由配置的 AI 服务基于事实记录提炼整理",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        // 日报元数据信息
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "模型: ${state.report.model} · 服务: ${state.report.service}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "生成时间: ${state.report.generatedAt}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                        // 日报段落正文 (只读)
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                state.report.paragraphs.forEach { p ->
                                    Text(
                                        text = p.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 右列 (40%)：关联事实记录与成果补充问答
            Column(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 关联事实记录卡片 (上半部)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.45f),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "关联事实记录 (${state.records.size} 条)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (state.records.isEmpty()) {
                            Text(
                                text = "该日期暂无关联记录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(state.records, key = { it.id }) { rec ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { guardAction { onSelectSource(rec.id) } },
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = rec.time.local.replace('T', ' '),
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                                if (rec.deleted != null) {
                                                    DesktopStatusBadge("已删除", isError = true)
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = rec.text,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 2
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 成果补充问答 (下半部)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.55f),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "成果补充问答",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (state.questions.isEmpty()) {
                            Text(
                                text = "当前日报未生成跟进追问",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            state.questions.forEach { q ->
                                val key = "$currentDate:${q.id}"
                                val draft = answerDrafts[key] ?: q.answer
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(text = "Q: ${q.text}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        OutlinedTextField(
                                            value = draft,
                                            onValueChange = { answerDrafts[key] = it },
                                            placeholder = { Text("补充答复事实细节...") },
                                            modifier = Modifier.fillMaxWidth(),
                                            minLines = 2
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TextButton(onClick = { submitSkip(q, !q.skipped) }) {
                                                Text(if (q.skipped) "取消跳过" else "跳过")
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Button(
                                                onClick = { submitAnswer(q, draft.trim()) },
                                                enabled = draft.trim() != q.answer
                                            ) {
                                                Text("保存答复")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showForceConfirm) {
        DesktopConfirmDialog(
            title = "确认强制重新生成？",
            body = "手机端状态未知。强制请求将直接向外部第三方 AI 发送请求并可能产生扣费。确定继续？",
            confirmLabel = "强制生成",
            onConfirm = {
                showForceConfirm = false
                onGenerate(true)
            },
            onDismiss = { showForceConfirm = false },
            danger = true
        )
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = {
                Text(
                    text = "有未保存的补充答案草稿",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "您对补充答案有尚未显式保存的修改。切换日期、跳转来源或重新生成将导致输入丢失。请选择操作：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        allQuestions.forEach { q ->
                            val key = "$currentDate:${q.id}"
                            val draft = answerDrafts[key]
                            if (draft != null && draft != q.answer) {
                                submitAnswer(q, draft.trim())
                            }
                        }
                        showUnsavedDialog = false
                        val act = pendingAction
                        pendingAction = null
                        act?.invoke()
                    }
                ) {
                    Text("保存并继续", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            allQuestions.forEach { q ->
                                val key = "$currentDate:${q.id}"
                                answerDrafts[key] = q.answer
                            }
                            showUnsavedDialog = false
                            val act = pendingAction
                            pendingAction = null
                            act?.invoke()
                        }
                    ) {
                        Text("放弃修改", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            pendingAction = null
                        }
                    ) {
                        Text("留在当前")
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp)
        )
    }
}
