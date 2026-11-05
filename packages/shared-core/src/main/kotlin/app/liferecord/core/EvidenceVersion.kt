package app.liferecord.core

import java.security.MessageDigest

/** Canonical evidence identities. These encodings are protocol data and must not follow JSON formatting. */
object EvidenceVersion {
    private const val V3_PREFIX = "v3:"
    private const val LEGACY_INPUT_PREFIX = "legacy-v2:"

    data class ParsedV3(val lifeDigest: String, val contentDigest: String)

    fun source(record: Record, headIds: Collection<String>, lifeEventIds: Collection<String>): String {
        require(headIds.isNotEmpty()) { "记录缺少可验证的内容版本" }
        val lifeDigest = nulDigest(lifeEventIds.distinct().sorted())
        val contentFields = buildList {
            add("record-content-v3")
            addAll(headIds.distinct().sorted())
            add("record")
            add(record.id)
            add(record.text)
            add(record.time.local)
            add(record.time.zone)
            add(record.time.offset)
            add(record.time.instant.toString())
            add(record.created)
            add(record.modified ?: "")
            add(record.deleted ?: "")
        }
        return "$V3_PREFIX$lifeDigest:${nulDigest(contentFields)}"
    }

    fun parseV3(value: String): ParsedV3? {
        if (!value.startsWith(V3_PREFIX)) return null
        val parts = value.split(':')
        if (parts.size != 3 || !parts[1].isSha256() || !parts[2].isSha256()) return null
        return ParsedV3(parts[1], parts[2])
    }

    fun isSupportedSourceToken(value: String): Boolean {
        if (parseV3(value) != null) return true
        val separator = value.indexOf(':')
        if (separator <= 0 || separator != value.lastIndexOf(':')) return false
        val lifecycle = value.substring(0, separator).toLongOrNull() ?: return false
        return lifecycle >= 0 && value.substring(separator + 1).isSha256()
    }

    fun exactMatch(stored: String, current: String, aliases: Collection<SourceAlias> = emptyList(), recordId: String? = null): Boolean {
        if (stored == current) return true
        if (parseV3(stored) != null) return false
        return aliases.any { alias ->
            alias.legacyToken == stored && alias.newToken == current && (recordId == null || alias.recordId == recordId)
        }
    }

    fun inputHash(sources: List<ReportSource>, questions: List<ReportQuestion>): String {
        val fields = mutableListOf("report-input-v3")
        sources.sortedBy { it.id }.forEach { fields += listOf("record", it.id, it.version) }
        questions.asSequence()
            .filter { it.answer.isNotEmpty() || it.skipped }
            .sortedBy { it.id }
            .forEach { fields += listOf("evidence", it.id, it.answer, it.skipped.toString(), it.applicable.toString()) }
        return "$V3_PREFIX${nulDigest(fields)}"
    }

    fun preserveLegacyInputHash(stored: String, legacyCurrentHash: String, currentV3Hash: String): String = when {
        stored == legacyCurrentHash -> currentV3Hash
        stored.startsWith(LEGACY_INPUT_PREFIX) || stored.startsWith(V3_PREFIX) -> stored
        else -> "$LEGACY_INPUT_PREFIX$stored"
    }

    fun legacyV2Source(record: Record, reportRevision: Long, reportLifecycle: Long): String {
        val content = nulDigest(listOf(record.id, record.text, record.time.local, record.time.zone, record.time.offset,
            record.time.instant.toString(), record.created, record.modified ?: "", reportRevision.toString()))
        return "$reportLifecycle:$content"
    }

    fun legacyV2InputHash(sources: List<ReportSource>, questions: List<ReportQuestion>): String {
        val fields = mutableListOf("report-input-v2")
        sources.forEach { fields += listOf("record", it.id, it.version) }
        questions.filter { it.answer.isNotEmpty() || it.skipped }.sortedBy { it.id }
            .forEach { fields += listOf("evidence", it.id, it.answer, it.skipped.toString(), it.applicable.toString()) }
        return nulDigest(fields)
    }

    fun nulDigest(fields: Collection<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { value ->
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun String.isSha256(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
}
