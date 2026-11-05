package app.liferecord.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

const val SYNC_PROTOCOL = 1
const val SYNC_DATA_SCHEMA = 4
const val SYNC_PART_BYTES = 256 * 1024

@Serializable
data class ExportManifest(
    val protocol: Int = SYNC_PROTOCOL,
    val dataSchema: Int = SYNC_DATA_SCHEMA,
    val spaceId: String,
    val senderActor: String,
    val senderDatabaseId: String,
    val receiverDatabaseId: String,
    val exportId: String,
    val baseSeq: Long,
    val throughSeq: Long,
    val full: Boolean,
    val partCount: Int,
    val partHashes: List<String>,
    val partsSha256: String,
    val contentEpoch: Long,
    val manifestHash: String,
)

@Serializable
data class ExportPart(val exportId: String, val partNo: Int, val dataBase64: String, val partHash: String)

@Serializable
data class Ack(val exportId: String, val throughSeq: Long, val manifestHash: String)

@Serializable
data class SyncStatus(
    val protocol: Int = SYNC_PROTOCOL,
    val dataSchema: Int = SYNC_DATA_SCHEMA,
    val spaceId: String?,
    val localDatabaseId: String,
    val peerDatabaseId: String?,
    val localCommitSeq: Long,
    val ackedLocalSeq: Long,
    val receivedRemoteSeq: Long,
    val contentEpoch: Long,
    val sendConsent: Boolean,
    val receiveConsent: Boolean,
    val entityCount: Long?,
    val purgeCount: Long?,
    val counts: SyncCategoryCounts? = null,
)

@Serializable
data class SyncCategoryCounts(
    val records: Long,
    val drafts: Long,
    val reports: Long,
    val goals: Long,
    val reviews: Long,
)

@Serializable
data class FenceSet(
    val protocol: Int = SYNC_PROTOCOL,
    val dataSchema: Int = SYNC_DATA_SCHEMA,
    val spaceId: String,
    val senderDatabaseId: String,
    val contentEpoch: Long,
    val fences: List<WirePurgeFence>,
)

@Serializable
enum class IncomingState { READY, APPLIED }

@Serializable
data class IncomingDisposition(val state: IncomingState, val ack: Ack? = null)

@Serializable
data class WirePurgeFence(val recordId: String, val purgeId: String)

@Serializable
data class WireHead(val id: String, val payloadHash: String, val payload: JsonElement)

@Serializable
data class WireEntity(
    val kind: String,
    val key: String,
    val heads: List<WireHead>,
    val retired: Set<String>,
    val recordRefs: Set<String> = emptySet(),
)

@Serializable
data class WireKnownHead(val id: String, val kind: String, val key: String, val payloadHash: String)

@Serializable
data class WireLifeEvent(val recordId: String, val eventId: String)

@Serializable
data class WireDraftTerminal(
    val draftId: String,
    val terminalId: String,
    val mode: String,
    val targetRecordId: String,
    val consumedHeads: Set<String>,
    val valueHash: String,
)

@Serializable
data class WireHistory(val table: String, val syncId: String, val payloadHash: String, val payload: JsonElement)

@Serializable
data class WireSourceAlias(val originId: String, val recordId: String, val legacyToken: String, val newToken: String)

/** One NDJSON frame. Exactly one value matching [type] must be present. */
@Serializable
data class WireSnapshotItem(
    val type: String,
    val entity: WireEntity? = null,
    val knownHead: WireKnownHead? = null,
    val lifeEvent: WireLifeEvent? = null,
    val purge: WirePurgeFence? = null,
    val draftTerminal: WireDraftTerminal? = null,
    val history: WireHistory? = null,
    val sourceAlias: WireSourceAlias? = null,
)

enum class SyncErrorCode {
    CONSENT_REQUIRED,
    CURSOR_GAP,
    IDENTITY_MISMATCH,
    SPACE_MISMATCH,
    UNSUPPORTED_PROTOCOL,
    UNSUPPORTED_SCHEMA,
    CONTENT_MISMATCH,
    INCOMPLETE_EXPORT,
    EXPORT_INVALIDATED,
    NOT_FOUND,
}

class SyncProtocolException(val code: SyncErrorCode, override val message: String) : IllegalStateException(message)

object SyncWire {
    val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
    }

    fun encodeManifest(value: ExportManifest): String = json.encodeToString(value)
    fun decodeManifest(value: String): ExportManifest = decode("manifest") { json.decodeFromString(value) }
    fun encodePart(value: ExportPart): String = json.encodeToString(value)
    fun decodePart(value: String): ExportPart = decode("part") { json.decodeFromString(value) }
    fun encodeAck(value: Ack): String = json.encodeToString(value)
    fun decodeAck(value: String): Ack = decode("ack") { json.decodeFromString(value) }
    fun encodeStatus(value: SyncStatus): String = json.encodeToString(value)
    fun decodeStatus(value: String): SyncStatus = decode("status") {
        json.decodeFromString<SyncStatus>(value).also { status ->
            val visible = status.entityCount != null || status.purgeCount != null || status.counts != null
            require(!visible || (status.sendConsent && status.receiveConsent)) { "未同意同步时不得返回数量" }
            require(!visible || (status.entityCount != null && status.purgeCount != null && status.counts != null)) { "同步数量必须完整或全部隐藏" }
            require(status.entityCount == null || status.entityCount >= 0) { "实体数量无效" }
            require(status.purgeCount == null || status.purgeCount >= 0) { "彻删数量无效" }
            status.counts?.let { counts ->
                require(counts.records >= 0 && counts.drafts >= 0 && counts.reports >= 0 && counts.goals >= 0 && counts.reviews >= 0) { "同步类别数量无效" }
            }
        }
    }
    fun encodeFences(value: FenceSet): String = json.encodeToString(value)
    fun decodeFences(value: String): FenceSet = decode("fences") { json.decodeFromString(value) }
    fun encodeDisposition(value: IncomingDisposition): String = json.encodeToString(value)
    fun decodeDisposition(value: String): IncomingDisposition = decode("disposition") { json.decodeFromString(value) }

    internal fun encodeItem(value: WireSnapshotItem): String = json.encodeToString(value)
    internal fun decodeItem(value: String): WireSnapshotItem = decode("snapshot item") { json.decodeFromString(value) }

    private inline fun <T> decode(label: String, block: () -> T): T = try {
        block()
    } catch (e: SyncProtocolException) {
        throw e
    } catch (e: Exception) {
        throw SyncProtocolException(SyncErrorCode.CONTENT_MISMATCH, "$label JSON格式无效")
    }

    internal fun partsDigest(hashes: List<String>): String = digestFields(hashes)

    internal fun manifestDigest(value: ExportManifest): String = digestFields(
        listOf(
            value.protocol.toString(), value.dataSchema.toString(), value.spaceId, value.senderActor,
            value.senderDatabaseId, value.receiverDatabaseId, value.exportId, value.baseSeq.toString(),
            value.throughSeq.toString(), value.full.toString(), value.partCount.toString(),
            value.partsSha256, value.contentEpoch.toString(),
        ) + value.partHashes,
    )

    private fun digestFields(fields: List<String>): String {
        val bytes = buildString {
            fields.forEach { field -> append(field.toByteArray(Charsets.UTF_8).size).append(':').append(field) }
        }.toByteArray(Charsets.UTF_8)
        return SyncCodec.sha256(bytes)
    }
}
