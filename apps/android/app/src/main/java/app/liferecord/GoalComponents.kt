package app.liferecord

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.liferecord.core.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class GoalMilestoneField(
    val id: String,
    val criterion: TextFieldValue,
)

data class GoalEditorState(
    val session: GoalEditSession,
    val title: TextFieldValue = TextFieldValue(),
    val desiredResult: TextFieldValue = TextFieldValue(),
    val startDate: TextFieldValue = TextFieldValue(),
    val endDate: TextFieldValue = TextFieldValue(),
    val reviewZone: TextFieldValue = TextFieldValue(),
    val cadenceDays: TextFieldValue = TextFieldValue(),
    val milestones: List<GoalMilestoneField> = emptyList(),
    val dirty: Boolean = false,
    val remoteChanged: Boolean = false,
)

data class GoalUiState(
    val goals: List<GoalSummary> = emptyList(),
    val includeArchived: Boolean = false,
    val selectedGoalId: String? = null,
    val detail: GoalDetail? = null,
    val editor: GoalEditorState? = null,
    val permission: GoalAnalysisPermission? = null,
    val localAiEndpoint: String = "",
    val localAiModel: String = "",
    val localAiConfigured: Boolean = false,
    val availablePeriods: List<GoalReviewKey> = emptyList(),
    val selectedPeriodKey: GoalReviewKey? = null,
    val currentPeriodRun: GoalRunInfo? = null,
    val currentPeriodKnown: GoalKnownReview? = null,
    val currentPeriodStatus: String? = null,
    val reviewStatuses: Map<String, String> = emptyMap(),
    val currentPeriodEvidence: List<GoalInputEvidence> = emptyList(),
    val canRetryLocalSave: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val discardConfirmation: Boolean = false,
    val evidenceText: String? = null,
    val generationConfirmation: String? = null,
)

data class GoalActions(
    val onSelectGoal: (String?) -> Unit = {},
    val onToggleIncludeArchived: (Boolean) -> Unit = {},
    val onOpenCreate: () -> Unit = {},
    val onOpenEdit: (String) -> Unit = {},
    val onUpdateEditor: (GoalEditorState) -> Unit = {},
    val onSaveGoal: (session: GoalEditSession, edited: GoalSpec) -> Unit = { _, _ -> },
    val onCloseEditor: () -> Unit = {},
    val onConfirmDiscard: () -> Unit = {},
    val onDismissDiscard: () -> Unit = {},
    val onToggleArchive: (goalId: String, archived: Boolean) -> Unit = { _, _ -> },
    val onSelectPeriod: (GoalReviewKey) -> Unit = {},
    val onLoadPeriods: () -> Unit = {},
    val onPreviewPeriod: () -> Unit = {},
    val onConfirmMilestone: (
        goalId: String,
        milestoneId: String,
        criterionHash: String,
        confirmed: Boolean,
        note: String,
        evidence: List<GoalEvidenceRef>,
    ) -> Unit = { _, _, _, _, _, _ -> },
    val onGrantPermission: (
        goalId: String,
        specHash: String,
        endpoint: String,
        model: String,
        sendAllowed: Boolean,
        automaticAllowed: Boolean,
    ) -> Unit = { _, _, _, _, _, _ -> },
    val onRequestAnalysis: (key: GoalReviewKey, force: Boolean) -> Unit = { _, _ -> },
    val onConfirmAnalysis: () -> Unit = {},
    val onDismissAnalysis: () -> Unit = {},
    val onCancelAnalysis: (key: GoalReviewKey) -> Unit = {},
    val onRetryLocalSave: (key: GoalReviewKey) -> Unit = {},
    val onTakeoverUnknown: (key: GoalReviewKey) -> Unit = {},
    val onNavigateToAiSettings: () -> Unit = {},
    val onNavigateToSource: (GoalEvidenceRef) -> Unit = {},
    val onDismissEvidence: () -> Unit = {},
    val onResolveConflict: (kind: String, id: String) -> Unit = { _, _ -> },
    val onRefresh: () -> Unit = {},
    val onDirtyChange: (Boolean) -> Unit = {},
)

fun goalDirectionLabel(direction: GoalDirection): String = when (direction) {
    GoalDirection.ADVANCING -> "正在推进"
    GoalDirection.STALLED -> "有停滞证据"
    GoalDirection.OFF_TRACK -> "有偏离证据"
    GoalDirection.INSUFFICIENT_EVIDENCE -> "证据不足"
}

@Composable
fun GoalDirectionBadge(direction: GoalDirection, modifier: Modifier = Modifier) {
    val (bgColor, textColor) = when (direction) {
        GoalDirection.ADVANCING -> Color(0xFFD3E7DC) to Color(0xFF072017)
        GoalDirection.STALLED -> Color(0xFFFFF1C2) to Color(0xFF4A3900)
        GoalDirection.OFF_TRACK -> Color(0xFFFFDAD6) to Color(0xFF410002)
        GoalDirection.INSUFFICIENT_EVIDENCE -> Color(0xFFEBECE6) to Color(0xFF4C544F)
    }
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier,
    ) {
        Text(
            text = goalDirectionLabel(direction),
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun GoalProgressBar(
    completed: Int,
    total: Int,
    conflicted: Boolean,
    modifier: Modifier = Modifier,
) {
    val percent = if (total > 0) (completed * 100) / total else 0
    val progressFloat = if (total > 0) completed.toFloat() / total.toFloat() else 0f

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "已确认里程碑进度",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "按完成标准等权计数，不是AI测量的能力或工作量百分比",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (conflicted) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = "进度待核对",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "$percent%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "$completed / $total 项",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progressFloat },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (conflicted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
fun GoalConflictBanner(
    conflicts: List<EntityState>,
    onResolveConflict: (kind: String, id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (conflicts.isEmpty()) return
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "存在 ${conflicts.size} 处数据冲突，当前进度与回顾待核对",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            conflicts.forEach { conflict ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val kindLabel = when (conflict.kind) {
                        GoalCodec.GOAL_KIND -> "目标定义"
                        GoalCodec.CONFIRMATION_KIND -> "里程碑确认"
                        GoalCodec.REVIEW_KIND -> "周期回顾"
                        else -> conflict.kind
                    }
                    Text(
                        text = "$kindLabel (${conflict.heads.size} 候选)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    OutlinedButton(
                        onClick = { onResolveConflict(conflict.kind, conflict.key) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("去核对", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun GoalPermissionCard(
    goal: GoalSpec,
    permission: GoalAnalysisPermission?,
    localEndpoint: String,
    localModel: String,
    localAiConfigured: Boolean,
    allowAutomatic: Boolean,
    onGrant: (specHash: String, endpoint: String, model: String, sendAllowed: Boolean, automaticAllowed: Boolean) -> Unit,
    onNavigateToAiSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val specHash = remember(goal) { GoalCodec.specHash(goal) }
    val isHashValid = permission != null && permission.specHash == specHash
    val sendAllowed = isHashValid && permission.sendAllowed
    val automaticAllowed = isHashValid && permission.automaticAllowed

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "AI 分析与外发授权",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "将发送所选周期内的全部正式记录、适用答案、可用日报及目标/用户声明（非本地筛选内容）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (!localAiConfigured) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "尚未配置本机 AI 服务",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onNavigateToAiSettings) {
                            Text("前往设置")
                        }
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "服务端点: $localEndpoint",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "模型名称: $localModel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "（密钥存储于本机安全区，不在此处回显）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "允许发送此目标数据进行分析",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        if (!isHashValid && permission != null) {
                            Text(
                                text = "目标内容已更新，需重新授权",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Switch(
                        checked = sendAllowed,
                        onCheckedChange = { checked ->
                            onGrant(
                                specHash,
                                localEndpoint,
                                localModel,
                                checked,
                                if (!checked) false else automaticAllowed,
                            )
                        },
                    )
                }

                if (allowAutomatic) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "周期结束自动分析 (01:10 后)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "仅自动补最近一期。如与其他设备无法协调可能产生重复调用费用。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = automaticAllowed,
                            enabled = sendAllowed,
                            onCheckedChange = { checked ->
                                onGrant(
                                    specHash,
                                    localEndpoint,
                                    localModel,
                                    sendAllowed,
                                    checked,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GoalRunStateCard(
    run: GoalRunInfo?,
    known: GoalKnownReview?,
    reviewStatus: String?,
    canRetryLocalSave: Boolean,
    onRequestAnalysis: (force: Boolean) -> Unit,
    onCancelAnalysis: () -> Unit,
    onRetryLocalSave: () -> Unit,
    onTakeoverUnknown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                    text = "周期回顾状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (reviewStatus != null) {
                    val (statusLabel, statusColor) = when (reviewStatus) {
                        "current" -> "有效结论" to MaterialTheme.colorScheme.primary
                        "stale" -> "基于旧快照，需重新分析" to MaterialTheme.colorScheme.error
                        "unavailable" -> "来源已不可用" to MaterialTheme.colorScheme.error
                        "conflict" -> "来源待解决" to MaterialTheme.colorScheme.error
                        else -> reviewStatus to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            when {
                run?.state == GoalRunState.RUNNING || run?.state == GoalRunState.PREPARING -> {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (run.state == GoalRunState.PREPARING) "正在准备分析请求…" else "AI 分析进行中…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onCancelAnalysis) {
                            Text("取消任务")
                        }
                    }
                }
                canRetryLocalSave || run?.state == GoalRunState.RESULT_READY -> {
                    Column {
                        Text(
                            text = "AI 回顾已生成，但本地保存未完成",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row {
                            Button(onClick = onRetryLocalSave) {
                                Text("重试本地保存")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(onClick = onCancelAnalysis) {
                                Text("放弃暂存")
                            }
                        }
                    }
                }
                run?.state == GoalRunState.UNKNOWN -> {
                    Column {
                        Text(
                            text = "上次分析超时或状态未确定：请求可能已在服务端计费",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (run.message.isNotBlank()) {
                            Text(
                                text = run.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row {
                            Button(
                                onClick = onTakeoverUnknown,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) {
                                Text("确认重复费用并重新生成")
                            }
                        }
                    }
                }
                known != null && known.state == "RUNNING" -> {
                    Text(
                        text = "另一台配对设备正在分析此周期…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                known != null && known.state == "UNKNOWN" -> {
                    Column {
                        Text(
                            text = "另一台配对设备分析状态未知，可能正在执行或已计费",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onTakeoverUnknown,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("接管并确认重复费用")
                        }
                    }
                }
                run?.state == GoalRunState.FAILED -> {
                    Column {
                        Text(
                            text = "分析失败: ${run.message.ifBlank { "未说明原因" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { onRequestAnalysis(false) }) {
                            Text("重新分析")
                        }
                    }
                }
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (reviewStatus == "current") "已有当前周期回顾" else "可对此周期进行回顾分析",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            if (reviewStatus != null) {
                                OutlinedButton(onClick = { onRequestAnalysis(true) }) {
                                    Text("强制重新分析")
                                }
                            } else {
                                Button(onClick = { onRequestAnalysis(false) }) {
                                    Text("开始回顾分析")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GoalReviewContent(
    review: GoalReviewPayload,
    statusLabel: String?,
    onOpenSuggestion: (GoalMilestoneSuggestion) -> Unit,
    onNavigateToSource: (GoalEvidenceRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Status and Validity Banner
            if (statusLabel != null && statusLabel != "current") {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (statusLabel) {
                                "stale" -> "此回顾基于旧数据快照生成，部分来源已修改，需重新分析。"
                                "unavailable" -> "此回顾引用的原始记录已删除或不可用。"
                                "conflict" -> "此回顾引用的来源存在并发冲突，待核对解决。"
                                else -> "回顾状态: $statusLabel"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Direction and Assessment
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "AI 方向评价",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                GoalDirectionBadge(direction = review.direction)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = review.assessment.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            EvidenceChipsRow(
                evidenceIds = review.assessment.evidenceIds,
                dependencies = review.dependencies,
                onNavigateToSource = onNavigateToSource,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "AI判断，依据可能不完整。AI建议不自动勾选里程碑。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Observations
            if (review.observations.isNotEmpty()) {
                Text(
                    text = "观察与行动",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                review.observations.forEach { obs ->
                    ClaimItemView(claim = obs, dependencies = review.dependencies, onNavigateToSource = onNavigateToSource)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // Achievements
            Text(
                text = "能力与成果",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (review.achievements.isEmpty()) {
                Text(
                    text = "现有记录未说明成果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                review.achievements.forEach { ach ->
                    ClaimItemView(claim = ach, dependencies = review.dependencies, onNavigateToSource = onNavigateToSource)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Recurring Problems
            if (review.recurringProblems.isNotEmpty()) {
                Text(
                    text = "重复出现的阻碍",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(4.dp))
                review.recurringProblems.forEach { prob ->
                    ClaimItemView(claim = prob, dependencies = review.dependencies, onNavigateToSource = onNavigateToSource)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // Positive Feedback
            if (review.positiveFeedback.isNotEmpty()) {
                Text(
                    text = "具体正反馈",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                review.positiveFeedback.forEach { fb ->
                    ClaimItemView(claim = fb, dependencies = review.dependencies, onNavigateToSource = onNavigateToSource)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // Milestone Suggestions
            if (review.milestoneSuggestions.isNotEmpty()) {
                Text(
                    text = "里程碑达成建议",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                review.milestoneSuggestions.forEach { sug ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = sug.text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            EvidenceChipsRow(
                                evidenceIds = sug.evidenceIds,
                                dependencies = review.dependencies,
                                onNavigateToSource = onNavigateToSource,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = { onOpenSuggestion(sug) },
                                modifier = Modifier.align(Alignment.End),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text("核对证据→确认达成", fontSize = 12.sp)
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // Next Actions
            if (review.nextActions.isNotEmpty()) {
                Text(
                    text = "下一步参考建议",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                review.nextActions.forEach { action ->
                    ClaimItemView(claim = action, dependencies = review.dependencies, onNavigateToSource = onNavigateToSource)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // Unknowns
            if (review.unknowns.isNotEmpty()) {
                Text(
                    text = "信息缺口与未知项",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                review.unknowns.forEach { unk ->
                    Text(
                        text = "• $unk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "生成时间: ${review.generatedAt.take(19).replace('T', ' ')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = "模型: ${review.model}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun ClaimItemView(
    claim: GoalEvidenceClaim,
    dependencies: List<GoalEvidenceDependency>,
    onNavigateToSource: (GoalEvidenceRef) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "• ${claim.text}",
            style = MaterialTheme.typography.bodyMedium,
        )
        EvidenceChipsRow(
            evidenceIds = claim.evidenceIds,
            dependencies = dependencies,
            onNavigateToSource = onNavigateToSource,
        )
    }
}

@Composable
fun EvidenceChipsRow(
    evidenceIds: List<String>,
    dependencies: List<GoalEvidenceDependency>,
    onNavigateToSource: (GoalEvidenceRef) -> Unit,
) {
    if (evidenceIds.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val depMap = remember(dependencies) { dependencies.associateBy { it.ref.id } }
        evidenceIds.forEach { id ->
            val dep = depMap[id]
            val label = when {
                id.startsWith("r:") -> "记录"
                id.startsWith("a:") -> "答案"
                id.startsWith("d:") -> "日报"
                else -> id
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.clickable(enabled = dep != null) {
                    if (dep != null) onNavigateToSource(dep.ref)
                },
            ) {
                Text(
                    text = "证据 $label",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
@Composable
fun GoalMilestoneItemView(
    milestone: GoalMilestone,
    confirmation: GoalConfirmationSnapshot?,
    onConfirmClick: () -> Unit,
    onWithdrawClick: () -> Unit,
    onNavigateToSource: (GoalEvidenceRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConfirmed = confirmation?.value?.confirmed == true
    val criterionHash = remember(milestone.criterion) { GoalCodec.criterionHash(milestone.criterion.trim()) }
    val isHashMismatch = confirmation != null && confirmation.value.criterionHash != criterionHash

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = milestone.criterion,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (isHashMismatch) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "标准已修改（旧确认标准: \"${confirmation?.value?.criterionSnapshot}\"）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (isConfirmed && !isHashMismatch) {
                    Surface(
                        color = Color(0xFFD3E7DC),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = "已确认达成",
                            color = Color(0xFF072017),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = "未确认",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            if (isConfirmed && confirmation != null) {
                val value = confirmation.value
                if (value.note.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "用户声明: ${value.note}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (value.evidence.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        value.evidence.forEach { ref ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable { onNavigateToSource(ref) },
                            ) {
                                Text(
                                    text = "证据: ${ref.kind.name} (${ref.entityKey.take(10)})",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "确认于: ${value.confirmedAt.take(19).replace('T', ' ')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isConfirmed) {
                    OutlinedButton(
                        onClick = onWithdrawClick,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("撤回确认", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onConfirmClick,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("修改说明与证据", fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onConfirmClick,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text("确认达成", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun MilestoneConfirmDialog(
    goalId: String,
    milestone: GoalMilestone,
    existingConfirmation: GoalConfirmationSnapshot?,
    availableEvidence: List<GoalInputEvidence>,
    onConfirm: (goalId: String, milestoneId: String, criterionHash: String, confirmed: Boolean, note: String, evidence: List<GoalEvidenceRef>) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember { mutableStateOf(existingConfirmation?.value?.note ?: "") }
    val initialSelected = remember { existingConfirmation?.value?.evidence?.map { it.id }?.toSet() ?: emptySet() }
    val selectedEvidenceIds = remember { mutableStateListOf<String>().apply { addAll(initialSelected) } }
    val criterionHash = remember(milestone.criterion) { GoalCodec.criterionHash(milestone.criterion.trim()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "确认里程碑达成",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                Text(
                    text = "标准内容:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = milestone.criterion,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("完成说明（选填，标注为用户声明）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )

                if (availableEvidence.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "关联证据（可选，来自当前周期记录/答案/日报）:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    availableEvidence.forEach { item ->
                        val ref = item.dependency.ref
                        val isChecked = selectedEvidenceIds.contains(ref.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) selectedEvidenceIds.remove(ref.id)
                                    else selectedEvidenceIds.add(ref.id)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) selectedEvidenceIds.add(ref.id)
                                    else selectedEvidenceIds.remove(ref.id)
                                },
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.text.take(60),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                )
                                Text(
                                    text = "${ref.kind.name} · ${ref.entityKey}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val evidenceList = availableEvidence
                        .filter { selectedEvidenceIds.contains(it.dependency.ref.id) }
                        .map { it.dependency.ref }
                    onConfirm(
                        goalId,
                        milestone.id,
                        criterionHash,
                        true,
                        note,
                        evidenceList,
                    )
                    onDismiss()
                },
            ) {
                Text("确认达成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
fun GoalWindowSelector(
    windows: List<GoalReviewKey>,
    selectedWindow: GoalReviewKey?,
    reviewStatuses: Map<String, String>,
    onSelectWindow: (GoalReviewKey) -> Unit,
    onLoadPeriods: () -> Unit,
    onPreviewPeriod: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "周期回顾窗口",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val label = if (selectedWindow != null) {
                        "${selectedWindow.fromDate} 至 ${selectedWindow.throughDate}"
                    } else {
                        "请选择周期"
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Box {
                    OutlinedButton(onClick = {
                        expanded = true
                        onLoadPeriods()
                    }) {
                        Text("切换周期")
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        if (windows.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("暂无已结束的完整周期") },
                                 onClick = { expanded = false },
                            )
                        } else {
                            windows.forEach { key ->
                                val status = reviewStatuses[key.encoded]
                                val statusText = when (status) {
                                    "current" -> "（已回顾）"
                                    "stale" -> "（快照待更新）"
                                    "unavailable" -> "（源不可用）"
                                    "conflict" -> "（待解决）"
                                    else -> ""
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text("${key.fromDate} 至 ${key.throughDate} $statusText")
                                    },
                                    onClick = {
                                        expanded = false
                                        onSelectWindow(key)
                                        onPreviewPeriod()
                                    },
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
fun EvidenceTextDialog(
    text: String?,
    onDismiss: () -> Unit,
) {
    if (text == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("证据详情", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
fun GenerationConfirmationDialog(
    message: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("费用与分析确认", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("确认继续") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun DiscardConfirmDialog(
    show: Boolean,
    onConfirmDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismissDiscard,
        title = { Text("放弃未保存的修改？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Text("当前目标表单有未保存的修改，退出后修改将丢失。", style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            Button(
                onClick = onConfirmDiscard,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("放弃修改")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismissDiscard) {
                Text("继续编辑")
            }
        },
    )
}
