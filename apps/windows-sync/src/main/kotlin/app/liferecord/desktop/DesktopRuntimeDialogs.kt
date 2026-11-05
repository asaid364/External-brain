package app.liferecord.desktop

import app.liferecord.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.time.ZonedDateTime
import java.util.UUID
import javax.swing.*

/** Native modal integration dialogs. Candidate sets are captured before display, never reread as a write base. */
object DesktopRuntimeDialogs {
    fun confirm(title: String, text: String): Boolean = JOptionPane.showConfirmDialog(null, text, title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION

    suspend fun resolveConflicts(states: List<EntityState>, store: RecordStore, reports: ReportRepository, goals: GoalRepository) {
        if (states.isEmpty()) {
            JOptionPane.showMessageDialog(null, "当前没有待解决冲突", "数据与同步", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val labels = states.map { "${it.kind}/${it.key} (${it.heads.size}个候选)" }
        val chosen = JOptionPane.showInputDialog(null, "选择待解决项目", "同步冲突", JOptionPane.QUESTION_MESSAGE, null, labels.toTypedArray(), labels.first()) as? String ?: return
        val state = states[labels.indexOf(chosen)]
        val heads = state.heads.mapTo(linkedSetOf()) { it.id }
        val list = JList(state.heads.map { "来源 ${it.id.substringBefore('/')} · 版本 ${it.id}" }.toTypedArray()).apply { selectedIndex = 0 }
        val content = JTextArea(18, 80).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; text = describe(state.heads.first()) }
        list.addListSelectionListener { if (!it.valueIsAdjusting && list.selectedIndex >= 0) content.text = describe(state.heads[list.selectedIndex]) }
        val panel = JPanel(BorderLayout(8, 8)).apply { add(JScrollPane(list), BorderLayout.NORTH); add(JScrollPane(content), BorderLayout.CENTER); preferredSize = Dimension(850, 570) }
        val options = if (state.kind in setOf("report", "goal-review")) arrayOf("采用完整候选", "取消") else arrayOf("采用候选", "手动整合", "取消")
        val action = JOptionPane.showOptionDialog(null, panel, "${state.kind}/${state.key} · 不按时间选择胜者", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options.last())
        if (action !in 0..1 || action == options.lastIndex) return
        val head = state.heads[list.selectedIndex]
        if (!confirm("确认解决冲突", "本次操作仅终结界面已显示的 ${heads.size} 个候选；后来到达的版本仍待解决。未选择候选不再作为当前版本。继续？")) return
        when (state.kind) {
            "record" -> {
                if (action == 0) withContext(Dispatchers.IO) { store.resolveRecord(state.key, head.id, heads, uuid()) }
                else {
                    val record = record(head.payload)
                    require(record.deleted == null) { "已删除候选不能直接手动编辑，请明确选择删除/恢复候选" }
                    val draft = RecordTime.edit(record).copy(id = uuid(), baseRecordHeads = heads)
                    val edited = edit(draft) ?: return
                    val session = EditSession(uuid(), "edit/${record.id}", draft.id, record.id, heads, record, emptySet(), null, draft)
                    withContext(Dispatchers.IO) { store.resolveRecord(session, edited, heads, uuid()) }
                }
            }
            "draft-slot" -> {
                val payload = head.payload.jsonObject
                if (payload["mode"] != null) {
                    require(action == 0) { "空草稿标记不能手动整合" }
                    withContext(Dispatchers.IO) { store.sync.chooseHead(state.kind, state.key, head.id, heads, uuid()) { _, _ -> } }
                } else {
                    var selected = withContext(Dispatchers.IO) { store.draftCandidate(state.key,head.id) }
                    val ended = withContext(Dispatchers.IO) { store.sync.snapshot().draftTerminals.any { it.draftId == selected.id } }
                    if (ended) {
                        if (!confirm("该草稿代已结束", "残留输入不能复活已保存/放弃的草稿。是否将所选文字明确转为一个新草稿代？")) return
                        withContext(Dispatchers.IO) { store.forkDraft(state.key,head.id,heads,uuid()) }
                        return
                    }
                    if (action == 1) selected = edit(selected) ?: return
                    val session = EditSession(uuid(), state.key, selected.id, selected.targetRecordId, selected.baseRecordHeads, selected.baseRecordSnapshot, heads, null, selected)
                    withContext(Dispatchers.IO) { store.resolveDraft(session, selected, heads, uuid()) }
                }
            }
            "report" -> withContext(Dispatchers.IO) { reports.resolveReport(state.key, head.id, heads, uuid()) }
            "answer" -> {
                val date = state.key.substringBefore('/')
                val id = state.key.substringAfter('/')
                if (action == 0) withContext(Dispatchers.IO) { reports.resolveAnswer(date, id, head.id, heads, uuid()) }
                else {
                    val payload = head.payload.jsonObject
                    val text = JTextArea(payload["answer"]?.jsonPrimitive?.content.orEmpty(), 12, 70).apply { lineWrap = true; wrapStyleWord = true }
                    val skipped = JCheckBox("跳过此问题", payload["skipped"]?.jsonPrimitive?.booleanOrNull == true)
                    val form = JPanel(BorderLayout()).apply { add(JScrollPane(text), BorderLayout.CENTER); add(skipped, BorderLayout.SOUTH) }
                    if (JOptionPane.showConfirmDialog(null, form, "手动整合答案（保留所选候选的题面与来源证据）", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
                    val result = JsonObject(payload.toMutableMap().apply {
                        put("answer", JsonPrimitive(text.text)); put("answeredAt", if (text.text.isBlank()) JsonNull else JsonPrimitive(ZonedDateTime.now().toString())); put("skipped", JsonPrimitive(skipped.isSelected))
                    })
                    withContext(Dispatchers.IO) { reports.resolveAnswer(date, id, result, heads, uuid()) }
                }
            }
            "goal" -> {
                val value = if (action == 0) head.payload else {
                    val goal = GoalCodec.decodeGoal(head.payload)
                    val title = JTextField(goal.title)
                    val desired = JTextField(goal.desiredResult)
                    val start = JTextField(goal.startDate)
                    val end = JTextField(goal.endDate)
                    val zone = JTextField(goal.reviewZone)
                    val cadence = JTextField(goal.cadenceDays.toString())
                    val criteria = goal.milestones.map { JTextField(it.criterion) }
                    val form = JPanel(GridLayout(0, 2, 8, 8)).apply {
                        add(JLabel("目标名称")); add(title); add(JLabel("期望成果")); add(desired)
                        add(JLabel("起始日期")); add(start); add(JLabel("截止日期")); add(end)
                        add(JLabel("回顾时区")); add(zone); add(JLabel("周期天数")); add(cadence)
                        criteria.forEachIndexed { index, field -> add(JLabel("完成标准 ${index + 1}")); add(field) }
                    }
                    if (JOptionPane.showConfirmDialog(null, form, "整合目标 · 修改标准会重算进度并保留旧确认", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
                    GoalCodec.encodeGoal(goal.copy(title = title.text, desiredResult = desired.text,
                        startDate = start.text, endDate = end.text, reviewZone = zone.text,
                        cadenceDays = cadence.text.toIntOrNull() ?: error("周期必须为正整数"),
                        milestones = goal.milestones.mapIndexed { index, milestone ->
                            val text = criteria[index].text.trim()
                            GoalMilestone(if (text == milestone.criterion.trim()) milestone.id else uuid(), text)
                        }))
                }
                withContext(Dispatchers.IO) { goals.resolveGoal(state.key, value, heads, uuid()) }
            }
            "goal-confirmation" -> {
                val confirmation = GoalCodec.decodeConfirmation(head.payload)
                val value = if (action == 0) confirmation else {
                    val confirmed = JCheckBox("用户确认达成", confirmation.confirmed)
                    val note = JTextArea(confirmation.note, 8, 60)
                    val form = JPanel(BorderLayout()).apply {
                        add(JLabel("用户声明 · ${confirmation.criterionSnapshot}"), BorderLayout.NORTH)
                        add(JScrollPane(note), BorderLayout.CENTER); add(confirmed, BorderLayout.SOUTH)
                    }
                    if (JOptionPane.showConfirmDialog(null, form, "整合用户声明（保留所选标准和证据）", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return
                    confirmation.copy(confirmed = confirmed.isSelected, note = note.text)
                }
                withContext(Dispatchers.IO) { goals.resolveConfirmation(value.goalId, value.milestoneId, GoalCodec.encodeConfirmation(value), heads, uuid()) }
            }
            "goal-review" -> withContext(Dispatchers.IO) { goals.resolveReview(GoalReviewKey.decode(state.key), head.id, heads, uuid()) }
            else -> error("未知冲突类型，未修改数据")
        }
    }

    private fun describe(head: SyncHead): String {
        val o = head.payload.jsonObject
        val text = o["text"]?.jsonPrimitive?.content ?: o["answer"]?.jsonPrimitive?.content
        return "版本：${head.id}\n来源：${head.id.substringBefore('/')}\n\n" + if (text != null) "$text\n\n当地时间：${o["local"] ?: "未知"}\n时区：${o["zone"] ?: "未知"}\n修改时间：${o["modified"] ?: o["answeredAt"] ?: "未知"}\n\n完整候选：\n${head.payload}" else head.payload.toString()
    }

    private fun edit(draft: Draft): Draft? {
        val text = JTextArea(draft.text, 12, 70).apply { lineWrap = true; wrapStyleWord = true }
        val local = JTextField(draft.local)
        val zone = JTextField(draft.zone)
        val offset = JTextField(draft.offset.orEmpty())
        val time = JPanel(GridLayout(3, 2)).apply { add(JLabel("当地时间 yyyy-MM-dd HH:mm")); add(local); add(JLabel("IANA时区")); add(zone); add(JLabel("UTC时差（夏令时重叠须明确选择）")); add(offset) }
        val form = JPanel(BorderLayout()).apply { add(JScrollPane(text), BorderLayout.CENTER); add(time, BorderLayout.SOUTH) }
        if (JOptionPane.showConfirmDialog(null, form, "手动整合完整内容", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return null
        return draft.copy(text = text.text, local = local.text, zone = zone.text, offset = offset.text.takeIf { it.isNotBlank() })
    }

    private fun record(raw: JsonElement): Record {
        val o = raw.jsonObject
        fun value(key: String) = o.getValue(key).jsonPrimitive.content
        return Record(value("id"), value("text"), EventTime(value("local"), value("zone"), value("offset"), o.getValue("instant").jsonPrimitive.long), value("created"), o["modified"]?.jsonPrimitive?.contentOrNull, o["deleted"]?.jsonPrimitive?.contentOrNull)
    }
    private fun uuid() = UUID.randomUUID().toString()
}
