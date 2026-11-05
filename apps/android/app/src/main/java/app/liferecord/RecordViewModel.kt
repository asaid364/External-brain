package app.liferecord

import app.liferecord.core.*
import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException

class RecordViewModel @JvmOverloads constructor(application: Application, store: RecordStore? = null) : AndroidViewModel(application) {
    private val runtime = if (store == null) AppRuntime.get(application) else null
    private val store = store ?: requireNotNull(runtime).store
    private val gate = Mutex()
    var data by mutableStateOf<Snapshot?>(null); private set
    var loadError by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var section by mutableIntStateOf(0); private set
    var page by mutableStateOf("list"); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var editor by mutableStateOf<Draft?>(null); private set
    var draftStatus by mutableStateOf(""); private set
    var leaveWarning by mutableStateOf(false); private set
    var session by mutableStateOf<EditSession?>(null); private set
    var editingBuffer by mutableStateOf(TextFieldValue()); private set
    var remoteEditWarning by mutableStateOf(false); private set
    private var editorDirty = false
    private var detailHeads = emptySet<String>()
    private var leaveDestination: (() -> Unit)? = null
    private var editGeneration = 0L
    private var saveIntent: String? = null
    private var discardIntent: String? = null
    var locateId by mutableStateOf<String?>(null); private set
    fun located() { locateId = null }
    private suspend fun <T> io(block: () -> T): T = gate.withLock { withContext(Dispatchers.IO) { block() } }
    init {
        reload()
        runtime?.let { owner -> viewModelScope.launch {
            try {
                owner.ready.await()
                owner.sync.changes.collect { refreshCommittedContent() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { loadError = true; message = "本机读取失败，未覆盖原内容。请重试。" }
        } }
    }

    private fun clearEditor() {
        editor = null; session = null; editingBuffer = TextFieldValue()
        editorDirty = false; remoteEditWarning = false; editGeneration++
        leaveWarning = false; leaveDestination = null
    }
    private fun useSession(opened: EditSession) {
        session = opened; editor = opened.draft; editingBuffer = TextFieldValue(opened.draft.text)
        editorDirty = false; remoteEditWarning = false; saveIntent = null; discardIntent = null
        editGeneration++
    }
    private suspend fun refreshCommittedContent() = gate.withLock {
        val opened = session
        val generation = editGeneration
        val fresh = withContext(Dispatchers.IO) {
            val snapshot = store.snapshot()
            val purged = opened?.let { store.sync.isPurged(it.targetRecordId) } == true
            val unseen = opened != null && !purged && (
                store.sync.headIds("record", opened.targetRecordId) != opened.baseRecordHeads ||
                    (store.sync.headIds("draft-slot", opened.slot) - opened.baseDraftHeads - listOfNotNull(opened.lastOwnDraftHead)).isNotEmpty())
            Triple(snapshot, purged, unseen)
        }
        data = fresh.first
        if (opened != null && session?.sessionId == opened.sessionId) {
            if (fresh.second) {
                clearEditor(); selectedId = null; page = "list"
                message = "记录已彻底删除，编辑缓冲已清除，不能再保存。"
            } else if (fresh.third) {
                remoteEditWarning = true
                if (!editorDirty && editingBuffer.composition == null && generation == editGeneration) {
                    try {
                        val latest = withContext(Dispatchers.IO) { if (opened.slot == "new") store.beginNew() else store.beginEdit(opened.targetRecordId) }
                        if (session?.sessionId == opened.sessionId && generation == editGeneration) useSession(latest)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { draftStatus = "共享草稿或正式记录已变化；当前输入保留，请核对同步冲突。" }
                }
            }
        }
        if (selectedId != null && data?.records?.none { it.id == selectedId } == true && session == null && page == "detail") {
            selectedId = null; page = "list"
        }
    }

    fun changeText(value: TextFieldValue) {
        if (busy || session == null) return
        val draft = editor ?: return
        editingBuffer = value
        if (value.text != draft.text) change(draft.copy(text = value.text))
    }
    fun applyRemote() {
        val opened = session ?: return
        var latest: EditSession? = null
        operation("已重新载入当前共享草稿；冲突候选仍需人工处理。", {
            latest = if (opened.slot == "new") store.beginNew() else store.beginEdit(opened.targetRecordId)
        }) { useSession(requireNotNull(latest)) }
    }
    fun reload() {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                refreshCommittedContent()
                loadError = false
            } catch (_: Exception) { loadError = true; message = "本机读取失败，未覆盖原内容。请检查存储后重试。" }
            finally { busy = false }
        }
    }
    private fun operation(success: String, block: () -> Unit, navigate: () -> Unit = {}) {
        if (busy || loadError) return
        busy = true
        viewModelScope.launch {
            try {
                io(block)
                try { data = io { store.snapshot() } }
                catch (_: Exception) { loadError = true; message = "操作已提交到本机，但重新读取失败。未覆盖内容，请重试读取。"; return@launch }
                message = success; navigate()
            } catch (e: IllegalArgumentException) { message = e.message ?: "输入无效，内容保留" }
            catch (_: Exception) { message = "本机写入失败，未显示成功。内容保留，请检查存储后重试。" }
            finally { busy = false }
        }
    }
    fun open(record: Record) {
        var heads = emptySet<String>()
        operation("", { heads = store.sync.headIds("record", record.id) }) {
            detailHeads = heads; selectedId = record.id; page = "detail"
        }
    }
    fun trash() { page = "trash"; message = "" }
    private fun begin(recordId: String?) {
        var opened: EditSession? = null
        operation("", { opened = if (recordId == null) store.beginNew() else store.beginEdit(recordId) }) {
            val ready = requireNotNull(opened)
            useSession(ready)
            draftStatus = if (ready.draft.text.isNotBlank()) "已恢复共享草稿，尚未正式保存" else "填写后自动保留草稿"
            page = "edit"; editGeneration++
        }
    }
    fun newRecord() = begin(null)
    fun edit(record: Record) { if (record.deleted == null) begin(record.id) }
    fun change(value: Draft) {
        if (busy || session == null) return
        editor = value; editorDirty = true; saveIntent = null; discardIntent = null
        val generation = ++editGeneration
        val sessionId = session!!.sessionId
        draftStatus = "正在保留草稿…"
        viewModelScope.launch {
            try {
                io {
                    val current = session
                    if (current?.sessionId == sessionId) session = store.keepDraft(current, value)
                }
                if (editGeneration == generation) draftStatus = "草稿已保留 · 尚未正式保存"
            } catch (_: Exception) { if (editGeneration == generation) draftStatus = "草稿写入失败；请勿关闭应用，返回前将提示风险。" }
        }
    }
    fun save() {
        val draft = editor ?: return
        val opened = session ?: return
        val intent = saveIntent ?: UUID.randomUUID().toString().also { saveIntent = it }
        var savedId: String? = null
        operation("已保存到本机；另一端是否提交请查看同步状态。", { savedId = store.save(requireNotNull(session).also { check(it.sessionId == opened.sessionId) }, draft, intent) }) {
            selectedId = savedId; locateId = savedId; clearEditor(); page = "detail"
            detailHeads = store.sync.headIds("record", savedId!!)
        }
    }
    fun discard() {
        val opened = session ?: return
        val intent = discardIntent ?: UUID.randomUUID().toString().also { discardIntent = it }
        operation("已放弃草稿，正式内容未改变。", { store.discardDraft(requireNotNull(session).also { check(it.sessionId == opened.sessionId) }, intent) }) {
            clearEditor(); page = if (opened.draft.recordId == null) "list" else "detail"
        }
    }
    fun delete(record: Record) {
        val heads = detailHeads; val intent = UUID.randomUUID().toString()
        operation("已移入最近删除；另一端确认状态独立显示。", { store.moveToTrash(record.id, heads, intent) }) { page = "list" }
    }
    fun restore(record: Record) {
        val heads = detailHeads; val intent = UUID.randomUUID().toString()
        operation("记录已恢复；编辑草稿未转正。", { store.restore(record.id, heads, intent) }) { locateId = record.id; page = "detail" }
    }
    fun purge(record: Record) {
        val intent = UUID.randomUUID().toString()
        operation("本机已彻底删除，另一端待确认。", { store.purge(record.id, intent) }) { selectedId = null; clearEditor(); page = "trash" }
    }
    fun navigate(targetSection: Int? = null) {
        if (busy) return
        val draft = editor
        val destination = {
            clearEditor()
            if (targetSection != null) { section = targetSection; page = "list" }
            else page = if (draft?.recordId != null) "detail" else if (page == "detail" && data?.records?.find { it.id == selectedId }?.deleted != null) "trash" else "list"
        }
        if (draft == null) { destination(); return }
        busy = true
        viewModelScope.launch {
            try {
                data = io { session = store.keepDraft(requireNotNull(session), draft); store.snapshot() }
                message = "草稿状态已保存，未自动转正。"; destination()
            } catch (_: Exception) { draftStatus = "草稿未可靠保留"; leaveDestination = destination; leaveWarning = true }
            finally { busy = false }
        }
    }
    fun confirmLeave() { leaveWarning = false; leaveDestination?.invoke(); leaveDestination = null }
    fun cancelLeave() { leaveWarning = false; leaveDestination = null }
}
