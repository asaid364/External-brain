package app.liferecord.core

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID

class SyncExchange(private val database: SqlDatabase) {
    private val repository = SyncRepository(database)

    fun bindSpace(spaceId: String) {
        require(spaceId.isNotBlank()) { "spaceId不能为空" }
        database.transaction { db ->
            val current = identity(db).spaceId
            if (current != null && current != spaceId) fail(SyncErrorCode.SPACE_MISMATCH, "同步空间身份不一致")
            if (current == null) db.exec("UPDATE sync_local SET space_id=? WHERE singleton=1", listOf(spaceId))
        }
    }

    fun setConsent(peerId: String, send: Boolean, receive: Boolean) = database.transaction { db ->
        val changed = db.exec(
            "UPDATE sync_peer SET send_consent=?,receive_consent=? WHERE peer_id=?",
            listOf(send.flag(), receive.flag(), peerId),
        )
        if (changed == 0) db.exec(
            "INSERT INTO sync_peer(peer_id,send_consent,receive_consent) VALUES(?,?,?)",
            listOf(peerId, send.flag(), receive.flag()),
        )
    }

    fun status(peerId: String): SyncStatus = database.transaction { db ->
        val id = identity(db)
        val peer = peer(db, peerId)
        val countsVisible = peer?.sendConsent == true && peer.receiveConsent
        SyncStatus(
            spaceId = id.spaceId,
            localDatabaseId = id.databaseId,
            peerDatabaseId = peer?.databaseId,
            localCommitSeq = id.commitSeq,
            ackedLocalSeq = peer?.ackedLocalSeq ?: 0,
            receivedRemoteSeq = peer?.receivedRemoteSeq ?: 0,
            contentEpoch = id.contentEpoch,
            sendConsent = peer?.sendConsent ?: false,
            receiveConsent = peer?.receiveConsent ?: false,
            entityCount = if (countsVisible) scalar(db, "SELECT COUNT(*) FROM (SELECT kind,entity_key FROM sync_heads UNION SELECT kind,entity_key FROM sync_retired)") else null,
            purgeCount = if (countsVisible) scalar(db, "SELECT COUNT(*) FROM sync_purges") else null,
            counts = if (countsVisible) SyncCategoryCounts(
                records = scalar(db, "SELECT COUNT(*) FROM records"),
                drafts = scalar(db, "SELECT COUNT(*) FROM drafts"),
                reports = scalar(db, "SELECT COUNT(*) FROM report_days"),
                goals = scalar(db, "SELECT COUNT(DISTINCT entity_key) FROM sync_heads WHERE kind='goal'"),
                reviews = scalar(db, "SELECT COUNT(DISTINCT entity_key) FROM sync_heads WHERE kind='goal-review'"),
            ) else null,
        )
    }


    fun prepare(peerId: String, peerDatabaseId: String, baseSeq: Long, full: Boolean = false): ExportManifest = database.transaction { db ->
        val id = identity(db)
        val space = id.spaceId ?: fail(SyncErrorCode.SPACE_MISMATCH, "尚未绑定同步空间")
        val peer = requirePeer(db, peerId, peerDatabaseId, requireConsent = true)
        if (baseSeq < 0 || baseSeq > id.commitSeq) fail(SyncErrorCode.CURSOR_GAP, "导出游标超出本机提交水位")
        if (!full && baseSeq != peer.ackedLocalSeq) fail(SyncErrorCode.CURSOR_GAP, "导出游标与持久ACK不连续")
        val exportId = UUID.randomUUID().toString()
        val through = id.commitSeq
        db.exec(
            "INSERT INTO sync_exports(export_id,peer_id,base_seq,through_seq,content_epoch,manifest_hash,manifest_json,state) VALUES(?,?,?,?,?,'','{}','STAGING')",
            listOf(exportId, peerId, baseSeq, through, id.contentEpoch),
        )
        val writer = PartWriter(db, exportId)
        stageSnapshot(db, writer, baseSeq, through, full)
        writer.finish()
        val hashes = writer.hashes.toList()
        val unsigned = ExportManifest(
            spaceId = space,
            senderActor = id.actorEpoch,
            senderDatabaseId = id.databaseId,
            receiverDatabaseId = peerDatabaseId,
            exportId = exportId,
            baseSeq = baseSeq,
            throughSeq = through,
            full = full,
            partCount = hashes.size,
            partHashes = hashes,
            partsSha256 = SyncWire.partsDigest(hashes),
            contentEpoch = id.contentEpoch,
            manifestHash = "",
        )
        val manifest = unsigned.copy(manifestHash = SyncWire.manifestDigest(unsigned))
        db.exec(
            "UPDATE sync_exports SET manifest_hash=?,manifest_json=?,state='READY' WHERE export_id=?",
            listOf(manifest.manifestHash, SyncWire.encodeManifest(manifest), exportId),
        )
        manifest
    }

    fun exportPart(exportId: String, partNo: Int): ExportPart = database.transaction { db ->
        val staged = db.query(
            "SELECT e.content_epoch,e.state,p.payload_json,p.part_hash FROM sync_exports e LEFT JOIN sync_export_parts p ON p.export_id=e.export_id AND p.part_no=? WHERE e.export_id=?",
            listOf(partNo, exportId),
        ).use { c ->
            if (!c.next()) fail(SyncErrorCode.NOT_FOUND, "导出不存在")
            StagedPart(c.long(0), c.string(1), c.optional("payload_json"), c.optional("part_hash"))
        }
        if (staged.epoch != identity(db).contentEpoch) fail(SyncErrorCode.EXPORT_INVALIDATED, "导出已被本机永久删除失效")
        if (staged.state != "READY" || staged.data == null || staged.hash == null) fail(SyncErrorCode.NOT_FOUND, "导出分片不存在")
        ExportPart(exportId, partNo, staged.data, staged.hash)
    }

    fun beginIncoming(peerId: String, manifest: ExportManifest): IncomingDisposition = database.transaction { db ->
        validateManifest(manifest)
        val id = identity(db)
        if (manifest.receiverDatabaseId != id.databaseId) fail(SyncErrorCode.IDENTITY_MISMATCH, "导出接收数据库身份不匹配")
        if (manifest.spaceId != id.spaceId) fail(SyncErrorCode.SPACE_MISMATCH, "同步空间身份不一致")
        val peer = requirePeer(db, peerId, manifest.senderDatabaseId, requireConsent = true)
        val applied = db.query(
            "SELECT manifest_hash,through_seq FROM sync_applied_exports WHERE peer_database_id=? AND export_id=?",
            listOf(manifest.senderDatabaseId, manifest.exportId),
        ).use { c -> if (c.next()) c.string(0) to c.long(1) else null }
        if (applied != null) {
            if (applied.first != manifest.manifestHash) fail(SyncErrorCode.CONTENT_MISMATCH, "重复exportId的manifest不同")
            return@transaction IncomingDisposition(IncomingState.APPLIED, Ack(manifest.exportId, applied.second, manifest.manifestHash))
        }
        if (!manifest.full && manifest.baseSeq != peer.receivedRemoteSeq) fail(SyncErrorCode.CURSOR_GAP, "接收游标不连续")
        val existing = db.query("SELECT manifest_hash FROM sync_incoming WHERE export_id=?", listOf(manifest.exportId)).use { c -> if (c.next()) c.string(0) else null }
        if (existing != null) {
            if (existing != manifest.manifestHash) fail(SyncErrorCode.CONTENT_MISMATCH, "重复exportId的manifest不同")
            return@transaction IncomingDisposition(IncomingState.READY)
        }
        db.exec(
            "INSERT INTO sync_incoming(export_id,peer_id,peer_database_id,base_seq,through_seq,content_epoch,local_epoch,manifest_hash,manifest_json,state) VALUES(?,?,?,?,?,?,?,?,?,'RECEIVING')",
            listOf(manifest.exportId, peerId, manifest.senderDatabaseId, manifest.baseSeq, manifest.throughSeq, manifest.contentEpoch, id.contentEpoch, manifest.manifestHash, SyncWire.encodeManifest(manifest)),
        )
        IncomingDisposition(IncomingState.READY)
    }

    fun receivePart(exportId: String, part: ExportPart) = database.transaction { db ->
        if (part.exportId != exportId || part.partNo < 0) fail(SyncErrorCode.CONTENT_MISMATCH, "分片身份无效")
        val manifest = incomingManifest(db, exportId)
        if (part.partNo >= manifest.partCount) fail(SyncErrorCode.CONTENT_MISMATCH, "分片序号超出manifest")
        val bytes = try { Base64.getDecoder().decode(part.dataBase64) } catch (_: IllegalArgumentException) {
            fail(SyncErrorCode.CONTENT_MISMATCH, "分片Base64无效")
        }
        if (bytes.size > SYNC_PART_BYTES) fail(SyncErrorCode.CONTENT_MISMATCH, "分片超过协议大小")
        val hash = SyncCodec.sha256(bytes)
        if (hash != part.partHash || hash != manifest.partHashes[part.partNo]) fail(SyncErrorCode.CONTENT_MISMATCH, "分片hash不匹配")
        val old = db.query("SELECT part_hash FROM sync_incoming_parts WHERE export_id=? AND part_no=?", listOf(exportId, part.partNo)).use { c -> if (c.next()) c.string(0) else null }
        if (old != null && old != hash) fail(SyncErrorCode.CONTENT_MISMATCH, "重复分片内容不同")
        if (old == null) db.exec(
            "INSERT INTO sync_incoming_parts(export_id,part_no,kind,entity_key,record_refs,payload_json,part_hash) VALUES(?,?,'stream','','',?,?)",
            listOf(exportId, part.partNo, part.dataBase64, hash),
        )
    }

    fun commitIncoming(peerId: String, exportId: String): Ack = database.transaction { db ->
        val paired = peer(db, peerId) ?: fail(SyncErrorCode.IDENTITY_MISMATCH, "peer尚未配对")
        val senderDatabaseId = paired.databaseId
        if (senderDatabaseId != null) {
            requirePeer(db, peerId, senderDatabaseId, requireConsent = true)
            val applied = db.query(
                "SELECT through_seq,manifest_hash FROM sync_applied_exports WHERE peer_database_id=? AND export_id=?",
                listOf(senderDatabaseId, exportId),
            ).use { c -> if (c.next()) Ack(exportId, c.long(0), c.string(1)) else null }
            if (applied != null) return@transaction applied
        }
        val manifest = incomingManifest(db, exportId)
        val incoming = db.query("SELECT peer_id,local_epoch FROM sync_incoming WHERE export_id=?", listOf(exportId)).use { c ->
            check(c.next()); c.string(0) to c.long(1)
        }
        if (incoming.first != peerId) fail(SyncErrorCode.IDENTITY_MISMATCH, "接收会话不属于该peer")
        requirePeer(db, peerId, manifest.senderDatabaseId, requireConsent = true)
        if (incoming.second != identity(db).contentEpoch) fail(SyncErrorCode.EXPORT_INVALIDATED, "接收期间本机永久删除使会话失效")
        val count = scalar(db, "SELECT COUNT(*) FROM sync_incoming_parts WHERE export_id=?", listOf(exportId)).toInt()
        if (count != manifest.partCount) fail(SyncErrorCode.INCOMPLETE_EXPORT, "导出分片不完整")
        val actualHashes = db.query("SELECT part_no,part_hash FROM sync_incoming_parts WHERE export_id=? ORDER BY part_no", listOf(exportId)).use { c ->
            buildList { var expected = 0; while (c.next()) { if (c.long(0).toInt() != expected++) fail(SyncErrorCode.INCOMPLETE_EXPORT, "导出分片序号不连续"); add(c.string(1)) } }
        }
        if (actualHashes != manifest.partHashes || SyncWire.partsDigest(actualHashes) != manifest.partsSha256) fail(SyncErrorCode.CONTENT_MISMATCH, "分片集合hash不匹配")
        streamIncoming(db, manifest)
        val peer = requirePeer(db, peerId, manifest.senderDatabaseId, requireConsent = true)
        if (!manifest.full && peer.receivedRemoteSeq != manifest.baseSeq) fail(SyncErrorCode.CURSOR_GAP, "提交时接收游标已变化")
        db.exec(
            "UPDATE sync_peer SET received_remote_seq=? WHERE peer_id=?",
            listOf(maxOf(peer.receivedRemoteSeq, manifest.throughSeq), peerId),
        )
        db.exec(
            "INSERT INTO sync_applied_exports(peer_database_id,export_id,manifest_hash,through_seq) VALUES(?,?,?,?)",
            listOf(manifest.senderDatabaseId, manifest.exportId, manifest.manifestHash, manifest.throughSeq),
        )
        db.exec("DELETE FROM sync_incoming WHERE export_id=?", listOf(exportId))
        Ack(exportId, manifest.throughSeq, manifest.manifestHash)
    }

    fun acknowledge(peerId: String, peerDatabaseId: String, ack: Ack) = database.transaction { db ->
        val manifest = db.query("SELECT manifest_json,content_epoch FROM sync_exports WHERE export_id=? AND peer_id=?", listOf(ack.exportId, peerId)).use { c ->
            if (!c.next()) fail(SyncErrorCode.NOT_FOUND, "ACK对应导出不存在")
            SyncWire.decodeManifest(c.string(0)) to c.long(1)
        }
        val value = manifest.first
        if (value.receiverDatabaseId != peerDatabaseId || value.throughSeq != ack.throughSeq || value.manifestHash != ack.manifestHash) fail(SyncErrorCode.CONTENT_MISMATCH, "ACK凭据不匹配")
        if (manifest.second != identity(db).contentEpoch) fail(SyncErrorCode.EXPORT_INVALIDATED, "ACK对应导出已失效")
        val peer = requirePeer(db, peerId, peerDatabaseId, requireConsent = true)
        val through = maxOf(peer.ackedLocalSeq, ack.throughSeq)
        db.exec("UPDATE sync_peer SET acked_local_seq=? WHERE peer_id=?", listOf(through, peerId))
        db.exec("DELETE FROM sync_changes WHERE commit_seq<=?", listOf(through))
        db.exec("DELETE FROM sync_exports WHERE export_id=?", listOf(ack.exportId))
    }

    fun fences(): FenceSet = database.transaction { db ->
        val id = identity(db)
        val space = id.spaceId ?: fail(SyncErrorCode.SPACE_MISMATCH, "尚未绑定同步空间")
        val values = db.query("SELECT record_id,purge_id FROM sync_purges ORDER BY record_id").use { c -> buildList { while (c.next()) add(WirePurgeFence(c.string(0), c.string(1))) } }
        FenceSet(spaceId = space, senderDatabaseId = id.databaseId, contentEpoch = id.contentEpoch, fences = values)
    }

    fun mergeFences(peerId: String, peerDatabaseId: String, fences: FenceSet): MergeResult = database.transaction { db ->
        if (fences.protocol != SYNC_PROTOCOL) fail(SyncErrorCode.UNSUPPORTED_PROTOCOL, "不支持的同步协议")
        if (fences.dataSchema != SYNC_DATA_SCHEMA) fail(SyncErrorCode.UNSUPPORTED_SCHEMA, "不支持的数据schema")
        if (fences.senderDatabaseId != peerDatabaseId) fail(SyncErrorCode.IDENTITY_MISMATCH, "fence发送方身份不匹配")
        if (fences.spaceId != identity(db).spaceId) fail(SyncErrorCode.SPACE_MISMATCH, "同步空间身份不一致")
        requirePeer(db, peerId, peerDatabaseId, requireConsent = true)
        repository.merge(db, emptySnapshot(peerDatabaseId, fences.fences.map { PurgeFence(it.recordId, it.purgeId) })).also { result ->
            if (result.purgedRecords.isNotEmpty()) db.exec("DELETE FROM sync_incoming")
        }
    }

    fun cancelExport(exportId: String) { database.transaction { it.exec("DELETE FROM sync_exports WHERE export_id=?", listOf(exportId)) } }
    fun cancelIncoming(exportId: String) { database.transaction { it.exec("DELETE FROM sync_incoming WHERE export_id=?", listOf(exportId)) } }
    fun cleanup() { database.transaction { db -> db.exec("DELETE FROM sync_exports"); db.exec("DELETE FROM sync_incoming") } }

    private fun stageSnapshot(db: SqlDatabase, writer: PartWriter, base: Long, through: Long, full: Boolean) {
        db.query("SELECT record_id,purge_id FROM sync_purges ORDER BY record_id").use { c ->
            while (c.next()) writer.item(WireSnapshotItem("purge", purge = WirePurgeFence(c.string(0), c.string(1))))
        }
        db.query("SELECT draft_id,terminal_id,mode,target_record_id,consumed_heads,value_hash FROM sync_draft_terminals ORDER BY terminal_id").use { c ->
            while (c.next()) writer.item(WireSnapshotItem("draftTerminal", draftTerminal = WireDraftTerminal(c.string(0), c.string(1), c.string(2), c.string(3), SyncCodec.decodeSet(c.string(4)), c.string(5))))
        }
        val keys = if (full) {
            db.query("SELECT kind,entity_key FROM sync_heads UNION SELECT kind,entity_key FROM sync_retired ORDER BY 1,2").use { c -> buildList { while (c.next()) add(c.string(0) to c.string(1)) } }
        } else {
            db.query(
                "SELECT DISTINCT kind,entity_key FROM sync_changes WHERE (commit_seq>? AND commit_seq<=?) OR bundle_id IN (SELECT bundle_id FROM sync_changes WHERE commit_seq>? AND commit_seq<=?) ORDER BY kind,entity_key",
                listOf(base, through, base, through),
            ).use { c -> buildList { while (c.next()) if (c.string(0) in ENTITY_KINDS) add(c.string(0) to c.string(1)) } }
        }
        keys.forEach { (kind, key) ->
            if (kind !in ENTITY_KINDS) fail(SyncErrorCode.UNSUPPORTED_SCHEMA, "未知同步实体类型")
            val entity = repository.readEntity(db, kind, key)
            writer.item(WireSnapshotItem("entity", entity = WireEntity(kind, key, entity.heads.map { WireHead(it.id, it.payloadHash, it.payload) }, entity.retired, entity.recordRefs)))
        }
        db.query("SELECT head_id,kind,entity_key,payload_hash FROM sync_known_heads ORDER BY head_id").use { c ->
            while (c.next()) writer.item(WireSnapshotItem("knownHead", knownHead = WireKnownHead(c.string(0), c.string(1), c.string(2), c.string(3))))
        }
        db.query("SELECT record_id,event_id FROM sync_record_life ORDER BY record_id,event_id").use { c -> while (c.next()) writer.item(WireSnapshotItem("lifeEvent", lifeEvent = WireLifeEvent(c.string(0), c.string(1)))) }
        db.query("SELECT table_name,sync_id,payload_hash,payload_json FROM sync_history ORDER BY sync_id").use { c -> while (c.next()) writer.item(WireSnapshotItem("history", history = WireHistory(c.string(0), c.string(1), c.string(2), SyncCodec.parse(c.string(3))))) }
        db.query("SELECT origin_id,record_id,legacy_token,new_token FROM sync_source_alias ORDER BY origin_id,record_id,legacy_token").use { c -> while (c.next()) writer.item(WireSnapshotItem("sourceAlias", sourceAlias = WireSourceAlias(c.string(0), c.string(1), c.string(2), c.string(3)))) }
    }

    private fun streamIncoming(db: SqlDatabase, manifest: ExportManifest) {
        val line = ByteArrayOutputStream()
        db.query("SELECT payload_json FROM sync_incoming_parts WHERE export_id=? ORDER BY part_no", listOf(manifest.exportId)).use { c ->
            while (c.next()) {
                val bytes = try { Base64.getDecoder().decode(c.string(0)) } catch (_: IllegalArgumentException) { fail(SyncErrorCode.CONTENT_MISMATCH, "暂存分片Base64无效") }
                bytes.forEach { byte ->
                    if (byte.toInt() == 10) {
                        if (line.size() == 0) fail(SyncErrorCode.CONTENT_MISMATCH, "快照含空帧")
                        applyItem(db, manifest, String(line.toByteArray(), Charsets.UTF_8))
                        line.reset()
                    } else line.write(byte.toInt())
                }
            }
        }
        if (line.size() != 0) fail(SyncErrorCode.INCOMPLETE_EXPORT, "快照末帧不完整")
    }

    private fun applyItem(db: SqlDatabase, manifest: ExportManifest, encoded: String) {
        val item = SyncWire.decodeItem(encoded)
        val populated = listOf(item.entity, item.knownHead, item.lifeEvent, item.purge, item.draftTerminal, item.history, item.sourceAlias).count { it != null }
        if (populated != 1) fail(SyncErrorCode.CONTENT_MISMATCH, "快照条目必须且只能包含一个载荷")
        val snapshot = when (item.type) {
            "purge" -> emptySnapshot(manifest.senderDatabaseId, purges = listOf((item.purge ?: fail(SyncErrorCode.CONTENT_MISMATCH, "purge载荷缺失")).let { PurgeFence(it.recordId, it.purgeId) }))
            "draftTerminal" -> emptySnapshot(manifest.senderDatabaseId, terminals = listOf((item.draftTerminal ?: fail(SyncErrorCode.CONTENT_MISMATCH, "draftTerminal载荷缺失")).let { terminal ->
                val mode = try { DraftTerminalMode.valueOf(terminal.mode) } catch (_: IllegalArgumentException) { fail(SyncErrorCode.CONTENT_MISMATCH, "草稿终结模式无效") }
                DraftTerminal(terminal.draftId, terminal.terminalId, mode, terminal.targetRecordId, terminal.consumedHeads, terminal.valueHash)
            }))
            "entity" -> emptySnapshot(manifest.senderDatabaseId, entities = listOf((item.entity ?: fail(SyncErrorCode.CONTENT_MISMATCH, "entity载荷缺失")).toEntity()))
            "knownHead" -> emptySnapshot(manifest.senderDatabaseId, known = listOf((item.knownHead ?: fail(SyncErrorCode.CONTENT_MISMATCH, "knownHead载荷缺失")).let { if(it.kind !in ENTITY_KINDS) fail(SyncErrorCode.UNSUPPORTED_SCHEMA,"未知同步实体类型"); KnownHead(it.id, it.kind, it.key, it.payloadHash) }))
            "lifeEvent" -> emptySnapshot(manifest.senderDatabaseId, life = listOf((item.lifeEvent ?: fail(SyncErrorCode.CONTENT_MISMATCH, "lifeEvent载荷缺失")).let { RecordLifeEvent(it.recordId, it.eventId) }))
            "history" -> emptySnapshot(manifest.senderDatabaseId, histories = listOf((item.history ?: fail(SyncErrorCode.CONTENT_MISMATCH, "history载荷缺失")).let {
                if (it.table !in HISTORY_TABLES) fail(SyncErrorCode.UNSUPPORTED_SCHEMA, "未知历史类型")
                StableHistory(it.table, it.syncId, it.payloadHash, it.payload)
            }))
            "sourceAlias" -> emptySnapshot(manifest.senderDatabaseId, aliases = listOf((item.sourceAlias ?: fail(SyncErrorCode.CONTENT_MISMATCH, "sourceAlias载荷缺失")).let { SourceAlias(it.originId, it.recordId, it.legacyToken, it.newToken) }))
            else -> fail(SyncErrorCode.UNSUPPORTED_SCHEMA, "未知快照条目类型")
        }
        try { repository.merge(db, snapshot) } catch (e: ContentMismatch) {
            fail(SyncErrorCode.CONTENT_MISMATCH, e.message ?: "同步内容冲突")
        } catch (e: IllegalArgumentException) {
            fail(SyncErrorCode.CONTENT_MISMATCH, "同步内容校验失败")
        }
    }

    private fun WireEntity.toEntity(): EntityState {
        if (kind !in ENTITY_KINDS) fail(SyncErrorCode.UNSUPPORTED_SCHEMA, "未知同步实体类型")
        if (key.isBlank() || heads.any { it.id.isBlank() || it.payloadHash.length != 64 }) fail(SyncErrorCode.CONTENT_MISMATCH, "实体标识或hash无效")
        heads.forEach { head ->
            if (kind in GOAL_KINDS) GoalCodec.validatePayload(kind, key, head.payload)
        }
        return EntityState(kind, key, heads.map { SyncHead(it.id, it.payloadHash, it.payload) }, retired, recordRefs)
    }

    private fun validateManifest(value: ExportManifest) {
        if (value.protocol != SYNC_PROTOCOL) fail(SyncErrorCode.UNSUPPORTED_PROTOCOL, "不支持的同步协议")
        if (value.dataSchema != SYNC_DATA_SCHEMA) fail(SyncErrorCode.UNSUPPORTED_SCHEMA, "不支持的数据schema")
        if (value.exportId.isBlank() || value.partCount < 0 || value.partCount != value.partHashes.size || value.baseSeq < 0 || value.throughSeq < value.baseSeq) fail(SyncErrorCode.CONTENT_MISMATCH, "manifest字段无效")
        if (value.partHashes.any { it.length != 64 } || value.partsSha256 != SyncWire.partsDigest(value.partHashes)) fail(SyncErrorCode.CONTENT_MISMATCH, "manifest分片摘要无效")
        if (value.manifestHash != SyncWire.manifestDigest(value.copy(manifestHash = ""))) fail(SyncErrorCode.CONTENT_MISMATCH, "manifest hash无效")
    }

    private fun incomingManifest(db: SqlDatabase, exportId: String): ExportManifest = db.query("SELECT manifest_json FROM sync_incoming WHERE export_id=?", listOf(exportId)).use { c ->
        if (!c.next()) fail(SyncErrorCode.NOT_FOUND, "接收会话不存在")
        SyncWire.decodeManifest(c.string(0)).also(::validateManifest)
    }

    private fun requirePeer(db: SqlDatabase, peerId: String, databaseId: String, requireConsent: Boolean): PeerRow {
        val current = peer(db, peerId) ?: fail(SyncErrorCode.IDENTITY_MISMATCH, "peer尚未配对")
        if (current.databaseId != null && current.databaseId != databaseId) fail(SyncErrorCode.IDENTITY_MISMATCH, "对端数据库身份已变化，必须重新配对")
        if (requireConsent && (!current.sendConsent || !current.receiveConsent)) fail(SyncErrorCode.CONSENT_REQUIRED, "双方数据同意尚未齐全")
        if (current.databaseId == null) db.exec("UPDATE sync_peer SET peer_database_id=? WHERE peer_id=?", listOf(databaseId, peerId))
        return current.copy(databaseId = databaseId)
    }

    private fun peer(db: SqlDatabase, peerId: String): PeerRow? = db.query("SELECT peer_database_id,acked_local_seq,received_remote_seq,send_consent,receive_consent FROM sync_peer WHERE peer_id=?", listOf(peerId)).use { c ->
        if (!c.next()) null else PeerRow(c.optional("peer_database_id"), c.long(1), c.long(2), c.long(3) != 0L, c.long(4) != 0L)
    }

    private fun identity(db: SqlDatabase): SyncIdentity = db.query("SELECT space_id,origin_id,actor_epoch,database_id,next_counter,commit_seq,content_epoch FROM sync_local WHERE singleton=1").use { c ->
        check(c.next()); SyncIdentity(c.optional("space_id"), c.string(1), c.string(2), c.string(3), c.long(4), c.long(5), c.long(6))
    }

    private fun emptySnapshot(
        peerDatabaseId: String,
        purges: List<PurgeFence> = emptyList(),
        terminals: List<DraftTerminal> = emptyList(),
        entities: List<EntityState> = emptyList(),
        known: List<KnownHead> = emptyList(),
        life: List<RecordLifeEvent> = emptyList(),
        histories: List<StableHistory> = emptyList(),
        aliases: List<SourceAlias> = emptyList(),
    ) = SyncStateSnapshot(SyncIdentity(null, peerDatabaseId, peerDatabaseId, peerDatabaseId, 0, 0, 0), entities, known, life, purges, terminals, histories, aliases, 0)

    private fun scalar(db: SqlDatabase, sql: String, args: List<Any?> = emptyList()): Long = db.query(sql, args).use { c -> check(c.next()); c.long(0) }
    private fun Boolean.flag(): Int = if (this) 1 else 0
    private fun fail(code: SyncErrorCode, message: String): Nothing = throw SyncProtocolException(code, message)

    private data class PeerRow(val databaseId: String?, val ackedLocalSeq: Long, val receivedRemoteSeq: Long, val sendConsent: Boolean, val receiveConsent: Boolean)
    private data class StagedPart(val epoch: Long, val state: String, val data: String?, val hash: String?)

    private class PartWriter(private val db: SqlDatabase, private val exportId: String) {
        private val buffer = ByteArrayOutputStream(SYNC_PART_BYTES)
        val hashes = mutableListOf<String>()
        private var partNo = 0

        fun item(value: WireSnapshotItem) {
            val bytes = (SyncWire.encodeItem(value) + "\n").toByteArray(Charsets.UTF_8)
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(SYNC_PART_BYTES - buffer.size(), bytes.size - offset)
                buffer.write(bytes, offset, count)
                offset += count
                if (buffer.size() == SYNC_PART_BYTES) flush()
            }
        }

        fun finish() { if (buffer.size() > 0) flush() }

        private fun flush() {
            val bytes = buffer.toByteArray()
            val hash = SyncCodec.sha256(bytes)
            db.exec(
                "INSERT INTO sync_export_parts(export_id,part_no,kind,entity_key,record_refs,payload_json,part_hash) VALUES(?,?,'stream','','',?,?)",
                listOf(exportId, partNo++, Base64.getEncoder().encodeToString(bytes), hash),
            )
            hashes += hash
            buffer.reset()
        }
    }

    companion object {
        private val GOAL_KINDS = setOf(GoalCodec.GOAL_KIND, GoalCodec.CONFIRMATION_KIND, GoalCodec.REVIEW_KIND)
        private val ENTITY_KINDS = setOf("record", "draft-slot", "report", "answer") + GOAL_KINDS
        private val HISTORY_TABLES = setOf("revision", "report_answer_history")
    }
}
