package app.liferecord

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalScreen(
    state: GoalUiState,
    actions: GoalActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.editor != null -> {
                GoalEditorContent(
                    editor = state.editor,
                    actions = actions,
                )
            }
            state.selectedGoalId != null && state.detail != null -> {
                GoalDetailContent(
                    state = state,
                    actions = actions,
                )
            }
            else -> {
                GoalListContent(
                    state = state,
                    actions = actions,
                )
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalListContent(
    state: GoalUiState,
    actions: GoalActions,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "目标与周期回顾",
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    IconButton(onClick = actions.onRefresh) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = actions.onOpenCreate) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "新建目标")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = actions.onOpenCreate,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "新建目标")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Archived filter tab
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !state.includeArchived,
                    onClick = { actions.onToggleIncludeArchived(false) },
                    label = { Text("进行中") },
                )
                FilterChip(
                    selected = state.includeArchived,
                    onClick = { actions.onToggleIncludeArchived(true) },
                    label = { Text("包含已归档") },
                )
            }

            if (state.isLoading && state.goals.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.goals.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "暂无目标",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "设定清晰的目标与里程碑，AI 将基于日常记录进行周期分析与反馈。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = actions.onOpenCreate) {
                            Text("新建第一个目标")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.goals, key = { it.spec?.id ?: it.hashCode().toString() }) { summary ->
                        val spec = summary.spec
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (spec != null) actions.onSelectGoal(spec.id)
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = spec?.title ?: "未命名目标",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (spec?.archived == true) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Text(
                                                text = "已归档",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }

                                if (!spec?.desiredResult.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = spec!!.desiredResult,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${spec?.startDate ?: ""} 至 ${spec?.endDate ?: ""} (每 ${spec?.cadenceDays ?: 7} 天)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    if (summary.conflicted) {
                                        Text(
                                            text = "进度待核对",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    } else {
                                        Text(
                                            text = "${summary.completed}/${summary.total} 项 (${summary.percent ?: 0}%)",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                val progress = if (summary.total > 0) summary.completed.toFloat() / summary.total.toFloat() else 0f
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp),
                                    color = if (summary.conflicted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalDetailContent(
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = spec.title,
                        maxLines = 1,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { actions.onSelectGoal(null) }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { actions.onOpenEdit(spec.id) }) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = "编辑目标")
                    }
                    TextButton(onClick = { actions.onToggleArchive(spec.id, !spec.archived) }) {
                        Text(if (spec.archived) "取消归档" else "归档")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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

            // Target / Desired Result Card
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
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "观察期限: ${spec.startDate} 至 ${spec.endDate} · 周期: 每 ${spec.cadenceDays} 天 · 时区: ${spec.reviewZone}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // Milestones Section
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

            // Review Content or Empty notice
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

            // Permission Card (Android allows automatic analysis)
            GoalPermissionCard(
                goal = spec,
                permission = state.permission,
                localEndpoint = state.localAiEndpoint,
                localModel = state.localAiModel,
                localAiConfigured = state.localAiConfigured,
                allowAutomatic = true,
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
    }

    // Milestone confirmation dialog
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalEditorContent(
    editor: GoalEditorState,
    actions: GoalActions,
) {
    val isNew = editor.session.initial == null
    var validationError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建目标" else "编辑目标", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = actions.onCloseEditor) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
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
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
}
