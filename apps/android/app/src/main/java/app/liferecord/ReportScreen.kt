package app.liferecord

import app.liferecord.core.*

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * AI事实日报主界面 (ReportScreen)：
 * - 纯状态驱动配合纯回调。
 * - 顶栏日期选择器 + 前一天/后一天切换（不能选未来，默认昨天）。
 * - 保护未保存草稿：日期切换、刷新、普通更新、来源跳转均经过草稿防丢失检查与确认。
 * - 普通主要动作：“更新日报”；强制付费重新生成在“更多”菜单中。
 * - day.running = true 遇到保存/存储失败时暴露“本地保存重试”与“取消”。
 * - 成功/普通状态提示不误用红色 errorContainer。
 * - 来源核对：展示 source 版本校验（不误报修改时间），提供“核对并保存”以重新确认已修正的源。
 * - 成果补充（问题）：当前活动问题最多 3 个未回答/未跳过的项；历史及不适用问题集中在可展开的“往期历史答案”区，支持编辑/重开。
 * - 术语统一：“AI事实日报”、“成果补充”、“当天适用的补充答案”。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportScreen(
    state: ReportUiState,
    onDate: (String) -> Unit,
    onGenerate: (Boolean, Boolean) -> Unit,
    onCancel: () -> Unit,
    onAnswer: (String, String, Set<String>, String) -> Unit,
    onSkip: (String, Boolean, Set<String>, String) -> Unit,
    onSettings: () -> Unit,
    onSource: (String) -> Unit,
    onRefresh: () -> Unit,
    onDirtyChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    val currentDate = state.date
    val day = state.day
    val report = day?.report
    val allQuestions = day?.questions ?: emptyList()

    // Active questions: max 3 unanswered non-skipped questions
    val activeQuestions = remember(allQuestions) {
        allQuestions.filter { it.applicable && it.answer.isBlank() && !it.skipped }.take(3)
    }
    // Historical / saved / skipped / inapplicable questions in expandable evidence
    val evidenceQuestions = remember(allQuestions) {
        allQuestions.filter { !activeQuestions.contains(it) }
    }

    val records = day?.records ?: emptyList()

    // Answer drafts mapping: key is "date:questionId", value is text.
    val draftMapSaver = listSaver<MutableMap<String, String>, String>(
        save = { map ->
            map.entries.flatMap { listOf(it.key, it.value) }
        },
        restore = { list ->
            val restored = mutableStateMapOf<String, String>()
            var idx = 0
            while (idx + 1 < list.size) {
                restored[list[idx]] = list[idx + 1]
                idx += 2
            }
            restored
        }
    )

    val answerDrafts = rememberSaveable(saver = draftMapSaver) {
        mutableStateMapOf<String, String>()
    }
    val answerBases = rememberSaveable(saver = draftMapSaver) { mutableStateMapOf<String, String>() }
    val answerIntents = rememberSaveable(saver = draftMapSaver) { mutableStateMapOf<String, String>() }
    val answerBuffers = remember { mutableStateMapOf<String, androidx.compose.ui.text.input.TextFieldValue>() }
    val observedAnswers = rememberSaveable(saver = draftMapSaver) { mutableStateMapOf<String, String>() }
    var remoteAnswer by remember { mutableStateOf<ReportQuestion?>(null) }
    fun buffer(q: ReportQuestion): androidx.compose.ui.text.input.TextFieldValue {
        val key = "$currentDate:${q.id}"
        return answerBuffers[key] ?: androidx.compose.ui.text.input.TextFieldValue(answerDrafts[key] ?: q.answer)
    }
    fun changeAnswer(q: ReportQuestion, value: androidx.compose.ui.text.input.TextFieldValue) {
        val key = "$currentDate:${q.id}"
        answerBases.getOrPut(key) { q.heads.sorted().joinToString("\u0000") }
        answerBuffers[key] = value
        if (answerDrafts[key] != value.text) {
            answerDrafts[key] = value.text
            answerIntents.remove(key)
        }
    }
    fun base(q: ReportQuestion): Set<String> {
        val key = "$currentDate:${q.id}"
        return answerBases.getOrPut(key) { q.heads.sorted().joinToString("\u0000") }
            .split('\u0000').filter(String::isNotEmpty).toSet()
    }
    fun submitAnswer(q: ReportQuestion, text: String) {
        val key = "$currentDate:${q.id}"
        val intent = answerIntents.getOrPut(key) { java.util.UUID.randomUUID().toString() }
        onAnswer(q.id, text, base(q), intent)
    }

    // Initialize drafts from all questions so historical / evidence items can also be edited
    LaunchedEffect(currentDate, day) {
        allQuestions.forEach { q ->
            val key = "$currentDate:${q.id}"
            val old = observedAnswers[key]
            val composing = answerBuffers[key]?.composition != null
            if (!answerDrafts.containsKey(key) || !composing &&
                (answerDrafts[key] == old || answerDrafts[key] == q.answer && answerIntents.containsKey(key))) {
                if (answerDrafts[key] != q.answer) answerBuffers[key] = androidx.compose.ui.text.input.TextFieldValue(q.answer)
                answerDrafts[key] = q.answer
                answerBases[key] = q.heads.sorted().joinToString("\u0000")
                answerIntents.remove(key)
            }
            observedAnswers[key] = q.answer
        }
    }
    remoteAnswer?.let { selected ->
        ConfirmDialog("应用已同步答复？", "将替换此题尚未保存的本机输入。同步答复：\n${selected.answer}", "确认应用", {
            val current = allQuestions.firstOrNull { it.id == selected.id }
            if (current != null && current.heads == selected.heads) {
                val key = "$currentDate:${current.id}"
                answerDrafts[key] = current.answer
                answerBuffers[key] = androidx.compose.ui.text.input.TextFieldValue(current.answer)
                answerBases[key] = current.heads.sorted().joinToString("\u0000")
                observedAnswers[key] = current.answer
                answerIntents.remove(key)
            }
            remoteAnswer = null
        }, { remoteAnswer = null })
    }

    val orphanedAnswers = answerDrafts.filter { (key, text) ->
        key.startsWith("$currentDate:") && text.isNotBlank() && allQuestions.none { key == "$currentDate:${it.id}" }
    }
    // Compute if there are unsaved drafts on any question
    val isDirty = remember(currentDate, allQuestions, answerDrafts.toMap()) {
        allQuestions.any { q ->
            val key = "$currentDate:${q.id}"
            val draft = answerDrafts[key] ?: q.answer
            draft != q.answer
        } || orphanedAnswers.isNotEmpty()
    }

    LaunchedEffect(isDirty) {
        onDirtyChange(isDirty)
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

    var showMenu by remember { mutableStateOf(false) }
    var showForceRegenerateDialog by remember { mutableStateOf(false) }
    var showClearEmptyDialog by remember { mutableStateOf(false) }
    var expandedSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var showEvidenceSection by rememberSaveable { mutableStateOf(false) }

    val todayStr = LocalDate.now().toString()
    val isToday = currentDate == todayStr

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top action bar / date strip
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = {
                                guardAction {
                                    val prev = LocalDate.parse(currentDate).minusDays(1).toString()
                                    onDate(prev)
                                }
                            },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "前一天")
                        }

                        TextButton(
                            onClick = {
                                guardAction {
                                    val parsed = LocalDate.parse(currentDate)
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            val picked = LocalDate.of(y, m + 1, d).toString()
                                            if (!LocalDate.parse(picked).isAfter(LocalDate.now())) {
                                                onDate(picked)
                                            }
                                        },
                                        parsed.year,
                                        parsed.monthValue - 1,
                                        parsed.dayOfMonth
                                    ).show()
                                }
                            },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                            Text(currentDate, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        IconButton(
                            onClick = {
                                guardAction {
                                    val next = LocalDate.parse(currentDate).plusDays(1).toString()
                                    if (!LocalDate.parse(next).isAfter(LocalDate.now())) {
                                        onDate(next)
                                    }
                                }
                            },
                            enabled = !isToday,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "后一天")
                        }
                    }

                    IconButton(
                        onClick = { guardAction(onSettings) },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "AI 设置")
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isRunning = day?.running == true
                    if (isRunning) {
                        val isSaveFailurePending = day.message.contains("保存") || day.message.contains("存储") || day.message.contains("重试")
                        if (isSaveFailurePending) {
                            Button(
                                onClick = { onGenerate(false, false) },
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("本地保存重试")
                            }
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("取消")
                            }
                        } else {
                            Button(
                                onClick = onCancel,
                                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("取消生成")
                            }
                        }
                    } else {
                        Button(
                            onClick = { guardAction { onGenerate(false, false) } },
                            enabled = !state.loading,
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text(if (report == null) "生成事实日报" else "更新日报")
                        }

                        OutlinedButton(
                            onClick = { guardAction(onRefresh) },
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }

                        Box {
                            OutlinedButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Text("更多")
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                if (report != null) {
                                    DropdownMenuItem(
                                        text = { Text("强制重新生成 (可能产生费用)") },
                                        onClick = {
                                            showMenu = false
                                            guardAction { showForceRegenerateDialog = true }
                                        }
                                    )
                                }

                                if (records.isEmpty() && report != null) {
                                    DropdownMenuItem(
                                        text = { Text("清空空日报", color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showMenu = false
                                            guardAction { showClearEmptyDialog = true }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Main content area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (state.loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("正在加载 AI事实日报与记录…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("report-content"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (state.message.isNotBlank()) item { InfoPanel("日报提示", state.message) }
                    items(orphanedAnswers.entries.toList(), key = { "local-answer-${it.key}" }) { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoPanel("旧题的本机未保存答复", "同步后当前题面已不可用，原输入仍仅保留在本机，不会自动提交或参与AI。请核对后复制到适用问题；不会猜测新题面或证据。")
                            OutlinedTextField(
                                value = answerBuffers[entry.key] ?: androidx.compose.ui.text.input.TextFieldValue(entry.value),
                                onValueChange = { value -> answerBuffers[entry.key] = value; answerDrafts[entry.key] = value.text; answerIntents.remove(entry.key) },
                                modifier = Modifier.fillMaxWidth(), label = { Text("保留的本机输入") }, minLines = 2, maxLines = 5,
                            )
                        }
                    }
                    if (!state.configured) item {
                        InfoPanel("AI尚未配置或未授权", "请先设置HTTPS服务、模型和密钥，并确认选定日期数据会发送到外部服务，可能产生费用。")
                        TextButton(onClick = { guardAction(onSettings) }) { Text("前往AI设置") }
                    }
                    if (day?.running == true) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Text("AI 正在合成事实日报…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                    if (day.message.isNotBlank()) {
                                        Text(day.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                    day.retryAt?.let { retryAt ->
                                        val secondsLeft = ((retryAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                                        Text("网络波动，将在 ${secondsLeft} 秒后自动重试…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                        }
                    }

                    if (day?.message?.isNotBlank() == true && day.running != true) {
                        val isErrorMessage = day.message.contains("失败") || day.message.contains("错误") || day.message.contains("异常") || day.message.contains("超时") || day.message.contains("拒绝")
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = if (isErrorMessage) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (!isErrorMessage) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = if (isErrorMessage) "生成遇到异常" else "状态提示",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isErrorMessage) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = day.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isErrorMessage) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (isErrorMessage) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = { guardAction { onGenerate(false, false) } },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Text("重试更新")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (day?.changed == true && report != null && day.running != true) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("记录在生成后已发生变更 (Stale)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("当前事实日报基于过去的记录版本。您可以更新日报以纳入最新修改与补充记录。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button(onClick = { guardAction { onGenerate(false, false) } }) {
                                        Text("更新日报")
                                    }
                                }
                            }
                        }
                    }

                    // Report Body & Paragraphs
                    if (report != null) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isToday) "事实正文 (${report.date} 截至目前)" else "事实正文 (${report.date})",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = "生成于: ${RecordTime.displayStamp(report.generatedAt)} · 模型: ${report.model} · 服务: ${report.service}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (report.paragraphs.isEmpty()) {
                            item {
                                InfoPanel(
                                    title = "该日期暂无活动事实",
                                    body = "选定日期内没有查找到有效的活动记录，或已清空空日报。您可以新增记录后更新日报。"
                                )
                            }
                        } else {
                            itemsIndexed(report.paragraphs, key = { index, _ -> "para-$index" }) { _, para ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SelectionContainer {
                                            Text(
                                                text = para.text,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        if (para.recordIds.isNotEmpty()) {
                                            FlowRow(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = "来源记录:",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.align(Alignment.CenterVertically)
                                                )
                                                para.recordIds.forEach { recId ->
                                                    val matchedRecord = records.find { it.id == recId }
                                                    val labelText = if (matchedRecord != null) {
                                                        "记录 #${recId.take(4)}"
                                                    } else {
                                                        "记录 #${recId.take(4)} (已删/改)"
                                                    }
                                                    AssistChip(
                                                        onClick = { guardAction { onSource(recId) } },
                                                        label = { Text(labelText) },
                                                        colors = AssistChipDefaults.assistChipColors(
                                                            containerColor = if (matchedRecord != null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
                                                        )
                                                    )
                                                }
                                            }
                                        }

                                        if (para.answerIds.isNotEmpty()) {
                                            FlowRow(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = "引证答复:",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.align(Alignment.CenterVertically)
                                                )
                                                para.answerIds.forEach { ansId ->
                                                    val q = allQuestions.find { it.id == ansId }
                                                    val ansSummary = if (q != null && q.answer.isNotBlank()) {
                                                        "答复: ${q.answer.take(8)}…"
                                                    } else {
                                                        "答复 #${ansId.take(4)}"
                                                    }
                                                    AssistChip(
                                                        onClick = {},
                                                        label = { Text(ansSummary) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Sources metadata section
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "来源核对 (${report.sources.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        items(report.sources, key = { it.id }) { src ->
                            val matchedRecord = records.find { it.id == src.id }
                            val isExpanded = expandedSourceId == src.id
                            // Avoid claiming modified means changed since generation; state.day.changed covers overall status.
                            val statusText = if (matchedRecord != null) "当前记录（生成后是否改变见日报状态）" else "已删除或移动"

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedSourceId = if (isExpanded) null else src.id
                                    },
                                shape = MaterialTheme.shapes.small,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("源记录 #${src.id.take(6)}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (matchedRecord != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                        )
                                    }

                                    if (isExpanded) {
                                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                                        if (matchedRecord != null) {
                                            Text("发生时间: ${matchedRecord.time.display()}", style = MaterialTheme.typography.bodySmall)
                                            Text("记录正文: ${matchedRecord.text}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                                            Spacer(modifier = Modifier.height(4.dp))
                                            // Explicit '核对并保存' editor button to reconfirm answered question after source correction
                                            Button(
                                                onClick = {
                                                    // Reconfirm latest answers against source
                                                    allQuestions.filter { it.recordIds.contains(src.id) }.forEach { q ->
                                                        val key = "$currentDate:${q.id}"
                                                        val currentAns = answerDrafts[key] ?: q.answer
                                                        if (currentAns.isNotBlank()) {
                                                            submitAnswer(q, currentAns)
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                                            ) {
                                                Text("核对并保存关联答复", style = MaterialTheme.typography.labelMedium)
                                            }
                                        } else {
                                            Text("该源记录已被删除或移动。关联日报结果可能需要重新生成以排除失效来源。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(if (isExpanded) "点击折叠" else "点击展开详情", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        TextButton(
                                            onClick = { guardAction { onSource(src.id) } },
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("跳转源记录", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }

                    } else {
                        item {
                            InfoPanel(
                                title = "尚无 AI事实日报",
                                body = "生成后将按时间整理该日正式记录及当天适用的补充答案。没有记录不代表没有行动。"
                            )
                        }
                    }

                    // Active Outcome Questions Section (max 3 unanswered non-skipped questions)
                    if (activeQuestions.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "成果补充 (${activeQuestions.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "对活动缺失结果的补充说明（最多 3 个未回答项）。修改后需点击保存统一提交。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        items(activeQuestions, key = { it.id }) { q ->
                            val key = "$currentDate:${q.id}"
                            val draftText = answerDrafts[key] ?: q.answer
                            val isSaved = draftText == q.answer
                            val hasLocalChange = draftText != q.answer

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, if (hasLocalChange) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        text = q.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    OutlinedTextField(
                                        value = buffer(q),
                                        onValueChange = { changeAnswer(q, it) },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("输入补充答案") },
                                        maxLines = 3
                                    )
                                    if (base(q) != q.heads) {
                                        Text("另一端已更新此答复；本机输入和原保存基线保留。", style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { remoteAnswer = q }) { Text("核对并应用已同步答复") }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = {
                                                keyboard?.hide()
                                                submitAnswer(q, draftText)
                                            },
                                            enabled = hasLocalChange,
                                            modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                                        ) {
                                            Text(if (isSaved) "已保存" else "保存补充答案")
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                keyboard?.hide()
                                                onSkip(q.id, !q.skipped, q.heads, java.util.UUID.randomUUID().toString())
                                            },
                                            modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                                        ) {
                                            Text("跳过")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Expandable Evidence Section for all saved / skipped / inapplicable answers (with edit / reopen)
                    if (evidenceQuestions.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showEvidenceSection = !showEvidenceSection },
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("当天适用的补充答案与历史存证 (${evidenceQuestions.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(if (showEvidenceSection) "折叠" else "展开查看与编辑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        if (showEvidenceSection) {
                            items(evidenceQuestions, key = { "ev-${it.id}" }) { eq ->
                                val key = "$currentDate:${eq.id}"
                                val draftText = answerDrafts[key] ?: eq.answer
                                val hasChange = draftText != eq.answer

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.small,
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(eq.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                            if (eq.skipped) {
                                                AssistChip(onClick = {}, label = { Text("已跳过") })
                                            }
                                        }

                                        eq.answeredAt?.let { answeredAt ->
                                            Text("保存时刻: ${RecordTime.displayStamp(answeredAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }

                                        OutlinedTextField(
                                            value = buffer(eq),
                                            onValueChange = { changeAnswer(eq, it) },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text("修改补充答案") },
                                            maxLines = 2
                                        )
                                        if (base(eq) != eq.heads) {
                                            Text("另一端已更新此答复；本机输入和原保存基线保留。", style = MaterialTheme.typography.bodySmall)
                                            TextButton(onClick = { remoteAnswer = eq }) { Text("核对并应用已同步答复") }
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = {
                                                    keyboard?.hide()
                                                    submitAnswer(eq, draftText)
                                                },
                                                enabled = hasChange,
                                                modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                                            ) {
                                                Text("更新答案")
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    keyboard?.hide()
                                                    onSkip(eq.id, !eq.skipped, eq.heads, java.util.UUID.randomUUID().toString())
                                                },
                                                modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                                            ) {
                                                Text(if (eq.skipped) "重新开启" else "跳过")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    if (showForceRegenerateDialog) {
        AlertDialog(
            onDismissRequest = { showForceRegenerateDialog = false },
            title = { Text("确认强制重新生成事实日报？") },
            text = { Text("强制重新生成将消耗外部 AI 配额并可能产生费用。当前日报正文将被覆盖并重新核对来源。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForceRegenerateDialog = false
                        onGenerate(true, false)
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text("确认生成", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showForceRegenerateDialog = false },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text("取消")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge
        )
    }

    if (showClearEmptyDialog) {
        AlertDialog(
            onDismissRequest = { showClearEmptyDialog = false },
            title = { Text("清空空日报？") },
            text = { Text("选定日期已无有效记录。清空将移除日报展示（无论原正文是否非空），已保存的补充答案和历史存证将安全保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearEmptyDialog = false
                        onGenerate(false, true)
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text("确认清空", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showClearEmptyDialog = false
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text("取消")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge
        )
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("有未保存的补充答案草稿") },
            text = { Text("您对补充答案有尚未显式保存的修改。直接切换或刷新将导致输入丢失。请选择操作：") },
            confirmButton = {
                TextButton(
                    onClick = {
                        allQuestions.forEach { q ->
                            val key = "$currentDate:${q.id}"
                            val draft = answerDrafts[key]
                            if (draft != null && draft != q.answer) {
                                submitAnswer(q, draft)
                            }
                        }
                        showUnsavedDialog = false
                        val act = pendingAction
                        pendingAction = null
                        act?.invoke()
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text("保存并继续", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            val act = pendingAction
                            pendingAction = null
                            act?.invoke()
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Text("放弃修改", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            pendingAction = null
                        },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Text("留在当前")
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge
        )
    }
}
