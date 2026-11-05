package app.liferecord

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

sealed interface AndroidPairingStep {
    data object Idle : AndroidPairingStep
    data object Discovering : AndroidPairingStep
    data object VerifyFingerprint : AndroidPairingStep
    data object EnterCode : AndroidPairingStep
    data object WaitingWindowsAuth : AndroidPairingStep
    data object PostAuthMergeConsent : AndroidPairingStep
}

data class DiscoveredWindowsPeer(
    val serverId: String,
    val hostName: String,
    val ipAddress: String,
    val port: Int
)

data class AndroidPurgeTrackerItem(
    val recordId: String,
    val purgedAt: String,
    val isPeerAcked: Boolean
)

data class AndroidSyncCounts(
    val records: Long = 0,
    val drafts: Long = 0,
    val reports: Long = 0,
    val goals: Long = 0,
    val reviews: Long = 0,
)

data class AndroidSyncUiState(
    val isPaired: Boolean = false,
    val peerDeviceName: String = "",
    val peerHost: String = "",
    val peerPort: Int = 8443,
    val spaceId: String = "",
    val lastSyncTime: String = "",
    val localCommitSeq: Long = 0,
    val remoteAckedSeq: Long = 0,
    val isSyncing: Boolean = false,
    val syncProgressMessage: String = "",
    val syncProgressFraction: Float? = null,
    val unresolvedConflictCount: Int = 0,
    val pinnedFingerprintSha256: String = "",
    val purgeTrackers: List<AndroidPurgeTrackerItem> = emptyList(),
    // 配对流程状态
    val isPairingActive: Boolean = false,
    val pairingStep: AndroidPairingStep = AndroidPairingStep.Idle,
    val discoveredPeers: List<DiscoveredWindowsPeer> = emptyList(),
    val pairingSecondsRemaining: Int = 0,
    val pairingAttemptsRemaining: Int = 5,
    val localCounts: AndroidSyncCounts = AndroidSyncCounts(),
    val remoteCounts: AndroidSyncCounts = AndroidSyncCounts(),
    val remoteCountsVerified: Boolean = false,
    val errorMessage: String? = null
)
/**
 * Android 移动端数据与同步主面板 (Section 2)
 */
@Composable
fun SyncScreen(
    state: AndroidSyncUiState,
    onStartPairing: () -> Unit,
    onCancelPairing: () -> Unit,
    onSelectDiscoveredPeer: (DiscoveredWindowsPeer) -> Unit,
    onManualConnect: (host: String, port: Int, serverId: String) -> Unit,
    onConfirmFingerprint: (fingerprint: String) -> Unit,
    onSubmitPairingCode: (code: String) -> Unit,
    onConfirmMergeConsent: () -> Unit,
    onDismissMergeConsent: () -> Unit,
    onTriggerSync: () -> Unit,
    onOpenConflicts: () -> Unit,
    onUnpair: () -> Unit
) {
    var showUnpairConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        state.errorMessage?.let { err ->
            InfoPanel("同步异常", err)
        }

        if (!state.isPaired) {
            // 未配对引导面板
            InfoPanel(
                title = "局域网双向同步",
                body = "LifeRecord 采用设备对等直连协议，通过 Wi-Fi 局域网在手机与电脑间双向同步记录、草稿与日报，无需云端中转。\n\n两端均可独立记录，数据将安全汇总；同一草稿两端接续，删除操作双向传播。"
            )
            Button(
                onClick = onStartPairing,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
            ) {
                Text("开始配对新电脑")
            }

            TimeLabel("数据归属", "记录、草稿、历史、最近删除、日报及成果补充答案")
            Text(
                text = "清除应用数据、卸载或设备损坏可能丢失内容；同步副本不等于独立备份；删除会双向传播。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 已配对设备与同步控制卡片
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已配对设备",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = if (state.isSyncing) "正在传输切片" else "已配置配对",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    TimeLabel("电脑名称", state.peerDeviceName)
                    TimeLabel("服务地址", "${state.peerHost}:${state.peerPort}")
                    TimeLabel("空间 ID", state.spaceId)
                    TimeLabel("最后同步", state.lastSyncTime.ifBlank { "尚未同步" })
                    TimeLabel("本地提交/对端ACK", "${state.localCommitSeq} / ${state.remoteAckedSeq}")

                    // 进度条
                    if (state.isSyncing) {
                        state.syncProgressFraction?.let {
                            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                        } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(state.syncProgressMessage, style = MaterialTheme.typography.bodyMedium)
                    }

                    // 立即同步按钮
                    Button(
                        onClick = onTriggerSync,
                        enabled = !state.isSyncing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(if (state.isSyncing) "正在同步中..." else "立即同步")
                    }
                }
            }

            // 冲突待解决提醒卡片
            if (state.unresolvedConflictCount > 0) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenConflicts),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = "冲突", tint = MaterialTheme.colorScheme.error)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "发现 ${state.unresolvedConflictCount} 项数据冲突待处理",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "两端在未联网时发生了独立修改，点击进入冲突中心人工选择采纳版本。",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text("去解决 ▶", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text(if (state.remoteCountsVerified) {
                val counts = state.remoteCounts
                "电脑：记录 ${counts.records} · 草稿 ${counts.drafts} · 日报 ${counts.reports} · 目标 ${counts.goals} · 周期回顾 ${counts.reviews}"
            } else "双方同意新增同步范围后显示数量；未披露不代表 0", style = MaterialTheme.typography.bodySmall)

            // 查看已锁定的 Windows 证书指纹 (只读核验)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("已锁定的 Windows 证书指纹 (SHA-256):", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            text = state.pinnedFingerprintSha256,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // 彻底删除双向同步状态列表
            if (state.purgeTrackers.isNotEmpty()) {
                Text("彻底删除双向同步追踪", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.purgeTrackers.forEach { p ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("记录 ${p.recordId.take(8)}... (已抹除)", style = MaterialTheme.typography.bodyMedium)
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (p.isPeerAcked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                                ) {
                                    Text(
                                        text = if (p.isPeerAcked) "电脑端已确认抹除 (ACK)" else "等待电脑端连接抹除",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 解除配对按钮
            TextButton(
                onClick = { showUnpairConfirm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("解除配对与停止同步")
            }
        }
    }

    // 解除配对确认弹窗
    if (showUnpairConfirm) {
        ConfirmDialog(
            title = "解除与此电脑的配对？",
            body = "解除后将注销授权令牌并停止局域网双向同步。手机与电脑中已同步的记录与日报不会被清空或删除。",
            confirmLabel = "解除配对",
            onConfirm = {
                showUnpairConfirm = false
                onUnpair()
            },
            onDismiss = { showUnpairConfirm = false },
            danger = true
        )
    }

    // 配对流程弹窗
    if (state.isPairingActive) {
        AndroidPairingDialog(
            step = state.pairingStep,
            discoveredPeers = state.discoveredPeers,
            secondsRemaining = state.pairingSecondsRemaining,
            attemptsRemaining = state.pairingAttemptsRemaining,
            localCounts = state.localCounts,
            remoteCounts = state.remoteCounts,
            remoteCountsVerified = state.remoteCountsVerified,
            onCancel = onCancelPairing,
            onSelectPeer = onSelectDiscoveredPeer,
            onManualConnect = onManualConnect,
            onConfirmFingerprint = onConfirmFingerprint,
            onSubmitCode = onSubmitPairingCode,
            onConfirmConsent = onConfirmMergeConsent,
            onRetryStatus = onTriggerSync
        )
    }
}

/**
 * Android 端配对引导弹窗 (多步骤)
 */
@Composable
private fun AndroidPairingDialog(
    step: AndroidPairingStep,
    discoveredPeers: List<DiscoveredWindowsPeer>,
    secondsRemaining: Int,
    attemptsRemaining: Int,
    localCounts: AndroidSyncCounts,
    remoteCounts: AndroidSyncCounts,
    remoteCountsVerified: Boolean,
    onCancel: () -> Unit,
    onSelectPeer: (DiscoveredWindowsPeer) -> Unit,
    onManualConnect: (host: String, port: Int, serverId: String) -> Unit,
    onConfirmFingerprint: (fingerprint: String) -> Unit,
    onSubmitCode: (code: String) -> Unit,
    onConfirmConsent: () -> Unit,
    onRetryStatus: () -> Unit
) {
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("8443") }
    var manualServerId by remember { mutableStateOf("") }
    var fingerprintInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }

    fun normalizeServerId(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("life-") && trimmed.endsWith(".local") ->
                trimmed.removePrefix("life-").removeSuffix(".local")
            trimmed.endsWith(".local") ->
                trimmed.removeSuffix(".local")
            else -> trimmed
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = when (step) {
                    AndroidPairingStep.Discovering, AndroidPairingStep.Idle -> "发现局域网电脑"
                    AndroidPairingStep.VerifyFingerprint -> "核对 Windows 证书指纹"
                    AndroidPairingStep.EnterCode -> "输入 26 位配对码"
                    AndroidPairingStep.WaitingWindowsAuth -> "等待电脑端确认授权"
                    AndroidPairingStep.PostAuthMergeConsent -> "双端数据合并知情同意"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    AndroidPairingStep.Discovering, AndroidPairingStep.Idle -> {
                        Text("请确保电脑端已打开 LifeRecord 并开启“数据与同步”。", style = MaterialTheme.typography.bodyMedium)
                        if (discoveredPeers.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                                Text("正在搜索局域网设备 (_liferecord-sync)...", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.height(140.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(discoveredPeers, key = { it.serverId }) { peer ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().clickable { onSelectPeer(peer) },
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(peer.hostName, fontWeight = FontWeight.SemiBold)
                                            Text("${peer.ipAddress}:${peer.port}", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                        Text("或手动指定电脑 IP、端口与服务器标识：", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = manualHost,
                                onValueChange = { manualHost = it },
                                label = { Text("IP 地址") },
                                placeholder = { Text("192.168.1.100") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = manualPort,
                                onValueChange = { manualPort = it },
                                label = { Text("端口") },
                                modifier = Modifier.width(90.dp)
                            )
                        }
                        OutlinedTextField(
                            value = manualServerId,
                            onValueChange = { manualServerId = it },
                            label = { Text("电脑主机名或服务器标识") },
                            placeholder = { Text("life-xxxxxx.local 或 xxxxxx") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Text(
                                text = "说明：安全规范严禁从裸 IP 推断主机名。手动连接仅用于路由寻址，在完成 TLS 证书指纹锁定之前，系统不会发送配对码、用户令牌或任何敏感凭据。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Button(
                            onClick = {
                                val normalizedId = normalizeServerId(manualServerId)
                                onManualConnect(manualHost.trim(), manualPort.toIntOrNull() ?: 8443, normalizedId)
                            },
                            enabled = manualHost.isNotBlank() && manualServerId.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("连接此 IP")
                        }
                    }

                    AndroidPairingStep.VerifyFingerprint -> {
                        Text(
                            text = "为防止局域网中间人监听，必须核对电脑端屏幕显示的证书 SHA-256 (DER) 指纹：",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = fingerprintInput,
                            onValueChange = { fingerprintInput = it },
                            label = { Text("输入或确认电脑指纹 (16进制)") },
                            placeholder = { Text("如 4A:9C:22:...:F2") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "指纹核对通过后，手机专用信任锁将仅接受此证书并建立 TLS 加密通道。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AndroidPairingStep.EnterCode -> {
                        Text(
                            text = "请输入电脑屏幕展示的 26 位 Base32 一次性配对码：\n(倒计时: ${secondsRemaining / 60}分${secondsRemaining % 60}秒 · 剩余尝试次数: ${attemptsRemaining}次)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { codeInput = it.uppercase() },
                            label = { Text("26位配对码") },
                            placeholder = { Text("ABCDE-FGHIJ-KLMNO-PQRST-UVWXY-Z") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    AndroidPairingStep.WaitingWindowsAuth -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text(
                            text = "配对请求已通过安全通道发送。请在电脑屏幕上点击“同意建立配对并分发令牌”。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    AndroidPairingStep.PostAuthMergeConsent -> {
                        val remoteDisplay = if (remoteCountsVerified) {
                            "• 电脑数据: 记录 ${remoteCounts.records} 条, 草稿 ${remoteCounts.drafts} 份, 日报 ${remoteCounts.reports} 份, 目标 ${remoteCounts.goals} 项, 周期回顾 ${remoteCounts.reviews} 份"
                        } else {
                            "• 电脑数据: 同意后显示数量；未披露不代表 0"
                        }
                        val consentNote = """
                            同步范围：正式记录、共享草稿、日报与答案、目标与里程碑完成声明、周期分析回顾。
                            两端独立 ID 取并集；并发修改保留候选，需人工核对，不按时间覆盖。
                            删除与彻底删除双向镜像传播；历史周期分析可能保留衍生结论和引用标识。
                            AI 密钥、目标外发许可、自动分析开关与本机任务不同步，需在各端独立授权。
                            双方分别同意发送和接收后显示数量并恢复同步，不需要重新配对。
                        """.trimIndent()
                        Text(
                            text = """
                                已核验配对身份。请阅读新增同步范围并确认：
                                $remoteDisplay
                                
                                $consentNote
                            """.trimIndent(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (!remoteCountsVerified) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onRetryStatus,
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("重新获取电脑状态统计")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                AndroidPairingStep.VerifyFingerprint -> {
                    Button(
                        onClick = { onConfirmFingerprint(fingerprintInput.trim()) },
                        enabled = fingerprintInput.isNotBlank()
                    ) {
                        Text("指纹一致，继续")
                    }
                }
                AndroidPairingStep.EnterCode -> {
                    Button(
                        onClick = { onSubmitCode(codeInput.trim()) },
                        enabled = codeInput.isNotBlank()
                    ) {
                        Text("提交配对码")
                    }
                }
                AndroidPairingStep.PostAuthMergeConsent -> {
                    Button(
                        onClick = onConfirmConsent,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("同意新增同步范围并继续")
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        }
    )
}
