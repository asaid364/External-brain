package app.liferecord.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Known/retired heads have no payload, but their goal identity must still be well-formed. */
internal fun validateGoalSyncKey(kind: String, key: String) {
    when (kind) {
        GoalCodec.GOAL_KIND -> require(java.util.UUID.fromString(key).toString() == key) { "目标同步键无效" }
        GoalCodec.CONFIRMATION_KIND -> {
            val parts = key.split('/')
            require(parts.size == 2 && parts.all { java.util.UUID.fromString(it).toString() == it }) { "里程碑确认同步键无效" }
        }
        GoalCodec.REVIEW_KIND -> {
            val parts = key.split('/')
            require(parts.size == 3) { "目标回顾同步键无效" }
            GoalCodec.validateReviewKey(GoalReviewKey(parts[0], parts[1], parts[2]))
        }
    }
}

class SyncRepository(private val database: SqlDatabase) {
    fun identity(): SyncIdentity = database.transaction(::identity)
    fun headIds(kind: String, key: String): Set<String> = database.transaction { currentHeadIds(it, kind, key) }
    fun readEntity(kind: String, key: String): EntityState = database.transaction { readEntity(it, kind, key) }
    fun conflicts(): List<EntityState> = database.transaction { db ->
        entityKeys(db).map { readEntity(db, it.first, it.second) }.filter { it.unresolved }
    }
    fun unresolvedKeys(kind: String): Set<String> = database.transaction { db ->
        entityKeys(db, kind).mapNotNullTo(linkedSetOf()) { (_, key) -> key.takeIf { hasUnresolved(db, kind, key) } }
    }
    fun isPurged(recordId: String): Boolean = database.transaction { isPurged(it, recordId) }
    fun acknowledgeLocal(peerId:String,peerDatabaseId:String,throughSeq:Long) = database.transaction { db ->
        require(throughSeq<=identity(db).commitSeq) { "ACK超过本机提交水位" }
        val old=db.query("SELECT peer_database_id,acked_local_seq FROM sync_peer WHERE peer_id=?",listOf(peerId)).use { if(it.next()) it.optional("peer_database_id") to it.long(1) else null }
        if(old!=null && old.first!=null && old.first!=peerDatabaseId) throw IllegalStateException("对端数据库身份已变化，必须全量重新合并")
        val ack=maxOf(old?.second ?: 0L,throughSeq)
        if(old==null) db.exec("INSERT INTO sync_peer(peer_id,peer_database_id,acked_local_seq) VALUES(?,?,?)",listOf(peerId,peerDatabaseId,ack)) else db.exec("UPDATE sync_peer SET peer_database_id=?,acked_local_seq=? WHERE peer_id=?",listOf(peerDatabaseId,ack,peerId))
        db.exec("DELETE FROM sync_changes WHERE commit_seq<=?",listOf(ack))
    }
    fun peerAck(peerId:String):Long = database.transaction { db -> db.query("SELECT acked_local_seq FROM sync_peer WHERE peer_id=?",listOf(peerId)).use { if(it.next()) it.long(0) else 0L } }
    fun changes(afterExclusive: Long, throughInclusive: Long = Long.MAX_VALUE): List<SyncChange> = database.transaction { db ->
        db.query("SELECT commit_seq,kind,entity_key,bundle_id FROM sync_changes WHERE commit_seq>? AND commit_seq<=? ORDER BY commit_seq,kind,entity_key", listOf(afterExclusive, throughInclusive)).use { c ->
            buildList { while (c.next()) add(SyncChange(c.long(0), c.string(1), c.string(2), c.string(3))) }
        }
    }
    fun resolve(
        kind:String,key:String,payload:JsonElement,displayedHeads:Set<String>,intentId:String,
        projector:(SqlDatabase,EntityState)->Unit,
    ):LocalWriteResult = database.transaction { db ->
        val result=writeLocalHead(db,kind,key,payload,SyncCodec.hash(payload),displayedHeads,intentId,resultKey=key)
        val state=readEntity(db,kind,key)
        projectCore(db,setOf(kind to key),emptySet())
        projector(db,state)
        result
    }
    fun chooseHead(
        kind:String,key:String,chosenHeadId:String,displayedHeads:Set<String>,intentId:String,
        projector:(SqlDatabase,EntityState)->Unit,
    ):LocalWriteResult = database.transaction { db ->
        replay(db,intentId)?.let { replayed ->
            val state=readEntity(db,kind,key)
            projectCore(db,setOf(kind to key),emptySet())
            projector(db,state)
            return@transaction replayed
        }
        val chosen=readEntity(db,kind,key).heads.singleOrNull { it.id==chosenHeadId } ?: throw IllegalArgumentException("候选版本已变化")
        val result=writeLocalHead(db,kind,key,chosen.payload,chosen.payloadHash,displayedHeads,intentId,resultKey=key)
        val state=readEntity(db,kind,key)
        projectCore(db,setOf(kind to key),emptySet())
        projector(db,state)
        result
    }
    fun retireHeads(kind: String, key: String, headIds: Set<String>, intentId: String): Unit = database.transaction { db ->
        replay(db, intentId)?.let { return@transaction }
        headIds.forEach { retireKnown(db, kind, key, it) }
        val marker = allocateVersionPart(db, intentId, "retire")
        val seq = nextCommit(db)
        db.exec("INSERT INTO sync_changes(commit_seq,kind,entity_key,bundle_id) VALUES(?,?,?,?)", listOf(seq,kind,key,intentId))
        db.exec("INSERT INTO sync_intents(intent_id,bundle_id,result_key,head_id) VALUES(?,?,?,?)", listOf(intentId,intentId,key,marker))
    }
    fun unresolvedRecordIdsForDate(date:String):Set<String> = database.transaction { db ->
        entityKeys(db,"record").mapNotNullTo(linkedSetOf()) { (_,id) ->
            val state=readEntity(db,"record",id)
            id.takeIf { state.unresolved && state.heads.any { head -> head.payload.jsonObject["local"]?.jsonPrimitive?.content?.substringBefore('T')==date } }
        }
    }

    fun writeLocalHead(kind: String, key: String, payload: JsonElement, observedBase: Set<String>, intentId: String, bundleId: String = intentId, resultKey: String = key): LocalWriteResult =
        database.transaction { writeLocalHead(it, kind, key, payload, SyncCodec.hash(payload), observedBase, intentId, bundleId, resultKey) }

    fun readEntity(db: SqlDatabase, kind: String, key: String): EntityState {
        val retired = db.query("SELECT head_id FROM sync_retired WHERE kind=? AND entity_key=? ORDER BY head_id", listOf(kind, key)).use { c -> buildSet { while (c.next()) add(c.string(0)) } }
        val heads = db.query("SELECT head_id,payload_hash,payload_json FROM sync_heads WHERE kind=? AND entity_key=? ORDER BY head_id", listOf(kind, key)).use { c ->
            buildList { while (c.next()) if (c.string(0) !in retired) add(SyncHead(c.string(0), c.string(1), SyncCodec.parse(c.string(2)))) }
        }
        return EntityState(kind, key, heads, retired)
    }

    fun currentHeadIds(db: SqlDatabase, kind: String, key: String): Set<String> = readEntity(db, kind, key).heads.mapTo(linkedSetOf()) { it.id }
    fun hasUnresolved(db: SqlDatabase, kind: String, key: String): Boolean = readEntity(db, kind, key).unresolved
    fun lifeEventIds(db: SqlDatabase, recordId: String): Set<String> = db.query("SELECT event_id FROM sync_record_life WHERE record_id=? ORDER BY event_id", listOf(recordId)).use { c -> buildSet { while (c.next()) add(c.string(0)) } }
    fun isPurged(db: SqlDatabase, recordId: String): Boolean = db.query("SELECT 1 FROM sync_purges WHERE record_id=?", listOf(recordId)).use { it.next() }

    fun writeLocalHead(
        db: SqlDatabase, kind: String, key: String, payloadJson: JsonElement, payloadHash: String = SyncCodec.hash(payloadJson),
        observedBase: Set<String>, intentId: String, bundleId: String = intentId, resultKey: String = key,
    ): LocalWriteResult {
        require(intentId.isNotBlank()) { "intentId不能为空" }
        replay(db, intentId)?.let { return it }
        if (kind == "record" && isPurged(db, key)) throw PurgedRecord(key)
        val canonical = SyncCodec.canonical(payloadJson)
        require(payloadHash == SyncCodec.hash(payloadJson)) { "payload hash不匹配" }
        val current = readEntity(db, kind, key)
        val identical = current.heads.firstOrNull { it.payloadHash == payloadHash }
        if (!current.unresolved && identical != null && observedBase.containsAll(current.heads.map { it.id })) {
            db.exec("INSERT INTO sync_intents(intent_id,bundle_id,result_key,head_id) VALUES(?,?,?,?)", listOf(intentId, bundleId, resultKey, identical.id))
            return LocalWriteResult(identical.id, resultKey, false)
        }
        val headId = allocateVersionPart(db, bundleId, "head-${SyncCodec.sha256("$kind\u0000$key".toByteArray()).take(12)}")
        observedBase.forEach { retireKnown(db, kind, key, it) }
        insertHead(db, kind, key, headId, payloadHash, canonical)
        val seq = commitForBundle(db, bundleId)
        db.exec("INSERT INTO sync_changes(commit_seq,kind,entity_key,bundle_id) VALUES(?,?,?,?)", listOf(seq, kind, key, bundleId))
        db.exec("INSERT INTO sync_intents(intent_id,bundle_id,result_key,head_id) VALUES(?,?,?,?)", listOf(intentId, bundleId, resultKey, headId))
        return LocalWriteResult(headId, resultKey, true)
    }

    fun allocateVersionPart(db: SqlDatabase, intentId: String, part: String): String {
        require(part.isNotBlank() && '/' !in part)
        val existing = db.query("SELECT version_id FROM sync_version_parts WHERE intent_id=? AND part=?", listOf(intentId,part)).use { if (it.next()) it.string(0) else null }
        if (existing != null) return existing
        val base = db.query("SELECT version_id FROM sync_version_parts WHERE intent_id=? ORDER BY part LIMIT 1", listOf(intentId)).use { if(it.next()) it.string(0).substringBeforeLast('/') else null }
        val prefix = if(base != null) base else {
            val actorAndCounter = db.query("SELECT actor_epoch,next_counter FROM sync_local WHERE singleton=1").use { c -> check(c.next()); c.string(0) to c.long(1) }
            db.exec("UPDATE sync_local SET next_counter=next_counter+1 WHERE singleton=1")
            "${actorAndCounter.first}/${actorAndCounter.second}"
        }
        val version="$prefix/$part"
        db.exec("INSERT INTO sync_version_parts(intent_id,part,version_id) VALUES(?,?,?)",listOf(intentId,part,version))
        return version
    }

    internal fun seedHead(db: SqlDatabase, kind: String, key: String, payload: JsonElement): String {
        val id = allocateVersionPart(db, "seed:$kind:$key", "seed")
        insertHead(db, kind, key, id, SyncCodec.hash(payload), SyncCodec.canonical(payload))
        return id
    }
    fun addLifeEvent(db: SqlDatabase, recordId: String, eventId: String):Boolean { val old=db.query("SELECT record_id FROM sync_record_life WHERE event_id=?",listOf(eventId)).use { if(it.next()) it.string(0) else null }; if(old!=null && old!=recordId) throw ContentMismatch("生命事件ID属于其他记录"); return old==null && db.exec("INSERT INTO sync_record_life(record_id,event_id) VALUES(?,?)", listOf(recordId, eventId)) > 0 }

    fun purge(db: SqlDatabase, recordId: String, intentId: String, bundleId: String = intentId): String {
        replay(db, intentId)?.let { return it.resultKey }
        val purgeId = allocateVersionPart(db, intentId, "purge")
        db.exec("INSERT INTO sync_purges(record_id,purge_id) VALUES(?,?)", listOf(recordId, purgeId))
        clearPurgedSyncPayloads(db,recordId)
        db.exec("UPDATE sync_local SET content_epoch=content_epoch+1 WHERE singleton=1")
        val seq = nextCommit(db)
        db.exec("INSERT INTO sync_changes(commit_seq,kind,entity_key,bundle_id) VALUES(?,'purge',?,?)", listOf(seq, recordId, bundleId))
        db.exec("INSERT INTO sync_intents(intent_id,bundle_id,result_key,head_id) VALUES(?,?,?,?)", listOf(intentId, bundleId, recordId, purgeId))
        return recordId
    }

    fun addDraftTerminal(db: SqlDatabase, terminal: DraftTerminal) {
        val encoded=SyncCodec.encodeSet(terminal.consumedHeads)
        val old=db.query("SELECT draft_id,mode,target_record_id,consumed_heads,value_hash FROM sync_draft_terminals WHERE terminal_id=?",listOf(terminal.terminalId)).use { if(it.next()) listOf(it.string(0),it.string(1),it.string(2),it.string(3),it.string(4)) else null }
        val value=listOf(terminal.draftId,terminal.mode.name,terminal.targetRecordId,encoded,terminal.valueHash)
        if(old!=null && old!=value) throw ContentMismatch("草稿终结ID内容不一致")
        if(old==null) db.exec("INSERT INTO sync_draft_terminals(draft_id,terminal_id,mode,target_record_id,consumed_heads,value_hash) VALUES(?,?,?,?,?,?)", listOf(terminal.draftId, terminal.terminalId, terminal.mode.name, terminal.targetRecordId, encoded, terminal.valueHash))
    }

    fun snapshot(): SyncStateSnapshot = database.transaction { db ->
        val entities = entityKeys(db).map { readEntity(db, it.first, it.second) }
        val known = db.query("SELECT head_id,kind,entity_key,payload_hash FROM sync_known_heads ORDER BY head_id").use { c -> buildList { while (c.next()) add(KnownHead(c.string(0), c.string(1), c.string(2), c.string(3))) } }
        val life = db.query("SELECT record_id,event_id FROM sync_record_life ORDER BY record_id,event_id").use { c -> buildList { while (c.next()) add(RecordLifeEvent(c.string(0), c.string(1))) } }
        val purges = db.query("SELECT record_id,purge_id FROM sync_purges ORDER BY record_id").use { c -> buildList { while (c.next()) add(PurgeFence(c.string(0), c.string(1))) } }
        val terminals = db.query("SELECT draft_id,terminal_id,mode,target_record_id,consumed_heads,value_hash FROM sync_draft_terminals ORDER BY terminal_id").use { c -> buildList { while (c.next()) add(DraftTerminal(c.string(0), c.string(1), DraftTerminalMode.valueOf(c.string(2)), c.string(3), SyncCodec.decodeSet(c.string(4)), c.string(5))) } }
        val histories = db.query("SELECT table_name,sync_id,payload_hash,payload_json FROM sync_history ORDER BY sync_id").use { c -> buildList { while (c.next()) add(StableHistory(c.string(0), c.string(1), c.string(2), SyncCodec.parse(c.string(3)))) } }
        val aliases = db.query("SELECT origin_id,record_id,legacy_token,new_token FROM sync_source_alias ORDER BY origin_id,record_id,legacy_token").use { c -> buildList { while (c.next()) add(SourceAlias(c.string(0), c.string(1), c.string(2), c.string(3))) } }
        val id = identity(db); SyncStateSnapshot(id, entities, known, life, purges, terminals, histories, aliases, id.commitSeq)
    }

    fun merge(snapshot: SyncStateSnapshot): MergeResult = database.transaction { db -> merge(db, snapshot) }

    internal fun merge(db: SqlDatabase, snapshot: SyncStateSnapshot): MergeResult {
        snapshot.entities.forEach { entity ->
            validateGoalSyncKey(entity.kind, entity.key)
            if (entity.kind == GoalCodec.GOAL_KIND || entity.kind == GoalCodec.CONFIRMATION_KIND || entity.kind == GoalCodec.REVIEW_KIND) {
                entity.heads.forEach { GoalCodec.validatePayload(entity.kind, entity.key, it.payload) }
            }
        }
        val changed = linkedSetOf<Pair<String, String>>()
        val purged = linkedSetOf<String>()
        snapshot.purges.forEach { fence ->
            val existing = db.query("SELECT purge_id FROM sync_purges WHERE record_id=?", listOf(fence.recordId)).use { if (it.next()) it.string(0) else null }
            if (existing != null && existing != fence.purgeId) throw ContentMismatch("同一记录的purge fence不一致")
            if (existing == null) { db.exec("INSERT INTO sync_purges(record_id,purge_id) VALUES(?,?)", listOf(fence.recordId, fence.purgeId)); clearPurgedSyncPayloads(db,fence.recordId, invalidateStaging = false); purged += fence.recordId; db.exec("UPDATE sync_local SET content_epoch=content_epoch+1 WHERE singleton=1") }
        }
        if (purged.isNotEmpty()) db.exec("DELETE FROM sync_exports")
        validateDraftIdentities(db,snapshot.entities,snapshot.draftTerminals,snapshot.purges.mapTo(hashSetOf()){it.recordId})
        snapshot.draftTerminals.filterNot { isPurged(db,it.targetRecordId) }.forEach { addDraftTerminal(db,it) }
        snapshot.entities.forEach { incoming ->
            if (incoming.kind == "record" && isPurged(db, incoming.key)) return@forEach
            val before = readEntity(db, incoming.kind, incoming.key)
            incoming.heads.forEach { h ->
                val purgedDraft = incoming.kind=="draft-slot" && h.payload.jsonObject["targetRecordId"]?.jsonPrimitive?.content?.let { isPurged(db,it) }==true
                if(!purgedDraft) {
                    insertHead(db, incoming.kind, incoming.key, h.id, h.payloadHash, SyncCodec.canonical(h.payload))
                    if(incoming.kind=="draft-slot" && draftHeadConsumed(db,h)) retireKnown(db,incoming.kind,incoming.key,h.id)
                }
            }
            incoming.retired.forEach { retireKnown(db, incoming.kind, incoming.key, it) }
            val after = readEntity(db, incoming.kind, incoming.key)
            if (after != before) changed += incoming.kind to incoming.key
        }
        snapshot.knownHeads.forEach { known ->
            validateGoalSyncKey(known.kind, known.key)
            assertKnown(db, known.id, known.kind, known.key, known.payloadHash)
        }
        snapshot.lifeEvents.forEach { if(addLifeEvent(db, it.recordId, it.eventId)) changed += "record" to it.recordId }
        snapshot.histories.forEach { h -> insertHistory(db, h) }
        snapshot.sourceAliases.forEach { a -> insertAlias(db,a) }
        if (changed.isNotEmpty() || purged.isNotEmpty()) { val seq = nextCommit(db); changed.forEach { db.exec("INSERT INTO sync_changes(commit_seq,kind,entity_key,bundle_id) VALUES(?,?,?,'import')", listOf(seq, it.first, it.second)) }; purged.forEach { db.exec("INSERT INTO sync_changes(commit_seq,kind,entity_key,bundle_id) VALUES(?,'purge',?,'import')", listOf(seq, it)) } }
        projectCore(db,changed,purged)
        projectHistories(db,snapshot.histories,purged)
        ReportRepository(database).projectMerged(db, changed, purged)
        GoalRepository(database).projectMerged(db, changed, purged)
        return MergeResult(changed.isNotEmpty() || purged.isNotEmpty(), changed, purged)
    }

    private fun draftHeadConsumed(db:SqlDatabase,head:SyncHead):Boolean { val o=head.payload.jsonObject; if(o["mode"]!=null) return false; val draftId=o["id"]?.jsonPrimitive?.content ?: return false; return db.query("SELECT consumed_heads,value_hash FROM sync_draft_terminals WHERE draft_id=?",listOf(draftId)).use { c -> var consumed=false; while(c.next()) consumed=consumed || head.id in SyncCodec.decodeSet(c.string(0)) || head.payloadHash==c.string(1); consumed } }
    private fun projectHistories(db:SqlDatabase,histories:List<StableHistory>,purged:Set<String>) {
        histories.forEach { h ->
            val table=if(h.table=="revision") "revisions" else if(h.table=="report_answer_history") "report_answer_history" else return@forEach
            if(db.query("SELECT 1 FROM $table WHERE sync_id=?",listOf(h.syncId)).use{it.next()}) return@forEach
            val o=h.payload.jsonObject
            if(h.table=="revision") {
                val recordId=o.getValue("recordId").jsonPrimitive.content; if(recordId in purged || isPurged(db,recordId) || db.query("SELECT 1 FROM records WHERE id=?",listOf(recordId)).use{!it.next()}) return@forEach
                val before=o["before"]?.jsonObject; val after=o["after"]?.jsonObject
                fun v(obj:kotlinx.serialization.json.JsonObject?,nested:String,flat:String)=obj?.get(nested)?.jsonPrimitive?.content ?: o.getValue(flat).jsonPrimitive.content
                val args=listOf(recordId,v(before,"text","beforeBody"),v(before,"local","beforeLocal"),v(before,"zone","beforeZone"),v(before,"offset","beforeOffset"),v(before,"instant","beforeEpoch").toLong(),v(after,"text","afterBody"),v(after,"local","afterLocal"),v(after,"zone","afterZone"),v(after,"offset","afterOffset"),v(after,"instant","afterEpoch").toLong(),o.getValue("modified").jsonPrimitive.content,h.syncId,o["parentHeads"]?.jsonPrimitive?.content.orEmpty())
                db.exec("INSERT INTO revisions(record_id,before_body,before_local,before_zone,before_offset,before_epoch,after_body,after_local,after_zone,after_offset,after_epoch,modified,sync_id,parent_heads) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",args)
            } else db.exec("INSERT INTO report_answer_history(date,question_id,answer,skipped,changed_at,sync_id,parent_heads) VALUES(?,?,?,?,?,?,?)",listOf(o.getValue("date").jsonPrimitive.content,o.getValue("questionId").jsonPrimitive.content,o.getValue("answer").jsonPrimitive.content,if(o.getValue("skipped").jsonPrimitive.content.toBoolean())1 else 0,o.getValue("changedAt").jsonPrimitive.content,h.syncId,o["parentHeads"]?.jsonPrimitive?.content.orEmpty()))
        }
    }
    private fun validateDraftIdentities(db:SqlDatabase,incoming:List<EntityState>,terminals:List<DraftTerminal>,purged:Set<String>) {
        val identities=linkedMapOf<String,Pair<String,String?>>()
        fun accept(draftId:String,target:String,record:String?) { if(target in purged || isPurged(db,target)) return; val old=identities[draftId]; if(old!=null && (old.first!=target || old.second!=null && record!=null && old.second!=record)) throw DraftIdentityMismatch(draftId); identities[draftId]=target to (old?.second ?: record) }
        fun accept(payload:JsonElement) { val o=payload.jsonObject; val draftId=o["draftId"]?.jsonPrimitive?.content ?: o["id"]?.jsonPrimitive?.content ?: return; val target=o["targetRecordId"]?.jsonPrimitive?.content ?: return; accept(draftId,target,o["recordId"]?.jsonPrimitive?.content) }
        entityKeys(db,"draft-slot").forEach { (kind,key) -> readEntity(db,kind,key).heads.forEach { accept(it.payload) } }
        db.query("SELECT draft_id,target_record_id FROM sync_draft_terminals").use { c -> while(c.next()) accept(c.string(0),c.string(1),null) }
        incoming.filter { it.kind=="draft-slot" }.forEach { it.heads.forEach { h -> accept(h.payload) } }
        terminals.forEach { accept(it.draftId,it.targetRecordId,null) }
    }
    private fun projectCore(db:SqlDatabase,changed:Set<Pair<String,String>>,purged:Set<String>) {
        purged.forEach { id ->
            db.exec("DELETE FROM sync_history WHERE table_name='revision' AND sync_id IN (SELECT sync_id FROM revisions WHERE record_id=?)",listOf(id))
            db.exec("DELETE FROM drafts WHERE record_id=? OR target_record_id=?",listOf(id,id)); db.exec("DELETE FROM records WHERE id=?",listOf(id)); db.exec("DELETE FROM revisions WHERE record_id=?",listOf(id))
        }
        changed.forEach { (kind,key) ->
            val state=readEntity(db,kind,key); if(state.unresolved) return@forEach
            val payload=state.heads.firstOrNull()?.payload?.jsonObject ?: return@forEach
            if(kind=="record") {
                fun s(name:String)=payload.getValue(name).jsonPrimitive.content
                val args=listOf(s("text"),s("local"),s("zone"),s("offset"),s("instant").toLong(),s("created"),payload["modified"]?.jsonPrimitive?.content,payload["deleted"]?.jsonPrimitive?.content,key)
                if(db.exec("UPDATE records SET body=?,local=?,zone=?,offset=?,epoch=?,created=?,modified=?,deleted=? WHERE id=?",args)==0) db.exec("INSERT INTO records(body,local,zone,offset,epoch,created,modified,deleted,id) VALUES(?,?,?,?,?,?,?,?,?)",args)
            } else if(kind=="draft-slot") {
                if(payload["mode"]!=null) { if(key=="new") db.exec("DELETE FROM drafts WHERE new_slot=1") else db.exec("DELETE FROM drafts WHERE record_id=?",listOf(key.removePrefix("edit/"))); return@forEach }
                fun s(name:String)=payload.getValue(name).jsonPrimitive.content
                val recordId=payload["recordId"]?.jsonPrimitive?.content; val id=s("id"); val target=s("targetRecordId")
                if(isPurged(db,target)) return@forEach
                val base=payload["baseRecordHeads"]?.jsonPrimitive?.content.orEmpty(); val baseSnapshot=payload["baseRecordSnapshot"]?.let(SyncCodec::canonical)
                val args=listOf(recordId,if(recordId==null)1 else null,s("text"),s("local"),s("zone"),payload["offset"]?.jsonPrimitive?.content,target,base,baseSnapshot,id)
                if(db.exec("UPDATE drafts SET record_id=?,new_slot=?,body=?,local=?,zone=?,offset=?,target_record_id=?,base_record_heads=?,base_record_snapshot=? WHERE id=?",args)==0) db.exec("INSERT INTO drafts(record_id,new_slot,body,local,zone,offset,target_record_id,base_record_heads,base_record_snapshot,id) VALUES(?,?,?,?,?,?,?,?,?,?)",args)
            }
        }
    }
    private fun clearPurgedSyncPayloads(db:SqlDatabase,recordId:String,invalidateStaging:Boolean=true) {
        GoalRepository(database).invalidatePendingForRecord(db, recordId)
        val affected=entityKeys(db).filter { (kind,key) -> (kind=="record" && key==recordId) || (kind=="draft-slot" && (key=="edit/$recordId" || readEntity(db,kind,key).heads.any { it.payload.jsonObject["targetRecordId"]?.jsonPrimitive?.content==recordId })) }
        affected.forEach { (kind,key) -> db.exec("DELETE FROM sync_heads WHERE kind=? AND entity_key=?",listOf(kind,key)); db.exec("DELETE FROM sync_retired WHERE kind=? AND entity_key=?",listOf(kind,key)) }
        val revisionIds=db.query("SELECT sync_id,payload_json FROM sync_history WHERE table_name='revision'").use { c -> buildList { while(c.next()) if(SyncCodec.parse(c.string(1)).jsonObject["recordId"]?.jsonPrimitive?.content==recordId) add(c.string(0)) } }
        revisionIds.forEach { db.exec("DELETE FROM sync_history WHERE sync_id=?",listOf(it)) }
        db.exec("DELETE FROM sync_draft_terminals WHERE target_record_id=?",listOf(recordId)); if(invalidateStaging) { db.exec("DELETE FROM sync_exports"); db.exec("DELETE FROM sync_incoming") }
    }
    private fun insertAlias(db:SqlDatabase,a:SourceAlias) { val old=db.query("SELECT new_token FROM sync_source_alias WHERE origin_id=? AND record_id=? AND legacy_token=?",listOf(a.originId,a.recordId,a.legacyToken)).use { if(it.next()) it.string(0) else null }; if(old!=null && old!=a.newToken) throw ContentMismatch("来源别名内容不一致"); if(old==null) db.exec("INSERT INTO sync_source_alias(origin_id,record_id,legacy_token,new_token) VALUES(?,?,?,?)",listOf(a.originId,a.recordId,a.legacyToken,a.newToken)) }
    private fun insertHistory(db: SqlDatabase, h: StableHistory) { val old = db.query("SELECT payload_hash FROM sync_history WHERE sync_id=?", listOf(h.syncId)).use { if (it.next()) it.string(0) else null }; if (old != null && old != h.payloadHash) throw ContentMismatch("历史ID内容不一致"); require(SyncCodec.hash(h.payload) == h.payloadHash) { "历史payload hash不匹配" }; if (old == null) db.exec("INSERT INTO sync_history(table_name,sync_id,payload_hash,payload_json) VALUES(?,?,?,?)", listOf(h.table, h.syncId, h.payloadHash, SyncCodec.canonical(h.payload))) }
    private fun commitForBundle(db:SqlDatabase,bundleId:String):Long = db.query("SELECT commit_seq FROM sync_changes WHERE bundle_id=? ORDER BY commit_seq DESC LIMIT 1",listOf(bundleId)).use { if(it.next()) it.long(0) else nextCommit(db) }
    private fun insertHead(db: SqlDatabase, kind: String, key: String, id: String, hash: String, json: String) { require(SyncCodec.sha256(json.toByteArray(Charsets.UTF_8)) == hash) { "payload hash不匹配" }; assertKnown(db, id, kind, key, hash); db.exec("INSERT OR IGNORE INTO sync_heads(kind,entity_key,head_id,payload_hash,payload_json) VALUES(?,?,?,?,?)", listOf(kind, key, id, hash, json)) }
    private fun assertKnown(db: SqlDatabase, id: String, kind: String, key: String, hash: String) { val old = db.query("SELECT kind,entity_key,payload_hash FROM sync_known_heads WHERE head_id=?", listOf(id)).use { if (it.next()) Triple(it.string(0),it.string(1),it.string(2)) else null }; if (old != null && old != Triple(kind,key,hash)) throw ContentMismatch("版本ID内容不一致"); if (old == null) db.exec("INSERT INTO sync_known_heads(head_id,kind,entity_key,payload_hash) VALUES(?,?,?,?)", listOf(id,kind,key,hash)) }
    private fun retireKnown(db: SqlDatabase, kind: String, key: String, id: String) { val known = db.query("SELECT kind,entity_key FROM sync_known_heads WHERE head_id=?", listOf(id)).use { if (it.next()) it.string(0) to it.string(1) else null }; require(known == null || known == kind to key) { "retired版本属于其他实体" }; db.exec("INSERT OR IGNORE INTO sync_retired(kind,entity_key,head_id) VALUES(?,?,?)", listOf(kind,key,id)); db.exec("DELETE FROM sync_heads WHERE head_id=?", listOf(id)) }
    private fun replay(db: SqlDatabase, intent: String): LocalWriteResult? = db.query("SELECT result_key,head_id FROM sync_intents WHERE intent_id=?", listOf(intent)).use { if (!it.next()) null else LocalWriteResult(it.string(0), it.string(0), false).copy(headId=it.optional("head_id") ?: "") }
    private fun nextCommit(db: SqlDatabase): Long { db.exec("UPDATE sync_local SET commit_seq=commit_seq+1 WHERE singleton=1"); return identity(db).commitSeq }
    private fun identity(db: SqlDatabase): SyncIdentity = db.query("SELECT space_id,origin_id,actor_epoch,database_id,next_counter,commit_seq,content_epoch FROM sync_local WHERE singleton=1").use { c -> check(c.next()); SyncIdentity(c.optional("space_id"),c.string(1),c.string(2),c.string(3),c.long(4),c.long(5),c.long(6)) }
    private fun entityKeys(db: SqlDatabase, kind: String? = null): List<Pair<String,String>> { val sql = if (kind == null) "SELECT kind,entity_key FROM sync_heads UNION SELECT kind,entity_key FROM sync_retired ORDER BY 1,2" else "SELECT kind,entity_key FROM sync_heads WHERE kind=? UNION SELECT kind,entity_key FROM sync_retired WHERE kind=? ORDER BY 1,2"; val args = if (kind == null) emptyList() else listOf(kind,kind); return db.query(sql,args).use { c -> buildList { while(c.next()) add(c.string(0) to c.string(1)) } } }
}
