package app.liferecord.core

import java.time.Clock
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Record and shared-draft projection. Every mutating API is one durable causal intent. */
class RecordStore(
    private val database: SqlDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val fault: (String) -> Unit = {},
) {
    val sync = SyncRepository(database)
    private fun <T> transaction(operation: String, block: (SqlDatabase) -> T): T = database.transaction { db -> block(db).also { fault(operation) } }
    private fun SqlCursor.time(prefix: String = "") = EventTime(string(prefix+"local"), string(prefix+"zone"), string(prefix+"offset"), long(prefix+"epoch"))
    private fun SqlCursor.record() = Record(string("id"), string("body"), time(), string("created"), optional("modified"), optional("deleted"))
    private fun find(db: SqlDatabase, id: String): Record? = db.query("SELECT * FROM records WHERE id=?", listOf(id)).use { if (it.next()) it.record() else null }

    fun snapshot(): Snapshot = transaction("snapshot") { db ->
        val records = db.query("SELECT * FROM records ORDER BY substr(local,1,10) DESC,epoch DESC,id").use { c -> buildList { while(c.next()) add(c.record()) } }
        val drafts = db.query("SELECT * FROM drafts ORDER BY id").use { c -> buildList { while(c.next()) add(c.draft()) } }
        val revisions = db.query("SELECT sync_id,record_id,before_body,before_local,before_zone,before_offset,before_epoch,after_body,after_local,after_zone,after_offset,after_epoch,modified,parent_heads FROM revisions ORDER BY id DESC").use { c ->
            buildList {
                while(c.next()) add(
                    Revision(
                        c.string("sync_id"),c.string("record_id"),c.string("before_body"),c.time("before_"),
                        c.string("after_body"),c.time("after_"),c.string("modified"),SyncCodec.decodeSet(c.string("parent_heads")),
                    ),
                )
            }
        }
        Snapshot(records,drafts,revisions)
    }

    fun beginNew(): EditSession = begin("new", null)
    fun draftCandidate(slot:String,headId:String):Draft = database.transaction { db ->
        val head=sync.readEntity(db,"draft-slot",slot).heads.singleOrNull { it.id==headId }
            ?: throw IllegalArgumentException("草稿候选已变化")
        decodeDraft(head.payload)
    }
    fun beginEdit(recordId: String): EditSession = begin("edit/$recordId", recordId)
    private fun begin(slot: String, recordId: String?): EditSession = transaction("begin") { db ->
        if (recordId != null && sync.isPurged(db, recordId)) throw PurgedRecord(recordId)
        val draftState = sync.readEntity(db,"draft-slot",slot)
        if (draftState.unresolved) throw UnresolvedConflict("draft-slot",slot)
        val saved = db.query("SELECT * FROM drafts WHERE ${if(recordId==null) "new_slot=1" else "record_id=?"}", if(recordId==null) emptyList() else listOf(recordId)).use { if(it.next()) it.draft() else null }
        val record = recordId?.let { requireNotNull(find(db,it)) { "记录不存在" } }
        require(record?.deleted == null) { "已删除记录只能恢复后编辑" }
        val id = saved?.id ?: UUID.randomUUID().toString()
        val target = saved?.targetRecordId ?: recordId ?: UUID.randomUUID().toString()
        val baseHeads = saved?.baseRecordHeads ?: recordId?.let { sync.currentHeadIds(db,"record",it) }.orEmpty()
        val baseSnapshot = saved?.baseRecordSnapshot ?: record
        val draft = saved ?: if(record==null) RecordTime.newDraft(clock,id,target).copy(baseRecordHeads=baseHeads,baseRecordSnapshot=baseSnapshot) else RecordTime.edit(record).copy(id=id,targetRecordId=target,baseRecordHeads=baseHeads,baseRecordSnapshot=baseSnapshot)
        EditSession(UUID.randomUUID().toString(),slot,id,target,baseHeads,baseSnapshot,draftState.heads.mapTo(linkedSetOf()){it.id},null,draft)
    }

    fun keepDraft(session: EditSession, draft: Draft): EditSession = transaction("draft") { db ->
        validateSession(session,draft)
        require(!isDraftTerminated(db,draft.id)) {
            "草稿已在另一端结束；不能恢复原草稿代，只能放弃残留输入或转存为新草稿代"
        }
        if (draft.recordId != null) {
            if(sync.isPurged(db,draft.targetRecordId)) throw PurgedRecord(draft.targetRecordId)
            val current=requireNotNull(find(db,draft.targetRecordId)) { "记录已不存在，未覆盖数据" }
            require(current.deleted==null) { "已删除记录只能恢复后编辑" }
        }
        val unchanged = draft.recordId == null && draft.text.isBlank() || session.baseRecordSnapshot?.let { RecordTime.unchanged(draft,it) } == true
        val observed = session.baseDraftHeads + listOfNotNull(session.lastOwnDraftHead)
        val payload = if(unchanged) buildJsonObject { put("mode","DRAFT_EMPTY"); put("draftId",session.draftId); put("targetRecordId",session.targetRecordId) } else draftPayload(draft)
        val result = sync.writeLocalHead(db,"draft-slot",session.slot,payload,SyncCodec.hash(payload),observed,UUID.randomUUID().toString(),resultKey=session.draftId)
        if(unchanged) db.exec("DELETE FROM drafts WHERE id=?",listOf(session.draftId)) else upsertDraft(db,draft.copy(id=session.draftId,targetRecordId=session.targetRecordId,baseRecordHeads=session.baseRecordHeads,baseRecordSnapshot=session.baseRecordSnapshot))
        session.copy(lastOwnDraftHead=result.headId,draft=draft)
    }

    fun save(session: EditSession, draft: Draft, intentId: String): String = transaction("save") { db ->
        validateSession(session,draft); require(draft.text.isNotBlank()) { "请先填写记录内容" }
        if(sync.isPurged(db,session.targetRecordId)) throw PurgedRecord(session.targetRecordId)
        val time = RecordTime.resolve(draft,clock.instant()); val old = session.baseRecordSnapshot
        require(old?.deleted == null) { "请先恢复已删除记录" }
        if(draft.recordId != null) requireNotNull(old) { "记录不存在，未创建替代记录" }
        if(old != null && old.text == draft.text && old.time == time) {
            consumeDraft(db,session,draft,intentId,DraftTerminalMode.SAVED)
            return@transaction session.targetRecordId
        }
        val stamp = RecordTime.stamp(clock)
        val record = Record(session.targetRecordId,draft.text,time,old?.created ?: stamp,if(old==null)null else stamp,null)
        val result = sync.writeLocalHead(db,"record",record.id,recordPayload(record),observedBase=session.baseRecordHeads,intentId=intentId,resultKey=record.id)
        if(result.changed) {
            val state=sync.readEntity(db,"record",record.id)
            if(!state.unresolved) projectRecord(db,record)
            if(old != null) {
                insertRevision(db,old,record,stamp,session.baseRecordHeads,intentId)
                if(old.time.local.substringBefore('T')!=record.time.local.substringBefore('T')) sync.addLifeEvent(db,record.id,"${result.headId}/life")
            } else sync.addLifeEvent(db,record.id,"${result.headId}/life-create")
        }
        consumeDraft(db,session,draft,intentId,DraftTerminalMode.SAVED)
        record.id
    }

    fun discardDraft(session: EditSession, intentId: String): Unit = transaction("discard") { db ->
        consumeDraft(db,session,session.draft,intentId,DraftTerminalMode.DISCARDED)
    }

    fun moveToTrash(id: String, baseHeads: Set<String>, intentId: String): Unit = lifeChange("delete",id,baseHeads,intentId,true)
    fun restore(id: String, baseHeads: Set<String>, intentId: String): Unit = lifeChange("restore",id,baseHeads,intentId,false)
    private fun lifeChange(op:String,id:String,baseHeads:Set<String>,intentId:String,deleted:Boolean): Unit = transaction(op) { db ->
        if(sync.isPurged(db,id)) throw PurgedRecord(id)
        val old=requireNotNull(find(db,id)); if((old.deleted!=null)==deleted) return@transaction
        val value=old.copy(deleted=if(deleted) RecordTime.stamp(clock) else null)
        val result=sync.writeLocalHead(db,"record",id,recordPayload(value),observedBase=baseHeads,intentId=intentId,resultKey=id)
        if(result.changed) {
            val state=sync.readEntity(db,"record",id)
            if(!state.unresolved) projectRecord(db,value)
            sync.addLifeEvent(db,id,"${result.headId}/life")
        }
    }

    fun purge(id: String, intentId: String): Unit = transaction("purge") { db ->
        val record=find(db,id); require(record==null || record.deleted!=null) { "请先移入最近删除" }
        sync.purge(db,id,intentId)
        db.exec("DELETE FROM sync_history WHERE table_name='revision' AND sync_id IN (SELECT sync_id FROM revisions WHERE record_id=?)",listOf(id))
        db.exec("DELETE FROM drafts WHERE record_id=? OR target_record_id=?",listOf(id,id)); db.exec("DELETE FROM records WHERE id=?",listOf(id))
        db.exec("DELETE FROM revisions WHERE record_id=?",listOf(id))
    }
    fun resolveRecord(id:String,chosenHeadId:String,displayedHeads:Set<String>,intentId:String):Unit = transaction("resolve-record") { db ->
        val before=find(db,id)
        sync.chooseHead("record",id,chosenHeadId,displayedHeads,intentId) { projectionDb,state ->
            if(!state.unresolved) state.heads.firstOrNull()?.let { head ->
                val resolved=decodeRecord(head.payload)
                projectRecord(projectionDb,resolved)
                if(before!=null && before.time.local.substringBefore('T')!=resolved.time.local.substringBefore('T')) sync.addLifeEvent(projectionDb,id,"${head.id}/life")
            }
        }
    }
    fun resolveRecord(session:EditSession,draft:Draft,displayedHeads:Set<String>,intentId:String):String = transaction("resolve-record-manual") { db ->
        validateSession(session,draft); require(draft.text.isNotBlank()) { "请先填写记录内容" }
        if(sync.isPurged(db,session.targetRecordId)) throw PurgedRecord(session.targetRecordId)
        val time=RecordTime.resolve(draft,clock.instant()); val before=session.baseRecordSnapshot; val stamp=RecordTime.stamp(clock)
        val resolved=Record(session.targetRecordId,draft.text,time,before?.created ?: stamp,if(before==null)null else stamp,before?.deleted)
        val result=sync.writeLocalHead(db,"record",resolved.id,recordPayload(resolved),observedBase=displayedHeads,intentId=intentId,resultKey=resolved.id)
        val state=sync.readEntity(db,"record",resolved.id)
        if(result.changed && before!=null) insertRevision(db,before,resolved,stamp,displayedHeads,intentId)
        if(!state.unresolved) {
            state.heads.firstOrNull()?.let { projectRecord(db,decodeRecord(it.payload)) }
            if(result.changed) {
                if(before==null) sync.addLifeEvent(db,resolved.id,"${result.headId}/life-create")
                else if(before.time.local.substringBefore('T')!=resolved.time.local.substringBefore('T')) sync.addLifeEvent(db,resolved.id,"${result.headId}/life")
            }
        }
        if(session.baseDraftHeads.isNotEmpty() || session.lastOwnDraftHead!=null)
            consumeDraft(db,session,draft,intentId,DraftTerminalMode.SAVED)
        resolved.id
    }


    fun isDraftTerminated(draftId:String):Boolean = transaction("draft-terminal-status") { db ->
        isDraftTerminated(db,draftId)
    }

    private fun isDraftTerminated(db:SqlDatabase,draftId:String):Boolean =
        db.query("SELECT 1 FROM sync_draft_terminals WHERE draft_id=? LIMIT 1",listOf(draftId)).use { it.next() }

    fun resolveDraft(slot:String,chosenHeadId:String,displayedHeads:Set<String>,intentId:String):EditSession = transaction("resolve-draft") { db ->
        val before=sync.readEntity(db,"draft-slot",slot)
        require(before.heads.map { it.id }.containsAll(displayedHeads)) { "草稿候选已变化，请重新查看" }
        val chosen=before.heads.singleOrNull { it.id==chosenHeadId && it.id in displayedHeads }
            ?: throw IllegalArgumentException("草稿候选已变化，请重新查看")
        require(chosen.payload.jsonObject["mode"]==null) { "草稿已在另一端结束；只能放弃残留输入或转存为新草稿代" }
        val selected=decodeDraft(chosen.payload)
        require(!isDraftTerminated(db,selected.id)) { "草稿已在另一端结束；只能放弃残留输入或转存为新草稿代" }
        val result=sync.writeLocalHead(db,"draft-slot",slot,chosen.payload,chosen.payloadHash,displayedHeads,intentId,resultKey=slot)
        val state=sync.readEntity(db,"draft-slot",slot)
        if(!state.unresolved) upsertDraft(db,selected)
        if(sync.isPurged(db,selected.targetRecordId)) throw PurgedRecord(selected.targetRecordId)
        EditSession(UUID.randomUUID().toString(),slot,selected.id,selected.targetRecordId,selected.baseRecordHeads,selected.baseRecordSnapshot,state.heads.mapTo(linkedSetOf()){it.id},result.headId,selected)
    }

    /**
     * Adopts exactly one displayed candidate and, when requested, preserves every displayed
     * unselected text candidate as a deterministic new draft generation. Heads arriving after
     * the screen baseline are never retired and therefore keep the original slot unresolved.
     * Terminal candidates are resolved as terminal state, never decoded into a fake draft.
     */
    fun resolveDraftConflict(
        slot:String,
        chosenHeadId:String,
        displayedHeads:Set<String>,
        forkUnselectedToNew:Boolean,
        intentId:String,
    ):Unit = transaction("resolve-draft-conflict") { db ->
        if(db.query("SELECT 1 FROM sync_intents WHERE intent_id=?",listOf(intentId)).use { it.next() }) return@transaction
        require(displayedHeads.isNotEmpty()) { "草稿候选已变化，请重新查看" }
        val before=sync.readEntity(db,"draft-slot",slot)
        require(before.heads.map { it.id }.containsAll(displayedHeads)) { "草稿候选已变化，请重新查看" }
        val chosen=before.heads.singleOrNull { it.id==chosenHeadId && it.id in displayedHeads }
            ?: throw IllegalArgumentException("草稿候选已变化，请重新查看")
        if(chosen.payload.jsonObject["mode"]==null) {
            val selectedDraft=decodeDraft(chosen.payload)
            require(!isDraftTerminated(db,selectedDraft.id)) {
                "草稿已在另一端结束；请选择放弃该残留输入或将它转存为新草稿代"
            }
        }
        val residuals=before.heads.filter { it.id in displayedHeads && it.id!=chosenHeadId && it.payload.jsonObject["mode"]==null }


        sync.writeLocalHead(
            db,"draft-slot",slot,chosen.payload,chosen.payloadHash,displayedHeads,intentId,resultKey=slot,
        )
        val after=sync.readEntity(db,"draft-slot",slot)
        if(!after.unresolved) {
            val resolved=after.heads.singleOrNull()
            if(resolved?.payload?.jsonObject?.get("mode")!=null) {
                val terminalDraftId=resolved.payload.jsonObject["draftId"]?.jsonPrimitive?.content
                if(terminalDraftId!=null) db.exec("DELETE FROM drafts WHERE id=?",listOf(terminalDraftId))
            } else if(resolved!=null) {
                upsertDraft(db,decodeDraft(resolved.payload))
            }
        }

        if(forkUnselectedToNew && residuals.isNotEmpty()) {
            require(residuals.size==1) {
                "有多个未选草稿候选；请逐一明确转存或放弃，不能自动复制全部候选"
            }
            val residual=residuals.single()
            val source=decodeDraft(residual.payload)
            if(sync.isPurged(db,source.targetRecordId)) throw PurgedRecord(source.targetRecordId)
            val newState=sync.readEntity(db,"draft-slot","new")
            if(slot!="new") require(newState.heads.all { it.payload.jsonObject["mode"]!=null }) {
                "已有新增草稿，请先处理后再转存未选候选"
            }
            val suffix="$intentId:${residual.id}"
            val generation=UUID.nameUUIDFromBytes("draft-fork:$suffix".toByteArray(Charsets.UTF_8)).toString()
            val target=UUID.nameUUIDFromBytes("draft-target:$suffix".toByteArray(Charsets.UTF_8)).toString()
            val fork=source.copy(
                id=generation,recordId=null,targetRecordId=target,
                baseRecordHeads=emptySet(),baseRecordSnapshot=null,
            )
            val base=if(slot=="new") emptySet() else newState.heads.mapTo(linkedSetOf()){it.id}
            sync.writeLocalHead(
                db,"draft-slot","new",draftPayload(fork),observedBase=base,
                intentId="$intentId:fork",bundleId="$intentId:fork",resultKey=generation,
            )
            val forkedState=sync.readEntity(db,"draft-slot","new")
            db.exec("DELETE FROM drafts WHERE new_slot=1")
            if(!forkedState.unresolved) forkedState.heads.singleOrNull()?.takeIf { it.payload.jsonObject["mode"]==null }
                ?.let { upsertDraft(db,decodeDraft(it.payload)) }
        }
    }
    fun resolveDraft(session:EditSession,draft:Draft,displayedHeads:Set<String>,intentId:String):EditSession = transaction("resolve-draft-manual") { db ->
        validateSession(session,draft)
        require(!isDraftTerminated(db,draft.id)) {
            "草稿已在另一端结束；不能恢复原草稿代，只能放弃残留输入或转存为新草稿代"
        }
        if(sync.isPurged(db,draft.targetRecordId)) throw PurgedRecord(draft.targetRecordId)
        val payload=draftPayload(draft)
        val result=sync.writeLocalHead(db,"draft-slot",session.slot,payload,SyncCodec.hash(payload),displayedHeads,intentId,resultKey=draft.id)
        val state=sync.readEntity(db,"draft-slot",session.slot)
        if(!state.unresolved) state.heads.firstOrNull()?.payload?.takeIf { it.jsonObject["mode"]==null }
            ?.let { upsertDraft(db,decodeDraft(it)) }
        session.copy(baseDraftHeads=state.heads.mapTo(linkedSetOf()){it.id},lastOwnDraftHead=result.headId,draft=draft)
    }

    /** Explicitly copies a displayed residual into a new draft generation, never a formal record. */
    fun forkDraft(slot:String, chosenHeadId:String, displayedHeads:Set<String>, intentId:String):EditSession = transaction("fork-draft") { db ->
        val previous=db.query("SELECT result_key FROM sync_intents WHERE intent_id=?",listOf(intentId)).use { if(it.next()) it.string(0) else null }
        if(previous!=null) {
            val state=sync.readEntity(db,"draft-slot","new")
            val replayed=state.heads.asSequence().filter { it.payload.jsonObject["mode"]==null }
                .map { decodeDraft(it.payload) }.singleOrNull { it.id==previous }
                ?: throw IllegalStateException("该转存已完成，草稿状态随后已变化")
            val ownHead=state.heads.single { it.payload.jsonObject["id"]?.jsonPrimitive?.content==previous }.id
            return@transaction EditSession(
                UUID.randomUUID().toString(),"new",replayed.id,replayed.targetRecordId,
                replayed.baseRecordHeads,replayed.baseRecordSnapshot,state.heads.mapTo(linkedSetOf()){it.id},ownHead,replayed,
            )
        }
        val state=sync.readEntity(db,"draft-slot",slot)
        require(state.heads.map { it.id }.containsAll(displayedHeads)) { "草稿候选已变化，请重新查看" }
        val chosen=state.heads.singleOrNull { it.id==chosenHeadId } ?: throw IllegalArgumentException("候选版本已变化")
        require(chosenHeadId in displayedHeads) { "只能转存已展示的候选" }
        require(chosen.payload.jsonObject["mode"]==null) { "终结状态没有可转存正文" }
        val source=decodeDraft(chosen.payload)
        if(sync.isPurged(db,source.targetRecordId)) throw PurgedRecord(source.targetRecordId)
        val newState=sync.readEntity(db,"draft-slot","new")
        require(slot=="new" || newState.heads.all { it.payload.jsonObject["mode"] != null }) { "已有新增草稿，请先处理后再转存" }
        val generation=UUID.nameUUIDFromBytes("draft-fork:$intentId".toByteArray(Charsets.UTF_8)).toString()
        val draft=source.copy(id=generation,recordId=null,targetRecordId=UUID.nameUUIDFromBytes("draft-target:$intentId".toByteArray(Charsets.UTF_8)).toString(),baseRecordHeads=emptySet(),baseRecordSnapshot=null)
        val base=if(slot=="new") setOf(chosenHeadId) else newState.heads.map { it.id }.toSet()
        val result=sync.writeLocalHead(db,"draft-slot","new",draftPayload(draft),observedBase=base,intentId=intentId,resultKey=generation)
        if(slot!="new") sync.retireHeads("draft-slot",slot,setOf(chosenHeadId),"$intentId:consume")
        db.exec("DELETE FROM drafts WHERE new_slot=1")
        if(!sync.hasUnresolved(db,"draft-slot","new")) upsertDraft(db,draft)
        EditSession(UUID.randomUUID().toString(),"new",generation,draft.targetRecordId,emptySet(),null,base,result.headId,draft)
    }

    fun discardDraftCandidate(slot:String,headId:String,displayedHeads:Set<String>,intentId:String):Unit = transaction("discard-draft-candidate") { db ->
        require(headId in displayedHeads) { "只能放弃已展示的候选" }
        val state=sync.readEntity(db,"draft-slot",slot)
        require(state.heads.map { it.id }.containsAll(displayedHeads)) { "草稿候选已变化，请重新查看" }
        val head=state.heads.singleOrNull { it.id==headId } ?: return@transaction
        val survivors=state.heads.filterNot { it.id==headId }
        val wouldStrandEndedResidual=survivors.isNotEmpty() &&
            survivors.map { it.payloadHash }.distinct().size<=1 &&
            survivors.any { candidate ->
                candidate.payload.jsonObject.takeIf { it["mode"]==null }
                    ?.let(::decodeDraft)?.let { isDraftTerminated(db,it.id) } == true
            }
        require(!wouldStrandEndedResidual) {
            "请先放弃残留输入或将它转存为新草稿代，再处理其他候选"
        }
        val source=head.payload.jsonObject.takeIf { it["mode"]==null }?.let(::decodeDraft)
        sync.retireHeads("draft-slot",slot,setOf(headId),intentId)
        source?.let { db.exec("DELETE FROM drafts WHERE id=?",listOf(it.id)) }
        val remaining=sync.readEntity(db,"draft-slot",slot)
        if(!remaining.unresolved) {
            val resolved=remaining.heads.singleOrNull()
            if(resolved?.payload?.jsonObject?.get("mode")!=null) {
                resolved.payload.jsonObject["draftId"]?.jsonPrimitive?.content?.let {
                    db.exec("DELETE FROM drafts WHERE id=?",listOf(it))
                }
            } else if(resolved!=null) upsertDraft(db,decodeDraft(resolved.payload))
        }
    }


    private fun consumeDraft(db:SqlDatabase,session:EditSession,draft:Draft,intentId:String,mode:DraftTerminalMode) {
        val observed=session.baseDraftHeads+listOfNotNull(session.lastOwnDraftHead)
        val empty=buildJsonObject { put("mode",mode.name); put("draftId",session.draftId); put("targetRecordId",session.targetRecordId) }
        val result=sync.writeLocalHead(db,"draft-slot",session.slot,empty,SyncCodec.hash(empty),observed,"$intentId:draft",intentId,session.draftId)
        sync.addDraftTerminal(db,DraftTerminal(session.draftId,"${result.headId}/terminal",mode,session.targetRecordId,observed,SyncCodec.hash(draftPayload(draft))))
        db.exec("DELETE FROM drafts WHERE id=?",listOf(session.draftId))
    }

    private fun insertRevision(db:SqlDatabase,before:Record,after:Record,stamp:String,parents:Set<String>,intentId:String) {
        val syncId=sync.allocateVersionPart(db,intentId,"revision")
        db.exec("INSERT INTO revisions(record_id,before_body,before_local,before_zone,before_offset,before_epoch,after_body,after_local,after_zone,after_offset,after_epoch,modified,sync_id,parent_heads) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",listOf(before.id,before.text,before.time.local,before.time.zone,before.time.offset,before.time.instant,after.text,after.time.local,after.time.zone,after.time.offset,after.time.instant,stamp,syncId,SyncCodec.encodeSet(parents)))
        val payload=buildJsonObject { put("recordId",before.id); put("before",recordPayload(before)); put("after",recordPayload(after)); put("modified",stamp); put("parentHeads",SyncCodec.encodeSet(parents)) }
        db.exec("INSERT INTO sync_history(table_name,sync_id,payload_hash,payload_json) VALUES('revision',?,?,?)",listOf(syncId,SyncCodec.hash(payload),SyncCodec.canonical(payload)))
    }
    private fun projectRecord(db:SqlDatabase,r:Record) { val args=listOf(r.text,r.time.local,r.time.zone,r.time.offset,r.time.instant,r.created,r.modified,r.deleted,r.id); if(db.exec("UPDATE records SET body=?,local=?,zone=?,offset=?,epoch=?,created=?,modified=?,deleted=? WHERE id=?",args)==0) db.exec("INSERT INTO records(body,local,zone,offset,epoch,created,modified,deleted,id) VALUES(?,?,?,?,?,?,?,?,?)",args) }
    private fun upsertDraft(db:SqlDatabase,d:Draft) { val snapshot=d.baseRecordSnapshot?.let { SyncCodec.canonical(recordPayload(it)) }; val args=listOf(d.recordId,if(d.recordId==null)1 else null,d.text,d.local,d.zone,d.offset,d.targetRecordId,SyncCodec.encodeSet(d.baseRecordHeads),snapshot,d.id); if(db.exec("UPDATE drafts SET record_id=?,new_slot=?,body=?,local=?,zone=?,offset=?,target_record_id=?,base_record_heads=?,base_record_snapshot=? WHERE id=?",args)==0) db.exec("INSERT INTO drafts(record_id,new_slot,body,local,zone,offset,target_record_id,base_record_heads,base_record_snapshot,id) VALUES(?,?,?,?,?,?,?,?,?,?)",args) }
    private fun SqlCursor.draft():Draft { val snapshot=optional("base_record_snapshot")?.let(::decodeRecord); return Draft(string("id"),optional("record_id"),string("body"),string("local"),string("zone"),optional("offset"),string("target_record_id"),SyncCodec.decodeSet(string("base_record_heads")),snapshot) }
    private fun validateSession(s:EditSession,d:Draft) { require(d.id==s.draftId && d.targetRecordId==s.targetRecordId && d.recordId==s.baseRecordSnapshot?.id) { "草稿身份不匹配" } }
    private fun recordPayload(r:Record):JsonElement=buildJsonObject { put("id",r.id);put("text",r.text);put("local",r.time.local);put("zone",r.time.zone);put("offset",r.time.offset);put("instant",r.time.instant);put("created",r.created);r.modified?.let{put("modified",it)};r.deleted?.let{put("deleted",it)} }
    private fun draftPayload(d:Draft):JsonElement=buildJsonObject { put("id",d.id);d.recordId?.let{put("recordId",it)};put("targetRecordId",d.targetRecordId);put("text",d.text);put("local",d.local);put("zone",d.zone);d.offset?.let{put("offset",it)};put("baseRecordHeads",SyncCodec.encodeSet(d.baseRecordHeads));d.baseRecordSnapshot?.let{put("baseRecordSnapshot",recordPayload(it))} }
    private fun decodeRecord(raw:String):Record = decodeRecord(SyncCodec.parse(raw))
    private fun decodeRecord(raw:JsonElement):Record {
        val value=raw.jsonObject
        fun field(name:String):String = value.getValue(name).jsonPrimitive.content
        return Record(
            field("id"),field("text"),
            EventTime(field("local"),field("zone"),field("offset"),field("instant").toLong()),
            field("created"),value["modified"]?.jsonPrimitive?.content,value["deleted"]?.jsonPrimitive?.content,
        )
    }
    private fun decodeDraft(raw:JsonElement):Draft {
        val value=raw.jsonObject
        fun field(name:String):String = value.getValue(name).jsonPrimitive.content
        val snapshot=value["baseRecordSnapshot"]?.let(::decodeRecord)
        return Draft(
            field("id"),value["recordId"]?.jsonPrimitive?.content,field("text"),field("local"),field("zone"),
            value["offset"]?.jsonPrimitive?.content,field("targetRecordId"),
            value["baseRecordHeads"]?.jsonPrimitive?.content?.let(SyncCodec::decodeSet).orEmpty(),snapshot,
        )
    }
}
