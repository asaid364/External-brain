package app.liferecord.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.JOptionPane
import kotlinx.coroutines.launch

/** No startup/service registration. Construction happens only on an explicit application launch. */
fun main(args: Array<String>) {
    val directory = try { DesktopDataDirectory.open(args) }
    catch (failure: Exception) { JOptionPane.showMessageDialog(null, failure.message ?: "无法打开数据目录", "生活记录启动失败", JOptionPane.ERROR_MESSAGE); return }
    val runtime = try { DesktopRuntime(directory.path) }
    catch (failure: Exception) { directory.close(); JOptionPane.showMessageDialog(null, failure.message ?: "无法打开数据库，原文件保留", "生活记录启动失败", JOptionPane.ERROR_MESSAGE); return }
    application {
        var visible by remember { mutableStateOf(true) }
        var page by remember { mutableStateOf("记录") }
        var showAi by remember { mutableStateOf(false) }
        var exiting by remember { mutableStateOf(false) }
        var tray by remember { mutableStateOf<TrayIcon?>(null) }
        val scope = rememberCoroutineScope()
        val records by runtime.records.collectAsState()
        val reports by runtime.reportState.collectAsState()
        val sync by runtime.syncState.collectAsState()
        val ai by runtime.aiState.collectAsState()
        val goals by runtime.goalState.collectAsState()
        fun quit() {
            if (exiting) return
            exiting = true
            scope.launch {
                try {
                    runtime.close()
                    tray?.let { SystemTray.getSystemTray().remove(it) }
                    directory.close()
                    exitApplication()
                } catch (failure: Exception) {
                    exiting = false
                    visible = true
                    JOptionPane.showMessageDialog(null, failure.message ?: "退出前保存失败，窗口保留；请重试", "未退出", JOptionPane.ERROR_MESSAGE)
                }
            }
        }
        LaunchedEffect(runtime) { runtime.start() }
        DisposableEffect(runtime) {
            val installed = if (SystemTray.isSupported()) runCatching {
                val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
                image.createGraphics().useGraphics {
                    color = java.awt.Color(0x26, 0x4e, 0x3f)
                    fillRoundRect(1, 1, 30, 30, 8, 8)
                    color = java.awt.Color.WHITE
                    drawLine(9, 10, 23, 10); drawLine(9, 16, 23, 16); drawLine(9, 22, 19, 22)
                }
                val popup = PopupMenu()
                val icon = TrayIcon(image, "生活记录 · 局域网同步", popup).apply { isImageAutoSize = true }
                popup.add(MenuItem("打开").apply { addActionListener { visible = true } })
                popup.add(MenuItem("暂停同步").apply { addActionListener { runtime.pauseSync() } })
                popup.add(MenuItem("恢复所选接口").apply { addActionListener { runtime.syncState.value.selectedInterfaceId.takeIf { it.isNotBlank() }?.let(runtime::selectInterface) } })
                popup.addSeparator()
                popup.add(MenuItem("退出（停止同步）").apply { addActionListener { quit() } })
                icon.addActionListener { visible = true }
                SystemTray.getSystemTray().add(icon)
                icon
            }.getOrNull() else null
            tray = installed
            onDispose { installed?.let { SystemTray.getSystemTray().remove(it) } }
        }
        Window(
            onCloseRequest = { if (tray != null) visible = false else quit() },
            visible = visible,
            title = if ("--smoke" in args) "生活记录 · 隔离合成同步测试" else "生活记录",
            state = rememberWindowState(width = 1240.dp, height = 820.dp),
        ) {
            LifeRecordDesktopTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("记录", "日报", "目标", "数据与同步").forEach { target ->
                                TextButton(onClick = {
                                    runtime.requestGoalNavigation {
                                        if (page == "数据与同步" && target != page) runtime.leaveSync()
                                        page = target
                                        showAi = false
                                        if (target == "数据与同步") runtime.openSync()
                                    }
                                }) { Text(target) }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { runtime.requestGoalNavigation { if (page == "数据与同步") runtime.leaveSync(); showAi = true } }) { Text("本机 AI 设置") }
                        }
                        if (tray == null) {
                            Text("系统托盘不可用：关闭窗口将停止同步。默认不开机自启。", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                        Box(Modifier.weight(1f)) {
                            when {
                                showAi -> AiSettingsScreen(ai, onSaveConfig = { runtime.saveAi(it) }, onTestConfig = { runtime.testAi(it) }, onClose = { showAi = false })
                                page == "记录" -> RecordsScreen(
                                    records,
                                    onSelectRecord = { runtime.selectRecord(it) }, onNewRecord = { runtime.newRecord() },
                                    onContinueDraft = { runtime.continueDraft(it) }, onDiscardNewDraft = { runtime.discardNewDraft() },
                                    onStartEdit = { runtime.editRecord(it) }, onBufferChange = runtime::changeBuffer,
                                    onSaveBuffer = { runtime.saveBuffer(it) }, onDiscardEditingDraft = { runtime.discardEditingDraft() },
                                    onCancelEdit = { runtime.cancelEdit() }, onApplyRemoteDraft = { runtime.applyRemoteDraft(it) },
                                    onLocateConflict = { page = "数据与同步"; runtime.openSync(); runtime.conflicts(it) },
                                    onDeleteRecord = { runtime.deleteRecord(it) }, onRestoreRecord = { runtime.restoreRecord(it) }, onPurgeRecord = { runtime.purgeRecord(it) },
                                    onFilterChange = runtime::filter, onSearchChange = runtime::search,
                                )
                                page == "日报" -> ReportsScreen(
                                    reports, onDateChange = { runtime.selectDate(it) }, onGenerate = { runtime.generate(it) }, onCancel = { runtime.cancelGeneration() },
                                    onRetryLocalSave = { runtime.retryLocalSave() }, onAnswer = { id, text, heads, intent -> runtime.answer(id, text, heads, intent) }, onSkip = { id, skipped, heads, intent -> runtime.skip(id, skipped, heads, intent) },
                                    onSelectSource = { page = "记录"; runtime.selectRecord(it) },
                                )
                                page == "目标" -> GoalsScreen(goals, runtime.goalActions(
                                    onNavigateToAiSettings = { showAi = true },
                                    onNavigateToSource = { ref -> ref.recordIds.firstOrNull()?.let { page = "记录"; runtime.selectRecord(it) } },
                                    onResolveConflict = { _, _ -> page = "数据与同步"; runtime.openSync(); runtime.conflicts() },
                                ))
                                else -> SyncScreen(
                                    sync, onSelectInterface = { runtime.selectInterface(it) }, onStartPairing = { runtime.startPairing() }, onCancelPairing = { runtime.cancelPairing() },
                                    onAcceptPairRequest = { runtime.acceptPair(it) }, onRejectPairRequest = { runtime.rejectPair(it) }, onConfirmMergeConsent = { runtime.confirmMerge() },
                                    onDismissMergeConsent = { runtime.dismissMerge() }, onTriggerSync = { runtime.triggerSync() }, onNavigateConflicts = { runtime.conflicts() }, onUnpair = { runtime.unpair() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private inline fun java.awt.Graphics2D.useGraphics(block: java.awt.Graphics2D.() -> Unit) {
    try { block() } finally { dispose() }
}
