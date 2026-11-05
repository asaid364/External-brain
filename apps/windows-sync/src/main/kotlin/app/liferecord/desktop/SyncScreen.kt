package app.liferecord.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp

data class NetworkInterfaceItem(
    val id: String,
    val name: String,
    val ipAddress: String,
    val isPreferred: Boolean = false
)

data class IncomingPairRequest(
    val requestId: String,
    val deviceName: String,
    val clientId: String,
    val timestamp: String
)

data class PurgeTrackerItem(
    val recordId: String,
    val purgedAt: String,
    val isPeerAcked: Boolean
)

data class DesktopSyncUiState(
    val isServerRunning: Boolean = false,
    val serverPort: Int = 8443,
    val serverHostName: String = "",
    val selectedInterfaceId: String = "",
    val availableInterfaces: List<NetworkInterfaceItem> = emptyList(),
    val certificateFingerprintSha256: String = "",
    val isMdnsActive: Boolean = false,
    val isPairingActive: Boolean = false,
    val pairingCode: String = "", // 26位 Base32
    val pairingSecondsRemaining: Int = 0,
    val pairingAttemptsRemaining: Int = 5,
    val incomingRequest: IncomingPairRequest? = null,
    val showMergeConsentDialog: Boolean = false,
    val localCounts: app.liferecord.core.SyncCategoryCounts? = null,
    val needsScopeConsent: Boolean = false,
    // 已配对状态
    val isPaired: Boolean = false,
    val pairedDeviceName: String = "",
    val pairedPeerId: String = "",
    val spaceId: String = "",
    val lastSyncTimestamp: String = "",
    val localCommitSeq: Long = 0,
    val remoteAckedSeq: Long = 0,
    val remoteCommitSeq: Long = 0,
    val localReceivedSeq: Long = 0,
    val isSyncing: Boolean = false,
    val syncProgressMessage: String = "",
    val syncProgressFraction: Float? = null,
    val unresolvedConflictCount: Int = 0,
    val purgeTrackers: List<PurgeTrackerItem> = emptyList(),
    val errorMessage: String? = null
)

/**
 * Windows 桌面端数据与同步主面板
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    state: DesktopSyncUiState,
    onSelectInterface: (String) -> Unit,
    onStartPairing: () -> Unit,
    onCancelPairing: () -> Unit,
    onAcceptPairRequest: (requestId: String) -> Unit,
    onRejectPairRequest: (requestId: String) -> Unit,
    onConfirmMergeConsent: () -> Unit,
    onDismissMergeConsent: () -> Unit,
    onTriggerSync: () -> Unit,
    onNavigateConflicts: () -> Unit,
    onUnpair: () -> Unit
) {
    var showUnpairConfirm by remember { mutableStateOf(false) }
    var interfaceDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("数据与局域网双向同步", style = MaterialTheme.typography.titleLarge)

        state.errorMessage?.let { err ->
            DesktopInfoPanel("同步服务异常", err, isError = true)
        }

        // 1. 本机局域网服务与接口配置卡片
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("局域网监听服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DesktopStatusBadge(
                            text = if (state.isServerRunning) "服务正在运行" else "服务未启动",
                            isSuccess = state.isServerRunning,
                            isError = !state.isServerRunning
                        )
                        DesktopStatusBadge(
                            text = if (state.isMdnsActive) "mDNS 广播中" else "mDNS 离线",
                            isSuccess = state.isMdnsActive
                        )
                    }
                }

                // 物理网卡下拉选择 (严禁 0.0.0.0)
                ExposedDropdownMenuBox(
                    expanded = interfaceDropdownExpanded,
                    onExpandedChange = { interfaceDropdownExpanded = it }
                ) {
                    val currentSelection = state.availableInterfaces.find { it.id == state.selectedInterfaceId }
                    OutlinedTextField(
                        value = currentSelection?.let { "${it.name} (${it.ipAddress})" } ?: "未选择网卡",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("绑定物理私网接口 (禁止绑定 0.0.0.0)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = interfaceDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = interfaceDropdownExpanded,
                        onDismissRequest = { interfaceDropdownExpanded = false }
                    ) {
                        state.availableInterfaces.forEach { item ->
                            DropdownMenuItem(
                                text = { Text("${item.name} - ${item.ipAddress}${if (item.isPreferred) " [推荐]" else ""}") },
                                onClick = {
                                    onSelectInterface(item.id)
                                    interfaceDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // TLS 证书 SHA-256 指纹展示
                // TLS 证书 SHA-256 指纹展示与稳定主机名
                if (state.certificateFingerprintSha256.isNotBlank() || state.serverHostName.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.serverHostName.isNotBlank()) {
                            Column {
                                Text(
                                    text = "稳定主机名 (手动连接配对标识):",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                SelectionContainer {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Text(
                                            text = state.serverHostName,
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "若 Android 无法通过 mDNS 自动发现，可复制此稳定主机名填入手机的手动连接弹窗。",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (state.certificateFingerprintSha256.isNotBlank()) {
                            Column {
                                Text(
                                    text = "本端 HTTPS 证书指纹 (SHA-256):",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                SelectionContainer {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Text(
                                            text = state.certificateFingerprintSha256,
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "请在 Android 手机配对时核对上述指纹，指纹一致才放行连接，防止中间人监听。",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. 配对管理卡片 (Pairing Section)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("配对与授权管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (!state.isPairingActive) {
                        Button(onClick = onStartPairing, enabled = state.isServerRunning) {
                            Text("开启配对广播 (5分钟有效)")
                        }
                    } else {
                        OutlinedButton(onClick = onCancelPairing) {
                            Text("取消配对")
                        }
                    }
                }

                if (state.isPairingActive) {
                    DesktopInfoPanel(
                        title = "配对广播中 (有效时间剩余 ${state.pairingSecondsRemaining / 60}分${state.pairingSecondsRemaining % 60}秒 · 剩余尝试次数: ${state.pairingAttemptsRemaining}次)",
                        body = "请在手机端 LifeRecord“数据与同步”中搜索并输入以下 26 位一次性 Base32 配对码：",
                        isWarning = true
                    )

                    SelectionContainer {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        ) {
                            Text(
                                text = state.pairingCode,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }

                    // 收到手机配对请求弹窗 / 提示
                    state.incomingRequest?.let { req ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("收到来自移动端的配对请求", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("设备名称: ${req.deviceName} · 客户端标识: ${req.clientId}", style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onAcceptPairRequest(req.requestId) }) {
                                        Text("同意建立配对并分发令牌")
                                    }
                                    OutlinedButton(onClick = { onRejectPairRequest(req.requestId) }) {
                                        Text("拒绝")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. 已配对设备与同步状态卡片
        if (state.isPaired) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("当前配对设备与同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.unresolvedConflictCount > 0) {
                                Button(
                                    onClick = onNavigateConflicts,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("冲突待处理 (${state.unresolvedConflictCount})")
                                }
                            }
                            Button(onClick = onTriggerSync, enabled = !state.isSyncing) {
                                Text(if (state.needsScopeConsent) "确认新增同步范围" else "立即双向同步")
                            }
                        }
                    }

                    if (state.isSyncing) {
                        state.syncProgressFraction?.let {
                            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                        } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(state.syncProgressMessage, style = MaterialTheme.typography.bodyMedium)
                    }

                    HorizontalDivider()

                    DesktopTimeLabel("配对设备", state.pairedDeviceName)
                    DesktopTimeLabel("空间 ID", state.spaceId)
                    DesktopTimeLabel("最后同步", state.lastSyncTimestamp)
                    DesktopTimeLabel("本地提交 / 对端ACK", "${state.localCommitSeq} / ${state.remoteAckedSeq}")
                    DesktopTimeLabel("对端提交 / 本地接收", "${state.remoteCommitSeq} / ${state.localReceivedSeq}")
                    Text(state.localCounts?.let {
                        "本机：记录 ${it.records} · 草稿 ${it.drafts} · 日报 ${it.reports} · 目标 ${it.goals} · 周期回顾 ${it.reviews}"
                    } ?: "双方同意新增同步范围后显示数量；未披露不代表 0", style = MaterialTheme.typography.bodySmall)

                    // 彻底删除同步状态追踪列表
                    if (state.purgeTrackers.isNotEmpty()) {
                        HorizontalDivider()
                        Text("彻底删除双向同步追踪", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        LazyColumn(modifier = Modifier.height(120.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(state.purgeTrackers, key = { it.recordId }) { p ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("记录 ID: ${p.recordId.take(8)}... (抹除于 ${p.purgedAt})", style = MaterialTheme.typography.bodyMedium)
                                    DesktopStatusBadge(
                                        text = if (p.isPeerAcked) "手机端已确认抹除 (ACK)" else "等待手机端连接确认",
                                        isSuccess = p.isPeerAcked,
                                        isWarning = !p.isPeerAcked
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { showUnpairConfirm = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("解除与此设备的配对")
                        }
                    }
                }
            }
        }
    }

    // 解绑二次确认
    if (showUnpairConfirm) {
        DesktopConfirmDialog(
            title = "解除与此设备的配对？",
            body = "解除配对后将注销授权令牌并停止双向同步。两端已同步的事实数据与历史不会被清空或删除。",
            confirmLabel = "确认解除配对",
            onConfirm = {
                showUnpairConfirm = false
                onUnpair()
            },
            onDismiss = { showUnpairConfirm = false },
            danger = true
        )
    }

    // 鉴权后数据全量合并告知弹窗 (Post-Auth Merge Consent Dialog)
    if (state.showMergeConsentDialog) {
        DesktopConfirmDialog(
            title = "确认新增同步范围",
            body = """
                配对身份保持不变，无需重新配对。双方分别同意发送和接收后恢复同步并显示数量。
                同步类别：正式记录、共享草稿、日报与答案、目标与里程碑完成声明、周期分析回顾。

                两端独立 ID 取并集；并发修改保留冲突候选供人工解决，不按时间覆盖。
                删除与彻底删除双向镜像传播；历史周期分析可能保留衍生结论与引用标识。
                AI Key、服务配置、目标外发许可、自动分析开关和本机任务不同步，需在各端独立授权。
            """.trimIndent(),
            confirmLabel = "同意新增同步范围",
            onConfirm = onConfirmMergeConsent,
            onDismiss = onDismissMergeConsent
        )
    }
}
