package app.liferecord.core

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal object SyncCodec {
    val json = Json { ignoreUnknownKeys = false; explicitNulls = true }
    fun parse(value: String): JsonElement = json.parseToJsonElement(value)
    fun canonical(value: JsonElement): String = value.toString()
    fun hash(value: JsonElement): String = sha256(canonical(value).toByteArray(Charsets.UTF_8))
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun encodeSet(values: Set<String>): String = values.sorted().joinToString("\u0000") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }
    fun decodeSet(value: String): Set<String> = if (value.isEmpty()) emptySet() else value.split('\u0000').mapTo(linkedSetOf()) { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
}
