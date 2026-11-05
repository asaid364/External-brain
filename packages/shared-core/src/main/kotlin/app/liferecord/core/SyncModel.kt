package app.liferecord.core

import kotlinx.serialization.json.JsonElement

data class SyncHead(val id: String, val payloadHash: String, val payload: JsonElement)
data class EntityState(
    val kind: String,
    val key: String,
    val heads: List<SyncHead>,
    val retired: Set<String>,
    val recordRefs: Set<String> = emptySet(),
) {
    val unresolved: Boolean get() = heads.map { it.payloadHash }.distinct().size > 1
}
data class KnownHead(val id: String, val kind: String, val key: String, val payloadHash: String)
data class SyncChange(val commitSeq: Long, val kind: String, val entityKey: String, val bundleId: String)
data class SyncIdentity(
    val spaceId: String?, val originId: String, val actorEpoch: String, val databaseId: String,
    val nextCounter: Long, val commitSeq: Long, val contentEpoch: Long,
)
data class PurgeFence(val recordId: String, val purgeId: String)
data class RecordLifeEvent(val recordId: String, val eventId: String)
data class DraftTerminal(
    val draftId: String, val terminalId: String, val mode: DraftTerminalMode, val targetRecordId: String,
    val consumedHeads: Set<String>, val valueHash: String,
)
enum class DraftTerminalMode { SAVED, DISCARDED }
data class StableHistory(val table: String, val syncId: String, val payloadHash: String, val payload: JsonElement)
data class SourceAlias(val originId: String, val recordId: String, val legacyToken: String, val newToken: String)
data class SyncStateSnapshot(
    val identity: SyncIdentity,
    val entities: List<EntityState>,
    val knownHeads: List<KnownHead>,
    val lifeEvents: List<RecordLifeEvent>,
    val purges: List<PurgeFence>,
    val draftTerminals: List<DraftTerminal>,
    val histories: List<StableHistory>,
    val sourceAliases: List<SourceAlias>,
    val throughSeq: Long,
)
data class LocalWriteResult(val headId: String, val resultKey: String, val changed: Boolean)
data class MergeResult(val changed: Boolean, val changedEntities: Set<Pair<String, String>>, val purgedRecords: Set<String>)

class UnresolvedConflict(val kind: String, val entityKey: String) :
    IllegalStateException("存在未解决的同步冲突：$kind/$entityKey")
class ContentMismatch(message: String) : IllegalArgumentException(message)
class PurgedRecord(val recordId: String) : IllegalStateException("记录已彻底删除：$recordId")
class DraftIdentityMismatch(val draftId: String) : IllegalArgumentException("DRAFT_IDENTITY_MISMATCH: $draftId")
