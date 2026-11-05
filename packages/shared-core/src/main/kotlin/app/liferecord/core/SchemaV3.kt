package app.liferecord.core

import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object SchemaV3 {
    fun install(database: SqlDatabase) {
        createTables(database)
        addProjectionColumns(database)
        val identity = ensureIdentity(database)
        val sync = SyncRepository(database)
        seedLegacyRecords(database, sync)
        seedLegacyDrafts(database, sync)
        seedLegacyRevisions(database, identity.originId)
        ReportV3Migration.migrate(database, identity.originId, sync)
    }
    private fun createTables(db: SqlDatabase) {
        db.exec("CREATE TABLE sync_local(singleton INTEGER PRIMARY KEY CHECK(singleton=1),space_id TEXT,origin_id TEXT NOT NULL,actor_epoch TEXT NOT NULL,database_id TEXT NOT NULL,next_counter INTEGER NOT NULL,commit_seq INTEGER NOT NULL,content_epoch INTEGER NOT NULL)")
        db.exec("CREATE TABLE sync_changes(commit_seq INTEGER NOT NULL,kind TEXT NOT NULL,entity_key TEXT NOT NULL,bundle_id TEXT NOT NULL,PRIMARY KEY(commit_seq,kind,entity_key))")
        db.exec("CREATE TABLE sync_heads(kind TEXT NOT NULL,entity_key TEXT NOT NULL,head_id TEXT PRIMARY KEY,payload_hash TEXT NOT NULL,payload_json TEXT NOT NULL)")
        db.exec("CREATE INDEX sync_heads_entity ON sync_heads(kind,entity_key)")
        db.exec("CREATE TABLE sync_retired(kind TEXT NOT NULL,entity_key TEXT NOT NULL,head_id TEXT NOT NULL,PRIMARY KEY(kind,entity_key,head_id))")
        db.exec("CREATE TABLE sync_known_heads(head_id TEXT PRIMARY KEY,kind TEXT NOT NULL,entity_key TEXT NOT NULL,payload_hash TEXT NOT NULL)")
        db.exec("CREATE TABLE sync_record_life(record_id TEXT NOT NULL,event_id TEXT PRIMARY KEY)")
        db.exec("CREATE TABLE sync_purges(record_id TEXT PRIMARY KEY,purge_id TEXT NOT NULL UNIQUE)")
        db.exec("CREATE TABLE sync_draft_terminals(draft_id TEXT NOT NULL,terminal_id TEXT PRIMARY KEY,mode TEXT NOT NULL,target_record_id TEXT NOT NULL,consumed_heads TEXT NOT NULL,value_hash TEXT NOT NULL)")
        db.exec("CREATE TABLE sync_peer(peer_id TEXT PRIMARY KEY,peer_database_id TEXT,acked_local_seq INTEGER NOT NULL DEFAULT 0,received_remote_seq INTEGER NOT NULL DEFAULT 0,send_consent INTEGER NOT NULL DEFAULT 0,receive_consent INTEGER NOT NULL DEFAULT 0)")
        db.exec("CREATE TABLE sync_exports(export_id TEXT PRIMARY KEY,peer_id TEXT NOT NULL,base_seq INTEGER NOT NULL,through_seq INTEGER NOT NULL,content_epoch INTEGER NOT NULL,manifest_hash TEXT NOT NULL,manifest_json TEXT NOT NULL,state TEXT NOT NULL)")
        db.exec("CREATE TABLE sync_export_parts(export_id TEXT NOT NULL,part_no INTEGER NOT NULL,kind TEXT NOT NULL,entity_key TEXT NOT NULL,record_refs TEXT NOT NULL,payload_json TEXT NOT NULL,part_hash TEXT NOT NULL,PRIMARY KEY(export_id,part_no),FOREIGN KEY(export_id) REFERENCES sync_exports(export_id) ON DELETE CASCADE)")
        db.exec("CREATE TABLE sync_incoming(export_id TEXT PRIMARY KEY,peer_id TEXT NOT NULL,peer_database_id TEXT NOT NULL,base_seq INTEGER NOT NULL,through_seq INTEGER NOT NULL,content_epoch INTEGER NOT NULL,local_epoch INTEGER NOT NULL,manifest_hash TEXT NOT NULL,manifest_json TEXT NOT NULL,state TEXT NOT NULL)")
        db.exec("CREATE TABLE sync_incoming_parts(export_id TEXT NOT NULL,part_no INTEGER NOT NULL,kind TEXT NOT NULL,entity_key TEXT NOT NULL,record_refs TEXT NOT NULL,payload_json TEXT NOT NULL,part_hash TEXT NOT NULL,PRIMARY KEY(export_id,part_no),FOREIGN KEY(export_id) REFERENCES sync_incoming(export_id) ON DELETE CASCADE)")
        db.exec("CREATE TABLE sync_version_parts(intent_id TEXT NOT NULL,part TEXT NOT NULL,version_id TEXT NOT NULL UNIQUE,PRIMARY KEY(intent_id,part))")
        db.exec("CREATE TABLE sync_source_alias(origin_id TEXT NOT NULL,record_id TEXT NOT NULL,legacy_token TEXT NOT NULL,new_token TEXT NOT NULL,PRIMARY KEY(origin_id,record_id,legacy_token))")
        db.exec("CREATE TABLE sync_intents(intent_id TEXT PRIMARY KEY,bundle_id TEXT NOT NULL,result_key TEXT NOT NULL,head_id TEXT)")
        db.exec("CREATE TABLE sync_applied_exports(peer_database_id TEXT NOT NULL,export_id TEXT NOT NULL,manifest_hash TEXT NOT NULL,through_seq INTEGER NOT NULL,PRIMARY KEY(peer_database_id,export_id))")
        db.exec("CREATE TABLE sync_history(table_name TEXT NOT NULL,sync_id TEXT PRIMARY KEY,payload_hash TEXT NOT NULL,payload_json TEXT NOT NULL)")
    }

    private fun addProjectionColumns(db: SqlDatabase) {
        db.exec("ALTER TABLE drafts ADD COLUMN target_record_id TEXT")
        db.exec("ALTER TABLE drafts ADD COLUMN base_record_heads TEXT NOT NULL DEFAULT ''")
        db.exec("ALTER TABLE drafts ADD COLUMN base_record_snapshot TEXT")
        db.exec("ALTER TABLE revisions ADD COLUMN sync_id TEXT")
        db.exec("ALTER TABLE revisions ADD COLUMN parent_heads TEXT NOT NULL DEFAULT ''")
        db.exec("CREATE UNIQUE INDEX revisions_sync_id ON revisions(sync_id)")
        db.exec("ALTER TABLE report_answer_history ADD COLUMN sync_id TEXT")
        db.exec("ALTER TABLE report_answer_history ADD COLUMN parent_heads TEXT NOT NULL DEFAULT ''")
        db.exec("CREATE UNIQUE INDEX report_answer_history_sync_id ON report_answer_history(sync_id)")
        db.exec("UPDATE drafts SET target_record_id=COALESCE(record_id,id) WHERE target_record_id IS NULL")
    }

    private fun ensureIdentity(db: SqlDatabase): SyncIdentity {
        val origin = UUID.randomUUID().toString()
        val actor = UUID.randomUUID().toString()
        val database = UUID.randomUUID().toString()
        db.exec("INSERT INTO sync_local(singleton,space_id,origin_id,actor_epoch,database_id,next_counter,commit_seq,content_epoch) VALUES(1,NULL,?,?,?,1,0,0)", listOf(origin, actor, database))
        return SyncIdentity(null, origin, actor, database, 1, 0, 0)
    }

    private fun seedLegacyRecords(db: SqlDatabase, sync: SyncRepository) {
        db.query("SELECT id,body,local,zone,offset,epoch,created,modified,deleted FROM records ORDER BY id").use { c ->
            while (c.next()) {
                val id = c.string("id")
                val payload = buildJsonObject {
                    put("id", id); put("text", c.string("body")); put("local", c.string("local")); put("zone", c.string("zone")); put("offset", c.string("offset")); put("instant", c.long("epoch")); put("created", c.string("created"))
                    c.optional("modified")?.let { put("modified", it) }; c.optional("deleted")?.let { put("deleted", it) }
                }
                val head = sync.seedHead(db, "record", id, payload)
                sync.addLifeEvent(db, id, "$head/life-seed")
            }
        }
    }

    private fun seedLegacyDrafts(db: SqlDatabase, sync: SyncRepository) {
        db.query("SELECT id,record_id,body,local,zone,offset,target_record_id,base_record_heads,base_record_snapshot FROM drafts ORDER BY id").use { c ->
            while (c.next()) {
                val id = c.string("id"); val recordId = c.optional("record_id"); val target = c.string("target_record_id")
                val base = if (recordId == null) emptySet() else sync.currentHeadIds(db, "record", recordId)
                val baseSnapshot = recordId?.let { rid -> db.query("SELECT id,body,local,zone,offset,epoch,created,modified,deleted FROM records WHERE id=?",listOf(rid)).use { r -> if(!r.next()) null else buildJsonObject { put("id",r.string(0));put("text",r.string(1));put("local",r.string(2));put("zone",r.string(3));put("offset",r.string(4));put("instant",r.long(5));put("created",r.string(6));r.optional("modified")?.let{put("modified",it)};r.optional("deleted")?.let{put("deleted",it)} } } }
                db.exec("UPDATE drafts SET base_record_heads=?,base_record_snapshot=? WHERE id=?", listOf(SyncCodec.encodeSet(base),baseSnapshot?.let(SyncCodec::canonical), id))
                val payload = buildJsonObject {
                    put("id", id); recordId?.let { put("recordId", it) }; put("targetRecordId", target); put("text", c.string("body")); put("local", c.string("local")); put("zone", c.string("zone")); c.optional("offset")?.let { put("offset", it) }; put("baseRecordHeads", SyncCodec.encodeSet(base)); baseSnapshot?.let { put("baseRecordSnapshot",it) }
                }
                sync.seedHead(db, "draft-slot", if (recordId == null) "new" else "edit/$recordId", payload)
            }
        }
    }
    private fun seedLegacyRevisions(db:SqlDatabase,originId:String) {
        db.query("SELECT id,record_id,before_body,before_local,before_zone,before_offset,before_epoch,after_body,after_local,after_zone,after_offset,after_epoch,modified FROM revisions ORDER BY id").use { c ->
            while(c.next()) {
                val localId=c.long(0); val syncId="$originId/legacy-revision/$localId"
                db.exec("UPDATE revisions SET sync_id=? WHERE id=?",listOf(syncId,localId))
                val payload=buildJsonObject { put("recordId",c.string(1)); put("beforeBody",c.string(2)); put("beforeLocal",c.string(3)); put("beforeZone",c.string(4)); put("beforeOffset",c.string(5)); put("beforeEpoch",c.long(6)); put("afterBody",c.string(7)); put("afterLocal",c.string(8)); put("afterZone",c.string(9)); put("afterOffset",c.string(10)); put("afterEpoch",c.long(11)); put("modified",c.string(12)); put("parentHeads","") }
                db.exec("INSERT INTO sync_history(table_name,sync_id,payload_hash,payload_json) VALUES('revision',?,?,?)",listOf(syncId,SyncCodec.hash(payload),SyncCodec.canonical(payload)))
            }
        }
    }
}
