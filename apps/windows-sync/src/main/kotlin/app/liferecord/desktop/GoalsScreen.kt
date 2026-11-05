package app.liferecord.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.liferecord.core.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

@Composable
fun GoalsScreen(
    state: GoalUiState,
    actions: GoalActions,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 840.dp

        if (isWide) {
            TwoPaneGoalsLayout(
                state = state,
                actions = actions,
            )
        } else {
            SinglePaneGoalsLayout(
                state = state,
                actions = actions,
            )
        }

        // Global dialogs
        DiscardConfirmDialog(
            show = state.discardConfirmation,
            onConfirmDiscard = actions.onConfirmDiscard,
            onDismissDiscard = actions.onDismissDiscard,
        )

        GenerationConfirmationDialog(
            message = state.generationConfirmation,
            onConfirm = actions.onConfirmAnalysis,
            onDismiss = actions.onDismissAnalysis,
        )

        EvidenceTextDialog(
            text = state.evidenceText,
            onDismiss = actions.onDismissEvidence,
        )
    }
}

@Composable
private fun TwoPaneGoalsLayout(
    state: GoalUiState,
    actions: GoalActions,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Left pane: Goals list (width ~320dp)
        Surface(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                GoalsListHeader(
                    includeArchived = state.includeArchived,
                    onToggleIncludeArchived = actions.onToggleIncludeArchived,
                    onOpenCreate = actions.onOpenCreate,
                    onRefresh = actions.onRefresh,
                )
                HorizontalDivider()
                GoalsListItems(
                    goals = state.goals,
                    selectedGoalId = state.selectedGoalId,
                    isLoading = state.isLoading,
                    onSelectGoal = actions.onSelectGoal,
                    onOpenCreate = actions.onOpenCreate,
                )
            }
        }

        // Right pane: Detail or Editor
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background),
        ) {
            when {
                state.editor != null -> {
                    DesktopGoalEditorView(
                        editor = state.editor,
                        actions = actions,
                    )
                }
                state.selectedGoalId != null && state.detail != null -> {
                    DesktopGoalDetailView(
                        state = state,
                        actions = actions,
                    )
                }
                else -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (state.goals.isEmpty()) "请在左侧新建目标" else "请选择左侧目标查看详情与回顾",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SinglePaneGoalsLayout(
    state: GoalUiState,
    actions: GoalActions,
) {
    when {
        state.editor != null -> {
            DesktopGoalEditorView(
                editor = state.editor,
                actions = actions,
            )
        }
        state.selectedGoalId != null && state.detail != null -> {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    TextButton(onClick = { actions.onSelectGoal(null) }) {
                        Text("← 返回目标列表")
                    }
                }
                DesktopGoalDetailView(
                    state = state,
                    actions = actions,
                )
            }
        }
        else -> {
            Column(modifier = Modifier.fillMaxSize()) {
                GoalsListHeader(
                    includeArchived = state.includeArchived,
                    onToggleIncludeArchived = actions.onToggleIncludeArchived,
                    onOpenCreate = actions.onOpenCreate,
                    onRefresh = actions.onRefresh,
                )
                HorizontalDivider()
                GoalsListItems(
                    goals = state.goals,
                    selectedGoalId = state.selectedGoalId,
                    isLoading = state.isLoading,
                    onSelectGoal = actions.onSelectGoal,
                    onOpenCreate = actions.onOpenCreate,
                )
            }
        }
    }
}

@Composable
private fun GoalsListHeader(
    includeArchived: Boolean,
    onToggleIncludeArchived: (Boolean) -> Unit,
    onOpenCreate: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "目标与周期回顾",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row {
                TextButton(onClick = onRefresh) {
                    Text("刷新", fontSize = 12.sp)
                }
                Button(
                    onClick = onOpenCreate,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("＋ 新建", fontSize = 12.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = !includeArchived,
                onClick = { onToggleIncludeArchived(false) },
                label = { Text("进行中", fontSize = 12.sp) },
            )
            FilterChip(
                selected = includeArchived,
                onClick = { onToggleIncludeArchived(true) },
                label = { Text("包含已归档", fontSize = 12.sp) },
            )
        }
    }
}

@Composable
private fun GoalsListItems(
    goals: List<GoalSummary>,
    selectedGoalId: String?,
    isLoading: Boolean,
    onSelectGoal: (String?) -> Unit,
    onOpenCreate: () -> Unit,
) {
    if (isLoading && goals.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    } else if (goals.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "暂无目标",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenCreate) {
                    Text("新建目标")
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(goals, key = { it.spec?.id ?: it.hashCode().toString() }) { summary ->
                val spec = summary.spec
                val isSelected = spec?.id == selectedGoalId
                val containerColor = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.surface
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (spec != null) onSelectGoal(spec.id) },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = containerColor),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = spec?.title ?: "未命名目标",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            if (spec?.archived == true) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text(
                                        text = "已归档",
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${spec?.startDate ?: ""} ~ ${spec?.endDate ?: ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            if (summary.conflicted) {
                                Text(
                                    text = "待核对",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                )
                            } else {
                                Text(
                                    text = "${summary.completed}/${summary.total} (${summary.percent ?: 0}%)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopGoalDetailView(
    state: GoalUiState,
    actions: GoalActions,
) {
    val detail = state.detail ?: return
    val spec = detail.summary.spec ?: return
    val confirmationMap = remember(detail.confirmations) {
        detail.confirmations.associateBy { it.value.milestoneId }
    }
    val currentReview = remember(detail.reviews, state.selectedPeriodKey) {
        detail.reviews.firstOrNull { it.key == state.selectedPeriodKey }
    }

    var showConfirmDialogForMilestone by remember { mutableStateOf<GoalMilestone?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Title Bar & Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = spec.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "观察期限: ${spec.startDate} 至 ${spec.endDate} · 周期: 每 ${spec.cadenceDays} 天 · 时区: ${spec.reviewZone}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.onOpenEdit(spec.id) }) {
                    Text("编辑目标")
                }
                OutlinedButton(onClick = { actions.onToggleArchive(spec.id, !spec.archived) }) {
                    Text(if (spec.archived) "取消归档" else "归档目标")
                }
            }
        }

        if (spec.archived) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "此目标已归档，已停止周期自动分析，保留历史记录与分析回顾。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Conflicts
        GoalConflictBanner(
            conflicts = detail.conflicts,
            onResolveConflict = actions.onResolveConflict,
        )

        // Progress Bar
        GoalProgressBar(
            completed = detail.summary.completed,
            total = detail.summary.total,
            conflicted = detail.summary.conflicted,
        )

        // Desired Result Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "期望成果",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = spec.desiredResult,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Milestones
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "完成标准与达成确认",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "里程碑由您主动确认与撤回，AI 仅在回顾中提供达成参考建议，不自动勾选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))

            spec.milestones.forEach { milestone ->
                val confirmation = confirmationMap[milestone.id]
                GoalMilestoneItemView(
                    milestone = milestone,
                    confirmation = confirmation,
                    onConfirmClick = { showConfirmDialogForMilestone = milestone },
                    onWithdrawClick = {
                        val hash = GoalCodec.criterionHash(milestone.criterion.trim())
                        actions.onConfirmMilestone(
                            spec.id,
                            milestone.id,
                            hash,
                            false,
                            "",
                            emptyList(),
                        )
                    },
                    onNavigateToSource = actions.onNavigateToSource,
                )
            }
        }

        // Review Window Selector
        GoalWindowSelector(
            windows = state.availablePeriods,
            selectedWindow = state.selectedPeriodKey,
            reviewStatuses = state.reviewStatuses,
            onSelectWindow = actions.onSelectPeriod,
            onLoadPeriods = actions.onLoadPeriods,
            onPreviewPeriod = actions.onPreviewPeriod,
        )

        // Run State Card
        GoalRunStateCard(
            run = state.currentPeriodRun,
            known = state.currentPeriodKnown,
            reviewStatus = state.currentPeriodStatus,
            canRetryLocalSave = state.canRetryLocalSave,
            onRequestAnalysis = { force ->
                if (state.selectedPeriodKey != null) {
                    actions.onRequestAnalysis(state.selectedPeriodKey, force)
                }
            },
            onCancelAnalysis = {
                if (state.selectedPeriodKey != null) {
                    actions.onCancelAnalysis(state.selectedPeriodKey)
                }
            },
            onRetryLocalSave = {
                if (state.selectedPeriodKey != null) {
                    actions.onRetryLocalSave(state.selectedPeriodKey)
                }
            },
            onTakeoverUnknown = {
                if (state.selectedPeriodKey != null) {
                    actions.onTakeoverUnknown(state.selectedPeriodKey)
                }
            },
        )

        // Review Content or Empty Notice
        if (currentReview != null) {
            GoalReviewContent(
                review = currentReview,
                statusLabel = state.currentPeriodStatus,
                onOpenSuggestion = { sug ->
                    val matched = spec.milestones.firstOrNull { it.id == sug.milestoneId }
                    if (matched != null) {
                        showConfirmDialogForMilestone = matched
                    }
                },
                onNavigateToSource = actions.onNavigateToSource,
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "所选周期尚未生成 AI 回顾",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "周期结束后，AI 可基于此期间的正式记录与日报分析推进情况并提供正反馈。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        // Permission Card (Desktop: allowAutomatic = false)
        GoalPermissionCard(
            goal = spec,
            permission = state.permission,
            localEndpoint = state.localAiEndpoint,
            localModel = state.localAiModel,
            localAiConfigured = state.localAiConfigured,
            allowAutomatic = false,
            onGrant = { specHash, endpoint, model, sendAllowed, automaticAllowed ->
                actions.onGrantPermission(
                    spec.id,
                    specHash,
                    endpoint,
                    model,
                    sendAllowed,
                    automaticAllowed,
                )
            },
            onNavigateToAiSettings = actions.onNavigateToAiSettings,
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showConfirmDialogForMilestone != null) {
        val target = showConfirmDialogForMilestone!!
        val existingConf = confirmationMap[target.id]
        MilestoneConfirmDialog(
            goalId = spec.id,
            milestone = target,
            existingConfirmation = existingConf,
            availableEvidence = state.currentPeriodEvidence,
            onConfirm = { goalId, milestoneId, hash, confirmed, note, evidence ->
                actions.onConfirmMilestone(goalId, milestoneId, hash, confirmed, note, evidence)
                showConfirmDialogForMilestone = null
            },
            onDismiss = { showConfirmDialogForMilestone = null },
        )
    }
}

@Composable
private fun DesktopGoalEditorView(
    editor: GoalEditorState,
    actions: GoalActions,
) {
    val isNew = editor.session.initial == null
    var validationError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isNew) "新建目标" else "编辑目标",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onCloseEditor) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val title = editor.title.text.trim()
                        val desiredResult = editor.desiredResult.text.trim()
                        val startDate = editor.startDate.text.trim()
                        val endDate = editor.endDate.text.trim()
                        val reviewZone = editor.reviewZone.text.trim()
                        val cadenceDays = editor.cadenceDays.text.trim().toIntOrNull()

                        if (title.isEmpty()) {
                            validationError = "目标标题不能为空"
                            return@Button
                        }
                        if (desiredResult.isEmpty()) {
                            validationError = "期望成果不能为空"
                            return@Button
                        }
                        val startParsed = runCatching { LocalDate.parse(startDate) }.getOrNull()
                        if (startParsed == null) {
                            validationError = "起始日期格式无效 (YYYY-MM-DD)"
                            return@Button
                        }
                        val endParsed = runCatching { LocalDate.parse(endDate) }.getOrNull()
                        if (endParsed == null) {
                            validationError = "截止日期格式无效 (YYYY-MM-DD)"
                            return@Button
                        }
                        if (startParsed.isAfter(endParsed)) {
                            validationError = "起始日期不能晚于截止日期"
                            return@Button
                        }
                        val zoneParsed = runCatching { ZoneId.of(reviewZone) }.getOrNull()
                        if (zoneParsed == null) {
                            validationError = "时区标识无效 (如 Asia/Shanghai)"
                            return@Button
                        }
                        val totalDays = ChronoUnit.DAYS.between(startParsed, endParsed) + 1
                        if (cadenceDays == null || cadenceDays <= 0 || cadenceDays > totalDays) {
                            validationError = "周期日数必须为 1 至 $totalDays 之间的正整数"
                            return@Button
                        }

                        val milestones = editor.milestones.map {
                            GoalMilestone(it.id, it.criterion.text.trim())
                        }
                        if (milestones.isEmpty()) {
                            validationError = "请至少添加一项完成标准"
                            return@Button
                        }
                        if (milestones.any { it.criterion.isEmpty() }) {
                            validationError = "完成标准内容不能为空"
                            return@Button
                        }

                        validationError = null
                        val spec = GoalSpec(
                            id = editor.session.goalId,
                            title = title,
                            desiredResult = desiredResult,
                            startDate = startDate,
                            endDate = endDate,
                            reviewZone = reviewZone,
                            cadenceDays = cadenceDays,
                            milestones = milestones,
                            archived = editor.session.initial?.archived ?: false,
                        )
                        actions.onSaveGoal(editor.session, spec)
                    },
                ) {
                    Text("保存")
                }
            }
        }

        if (editor.remoteChanged) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "注意：远端已更新此目标，保存将基于您当前所见版本进行分支合并。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        if (validationError != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = validationError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        // Title
        OutlinedTextField(
            value = editor.title,
            onValueChange = {
                actions.onUpdateEditor(editor.copy(title = it, dirty = true))
            },
            label = { Text("目标标题 *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // Desired Result
        OutlinedTextField(
            value = editor.desiredResult,
            onValueChange = {
                actions.onUpdateEditor(editor.copy(desiredResult = it, dirty = true))
            },
            label = { Text("期望成果（清晰可验证的结果）*") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
        )

        // Date Range
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = editor.startDate,
                onValueChange = {
                    actions.onUpdateEditor(editor.copy(startDate = it, dirty = true))
                },
                label = { Text("起始日期 *") },
                placeholder = { Text("YYYY-MM-DD") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = editor.endDate,
                onValueChange = {
                    actions.onUpdateEditor(editor.copy(endDate = it, dirty = true))
                },
                label = { Text("截止日期 *") },
                placeholder = { Text("YYYY-MM-DD") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }

        // Review Zone & Cadence Days
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = editor.reviewZone,
                onValueChange = {
                    actions.onUpdateEditor(editor.copy(reviewZone = it, dirty = true))
                },
                label = { Text("回顾时区 *") },
                placeholder = { Text("如 Asia/Shanghai") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = editor.cadenceDays,
                onValueChange = {
                    actions.onUpdateEditor(editor.copy(cadenceDays = it, dirty = true))
                },
                label = { Text("周期日数 *") },
                placeholder = { Text("如 7") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }

        HorizontalDivider()

        // Milestones
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(
                    text = "完成标准（里程碑）*",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "按标准项等权计数。修改已有标准将分配新ID，历史确认不再计入当前进度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            editor.milestones.forEachIndexed { index, milestoneField ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = milestoneField.criterion,
                        onValueChange = { newCriterion ->
                            val updated = editor.milestones.toMutableList()
                            updated[index] = milestoneField.copy(criterion = newCriterion)
                            actions.onUpdateEditor(editor.copy(milestones = updated, dirty = true))
                        },
                        label = { Text("标准 ${index + 1}") },
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 3,
                    )
                    if (editor.milestones.size > 1) {
                        TextButton(
                            onClick = {
                                val updated = editor.milestones.toMutableList().apply { removeAt(index) }
                                actions.onUpdateEditor(editor.copy(milestones = updated, dirty = true))
                            },
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    val newField = GoalMilestoneField(
                        id = UUID.randomUUID().toString(),
                        criterion = TextFieldValue(),
                    )
                    actions.onUpdateEditor(
                        editor.copy(
                            milestones = editor.milestones + newField,
                            dirty = true,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("＋ 添加完成标准")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
