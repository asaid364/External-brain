package app.liferecord.core

import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

data class EventTime(val local: String, val zone: String, val offset: String, val instant: Long) {
    fun display() = "${local.replace('T', ' ')}  $zone ($offset)"
}

data class Record(val id: String, val text: String, val time: EventTime, val created: String, val modified: String?, val deleted: String?)
data class Revision(val id: String, val recordId: String, val beforeText: String, val beforeTime: EventTime, val afterText: String, val afterTime: EventTime, val modified: String, val parentHeads: Set<String>)
data class Draft(
    val id: String,
    val recordId: String?,
    val text: String,
    val local: String,
    val zone: String,
    val offset: String?,
    val targetRecordId: String = recordId ?: id,
    val baseRecordHeads: Set<String> = emptySet(),
    val baseRecordSnapshot: Record? = null,
)
data class EditSession(
    val sessionId: String,
    val slot: String,
    val draftId: String,
    val targetRecordId: String,
    val baseRecordHeads: Set<String>,
    val baseRecordSnapshot: Record?,
    val baseDraftHeads: Set<String>,
    val lastOwnDraftHead: String?,
    val draft: Draft,
)
data class Snapshot(val records: List<Record>, val drafts: List<Draft>, val revisions: List<Revision>)

object RecordTime {
    private val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)

    fun newDraft(clock: Clock = Clock.systemDefaultZone(), id: String = UUID.randomUUID().toString(), targetRecordId: String = id): Draft {
        val now = ZonedDateTime.now(clock)
        return Draft(id, null, "", now.format(format), now.zone.id, now.offset.id, targetRecordId)
    }

    fun edit(record: Record) = Draft(record.id, record.id, record.text, record.time.local.replace('T', ' '), record.time.zone, record.time.offset, record.id, emptySet(), record)

    fun local(draft: Draft): LocalDateTime = try {
        LocalDateTime.parse(draft.local, format)
    } catch (_: DateTimeException) {
        throw IllegalArgumentException("发生时间格式应为 年-月-日 时:分，例如 2026-10-30 09:30")
    }

    fun offsets(draft: Draft): List<ZoneOffset> = try {
        ZoneId.of(draft.zone).rules.getValidOffsets(local(draft))
    } catch (error: DateTimeException) {
        throw IllegalArgumentException("请选择有效时区", error)
    }

    fun resolve(draft: Draft, now: Instant = Instant.now()): EventTime {
        val value = local(draft)
        val zone = try {
            ZoneId.of(draft.zone)
        } catch (_: DateTimeException) {
            throw IllegalArgumentException("请选择有效时区")
        }
        val choices = zone.rules.getValidOffsets(value)
        require(choices.isNotEmpty()) { "这个当地时间因夏令时不存在，请调整时间；不会自动偏移。" }
        val offset = if (choices.size == 1) choices.first() else choices.firstOrNull { it.id == draft.offset }
        requireNotNull(offset) { "该当地时间出现两次，请明确选择时差。" }
        val instant = value.toInstant(offset)
        require(!instant.isAfter(now)) { "发生时间不能晚于当前设备时刻。输入可作为草稿保留。" }
        return EventTime(value.toString(), zone.id, offset.id, instant.toEpochMilli())
    }

    fun stamp(clock: Clock = Clock.systemDefaultZone()): String = ZonedDateTime.now(clock).toString()

    fun displayStamp(value: String): String = runCatching {
        val time = ZonedDateTime.parse(value)
        "${time.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"))}  ${time.zone.id} (${time.offset.id})"
    }.getOrDefault(value)

    fun unchanged(draft: Draft, record: Record): Boolean =
        draft.text == record.text && draft.local == record.time.local.replace('T', ' ') && draft.zone == record.time.zone &&
            (draft.offset == record.time.offset || runCatching { offsets(draft).size == 1 }.getOrDefault(false))
}
