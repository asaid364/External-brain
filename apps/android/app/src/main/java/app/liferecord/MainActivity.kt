package app.liferecord

import app.liferecord.core.*

import android.os.Bundle
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.ZoneId
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LifeRecordTheme { val sync: SyncViewModel = viewModel(); val goals: GoalViewModel = viewModel(); RecordApp(sync = sync, goals = goals) } }
    }
    override fun onResume() {
        super.onResume()
        ReportSchedule.schedule(this)
        lifecycleScope.launch {
            val runtime = AppRuntime.get(this@MainActivity)
            try {
                runtime.ready.await()
                runtime.sync.foreground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                runtime.rearmGoalReviews()
            }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* ViewModels expose initialization failure without replacing local data. */ }
        }
    }
    override fun onPause() {
        AppRuntime.get(this).sync.foreground(false)
        super.onPause()
    }
}

@Composable
fun RecordApp(vm: RecordViewModel = viewModel(), reports: ReportViewModel = viewModel(), sync: SyncViewModel? = null, goals: GoalViewModel? = null) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var reportDirty by remember { mutableStateOf(false) }
    var reportLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val reportHolder = rememberSaveableStateHolder()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    if (sync?.conflictsOpen == true) {
        ConflictScreen(
            state = sync.conflictState,
            onSelectTab = sync::selectConflictTab,
            onResolveRecord = sync::chooseRecord,
            onIntegrateRecord = sync::integrateRecord,
            onResolveDraft = sync::chooseDraft,
            onDiscardDraftHead = sync::discardDraftCandidate,
            onForkDraft = sync::forkDraftCandidate,
            onIntegrateDraft = sync::integrateDraft,
            onResolveReport = sync::chooseReport,
            onResolveAnswer = sync::chooseAnswer,
            onIntegrateAnswer = sync::integrateAnswer,
            onBack = sync::closeConflicts,
            onResolveGoalEntity = sync::chooseGoalEntity,
            onIntegrateGoalEntity = sync::integrateGoalEntity,
        )
        return
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reports.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun reportNavigate(action: () -> Unit) {
        if (vm.section == 2 && goals != null) goals.requestGoalNavigation(action)
        else if (vm.section == 1 && reportDirty && !settingsOpen) reportLeave = action
        else action()
    }
    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        AiSettingsScreen(reports.state.configuration, reports.state.testing, reports.state.message, reports::saveConfiguration, reports::testConfiguration) { settingsOpen = false }
        return
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val listState = rememberLazyListState()
    var confirm by remember { mutableStateOf<String?>(null) }
    val snapshot = vm.data
    LaunchedEffect(vm.page, vm.locateId, snapshot) {
        val target = vm.locateId
        if (vm.page == "list" && vm.section == 0 && target != null && snapshot != null) {
            val active = snapshot.records.filter { it.deleted == null }
            var position = 1
            var day = ""
            for (row in active) {
                if (row.time.local.take(10) != day) { day = row.time.local.take(10); position++ }
                if (row.id == target) { listState.scrollToItem(position); break }
                position++
            }
            vm.located()
        }
    }
    val record = snapshot?.records?.find { it.id == vm.selectedId }
    val editor = vm.editor
    val title = when {
        vm.section == 1 -> "日报"
        vm.section == 2 -> "目标"
        vm.section == 3 -> "数据与同步"
        vm.page == "edit" -> if (editor?.recordId == null) "新记录" else "编辑记录"
        vm.page == "detail" -> if (record?.deleted != null) "已删除记录" else "记录详情"
        vm.page == "trash" -> "最近删除"
        else -> "记录"
    }
    BackHandler(enabled = (vm.page != "list" || vm.section != 0) && !imeOpen) { reportNavigate { if (vm.section != 0) vm.navigate(0) else vm.navigate() } }
    AppFrame(title, vm.section, onSection = { target -> reportNavigate { keyboard?.hide(); vm.navigate(target); if (target == 1) reports.refresh() } },
        onBack = if (vm.page != "list") ({ keyboard?.hide(); vm.navigate() }) else null,
        actions = {
            if (vm.section == 0 && vm.page == "edit") TextButton(enabled = !vm.busy && !vm.loadError && !editor?.text.isNullOrBlank(), onClick = { keyboard?.hide(); vm.save() }, modifier = Modifier.testTag("save")) { Text(if (vm.busy) "处理中…" else "保存") }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.message.isNotBlank()) Text(vm.message, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("feedback"), color = MaterialTheme.colorScheme.primary)
            if (vm.section == 0 && vm.page == "edit" && vm.remoteEditWarning) {
                InfoPanel("已收到另一端变化", "当前输入、选区和保存基线未被覆盖。可以继续编辑形成候选，或核对后重新载入共享草稿。")
                Row {
                    TextButton(onClick = { confirm = "remote" }) { Text("重新载入共享草稿") }
                    if (sync != null) TextButton(onClick = sync::openConflicts) { Text("核对冲突") }
                }
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.loadError || snapshot == null) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoPanel(if (vm.loadError) "读取失败" else "正在读取本机记录", "读取异常不会清空或替换原内容。未确认读取成功前不能修改数据。")
                    if (vm.loadError) Button(onClick = vm::reload, enabled = !vm.busy) { Text("重试读取") }
                }
            } else if (vm.section == 1) {
                reportHolder.SaveableStateProvider("daily-report") {
                    ReportScreen(reports.state, reports::select, reports::generate, reports::cancel, reports::saveAnswer, reports::skip,
                        onSettings = { settingsOpen = true },
                        onSource = { id -> snapshot.records.find { it.id == id && it.deleted == null }?.let { source -> vm.navigate(0); vm.open(source) } },
                        onRefresh = reports::refresh, onDirtyChange = { reportDirty = it })
                }
            } else if (vm.section == 2 && goals != null) {
                GoalScreen(goals.state, goals.goalActions(
                    onNavigateToAiSettings = { settingsOpen = true },
                    onNavigateToSource = { ref ->
                        val id = ref.recordIds.firstOrNull()
                        snapshot.records.firstOrNull { it.id == id && it.deleted == null }?.let { source -> vm.navigate(0); vm.open(source) }
                    },
                    onResolveConflict = { _, _ -> sync?.openConflicts() },
                ))
            } else if (vm.section == 3) {
                if (sync != null) SyncScreen(sync.state, sync::startPairing, sync::cancelPairing,
                    sync::selectPeer, sync::manualConnect, sync::confirmFingerprint, sync::submitCode,
                    sync::consent, sync::cancelPairing, sync::trigger,
                    onOpenConflicts = sync::openConflicts, onUnpair = sync::unpair)
            } else when (vm.page) {
                "edit" -> if (editor != null) EditorScreen(editor, vm.editingBuffer, vm.draftStatus, !vm.busy, vm::change, vm::changeText) { confirm = "discard" }
                "detail" -> if (record != null) DetailScreen(record, snapshot, !vm.busy, { vm.edit(record) }, { confirm = "delete" }, { vm.restore(record) }, { confirm = "purge" }) else InfoPanel("记录不可查看", "请返回列表，原内容不会被空记录替换。")
                else -> {
                    val trash = vm.page == "trash"
                    val records = snapshot.records.filter { (it.deleted != null) == trash }
                    val newDraft = snapshot.drafts.find { it.recordId == null }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("record-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            if (!trash) {
                                Button(onClick = vm::newRecord, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(if (newDraft == null) "新增记录" else "继续新增草稿") }
                                TextButton(onClick = vm::trash, enabled = !vm.busy) { Text("最近删除") }
                            } else Text("不自动清理。恢复后保留原内容、历史及编辑草稿。", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (records.isEmpty()) item { InfoPanel(if (trash) "最近删除为空" else "暂无记录", if (trash) "没有已删除的记录。" else "没有记录不代表没有行动。点击新增，留下第一段事实。") }
                        val groups = records.groupBy { it.time.local.take(10) }
                        groups.forEach { (date, values) ->
                            item(key = "group-$date") { Text(date, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                            items(values, key = { it.id }) { row ->
                                val meta = buildList {
                                    if (row.created.take(10) != row.time.local.take(10)) add("补记")
                                    if (row.modified != null) add("已修改")
                                    if (snapshot.drafts.any { it.recordId == row.id }) add("有编辑草稿")
                                }.joinToString(" · ")
                                Column {
                                    RecordCard(row.text, "${row.time.local.drop(11)} ${row.time.offset}", meta) { vm.open(row) }
                                    if (trash) Text("删除于 ${RecordTime.displayStamp(row.deleted!!)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    reports.generationConfirmation?.let { decision ->
        ConfirmDialog("核对跨端生成 · ${decision.date}", decision.message, "确认，可能再次计费", reports::confirmGeneration, reports::dismissGeneration)
    }
    if (reportLeave != null) ConfirmDialog("答复尚未保存", "返回日报点击保存答复后再离开；也可切换页面，未保存文字仅作为本机界面草稿保留，不参与AI生成。", "保留草稿并离开", { val action = reportLeave; reportLeave = null; reportDirty = false; action?.invoke() }, { reportLeave = null })
    if (vm.leaveWarning) ConfirmDialog("草稿未可靠保留", "继续编辑可重试。仍要离开可能丢失尚未写入的内容，正式记录不会改变。", "仍然离开", vm::confirmLeave, vm::cancelLeave, true)
    if (confirm != null) {
        val action = confirm
        ConfirmDialog(
            when (action) { "remote" -> "替换当前输入？"; "discard" -> "放弃未保存草稿？"; "purge" -> "彻底删除，无法恢复"; else -> "移入最近删除？" },
            when (action) {
                "remote" -> "将重新读取当前共享草稿并替换输入框及编辑基线。尚未解决的候选不会自动选用；请先在冲突中心核对。"
                "discard" -> "清除当前草稿，正式记录与修改历史保持不变。"
                "purge" -> "将清除此记录的正文、修改历史及编辑草稿。本机无法恢复。已有日报和目标分析可能仍保留此前衍生内容，并标记来源不可用；已发送给外部AI的内容无法保证撤回。"
                else -> "记录将从正常列表移除，仍可恢复。相关日报会标记来源变化，旧正文可能仍包含此内容，需手动更新。" + if (snapshot?.drafts?.any { it.recordId == record?.id } == true) "已有未保存编辑草稿，将一起保留；恢复后才能继续编辑。" else "修改历史将一并保留。"
            },
            when (action) { "remote" -> "确认重新载入"; "discard" -> "确认放弃"; "purge" -> "确认彻底删除"; else -> "确认删除" },
            onConfirm = { confirm = null; when (action) { "remote" -> vm.applyRemote(); "discard" -> vm.discard(); "purge" -> record?.let(vm::purge); else -> record?.let(vm::delete) } },
            onDismiss = { confirm = null }, danger = true
        )
    }
}

@Composable
private fun EditorScreen(draft: Draft, body: TextFieldValue, status: String, enabled: Boolean, change: (Draft) -> Unit, changeText: (TextFieldValue) -> Unit, discard: () -> Unit) {
    var zones by remember { mutableStateOf(false) }
    val offsets = remember(draft.local, draft.zone) { runCatching { RecordTime.offsets(draft) }.getOrDefault(emptyList()) }
    val context = LocalContext.current
    val format = remember { DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("draft-status"))
        OutlinedTextField(value = body, onValueChange = changeText, enabled = enabled, label = { Text("记录内容") }, placeholder = { Text("刚做了什么？也可以自由描述计划。") }, minLines = 8, modifier = Modifier.fillMaxWidth().testTag("body"))
        OutlinedTextField(value = draft.local, onValueChange = { change(draft.copy(local = it, offset = null)) }, enabled = enabled, label = { Text("发生时间") }, supportingText = { Text("年-月-日 时:分；不允许未来发生时间") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("event-time"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled, onClick = {
                val value = runCatching { RecordTime.local(draft) }.getOrElse { LocalDateTime.now(ZoneId.of(draft.zone)) }
                DatePickerDialog(context, { _, y, m, d -> change(draft.copy(local = value.withYear(y).withMonth(m + 1).withDayOfMonth(d).format(format), offset = null)) }, value.year, value.monthValue - 1, value.dayOfMonth).show()
            }) { Text("选择日期") }
            OutlinedButton(enabled = enabled, onClick = {
                val value = runCatching { RecordTime.local(draft) }.getOrElse { LocalDateTime.now(ZoneId.of(draft.zone)) }
                TimePickerDialog(context, { _, h, m -> change(draft.copy(local = value.withHour(h).withMinute(m).format(format), offset = null)) }, value.hour, value.minute, true).show()
            }) { Text("选择时间") }
        }
        OutlinedButton(onClick = { zones = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("发生时区：${draft.zone}") }
        if (offsets.size > 1) {
            Text("这个当地时间出现两次，请选择时差：")
            offsets.forEach { offset -> Row(Modifier.fillMaxWidth()) {
                RadioButton(selected = draft.offset == offset.id, enabled = enabled, onClick = { change(draft.copy(offset = offset.id)) })
                TextButton(onClick = { change(draft.copy(offset = offset.id)) }, enabled = enabled) { Text("UTC${offset.id}") }
            } }
        }
        Text("时区默认来自手机，不请求位置权限。草稿保留与正式保存不同；时间校验依赖设备时钟。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = discard, enabled = enabled) { Text("放弃草稿", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(24.dp))
    }
    if (zones) ZoneDialog(draft.zone, { change(draft.copy(zone = it, offset = null)); zones = false }, { zones = false })
}

@Composable
private fun ZoneDialog(current: String, select: (String) -> Unit, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val available = remember { ZoneId.getAvailableZoneIds().sorted().map { it to ZoneId.of(it).getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE) } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("发生地点时区") }, text = {
        Column(Modifier.heightIn(max = 420.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("搜索时区名称或 IANA ID") }, modifier = Modifier.fillMaxWidth())
            LazyColumn {
                items(available.filter { (id, name) -> id.contains(query, true) || name.contains(query) }) { (zone, name) -> TextButton(onClick = { select(zone) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth()) { Text((if (zone == current) "✓ " else "") + name); Text(zone, style = MaterialTheme.typography.labelMedium) } } }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun DetailScreen(record: Record, snapshot: Snapshot, enabled: Boolean, edit: () -> Unit, delete: () -> Unit, restore: () -> Unit, purge: () -> Unit) {
    var history by rememberSaveable(record.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (snapshot.drafts.any { it.recordId == record.id }) InfoPanel("有未保存的编辑草稿", if (record.deleted == null) "当前显示正式版本，点击编辑可继续草稿。" else "草稿随记录保留，恢复后才能继续编辑。")
        SelectionContainer { Text(record.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("record-body")) }
        HorizontalDivider()
        TimeLabel("发生时间", record.time.display())
        TimeLabel("首次录入时间", RecordTime.displayStamp(record.created))
        record.modified?.let { TimeLabel("最近修改时间", RecordTime.displayStamp(it)) }
        record.deleted?.let { TimeLabel("删除时间", RecordTime.displayStamp(it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (record.deleted == null) {
                Button(onClick = edit, enabled = enabled) { Text("编辑") }
                TextButton(onClick = delete, enabled = enabled) { Text("删除", color = MaterialTheme.colorScheme.error) }
            } else {
                Button(onClick = restore, enabled = enabled) { Text("恢复记录") }
                TextButton(onClick = purge, enabled = enabled) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
            }
        }
        val revisions = snapshot.revisions.filter { it.recordId == record.id }
        if (revisions.isNotEmpty()) {
            TextButton(onClick = { history = !history }) { Text("修改历史（${revisions.size}）${if (history) "收起" else "展开"}") }
            if (history) revisions.forEach { revision ->
                HorizontalDivider()
                Text(RecordTime.displayStamp(revision.modified), style = MaterialTheme.typography.titleSmall)
                Text("修改前", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(revision.beforeText) }
                Text(revision.beforeTime.display(), style = MaterialTheme.typography.bodySmall)
                Text("修改后", style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(revision.afterText) }
                Text(revision.afterTime.display(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
