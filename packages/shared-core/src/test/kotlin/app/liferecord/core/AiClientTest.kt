package app.liferecord.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AiClientTest {
    private val config = AiConfiguration("https://synthetic.invalid/v1", "synthetic-key", "synthetic-model", consent = true)
    private val records = listOf(record("r2", 2), record("r1", 1))
    private val input = ReportInput("2026-11-02", records, emptyList(), "hash", records.map { ReportSource(it.id, "v") })

    @Test fun endpointIsHttpsAndCanonicalWithoutCredentialLeakSurfaces() {
        assertEquals("https://example.invalid/v1/chat/completions", AiClient.normalizeEndpoint("https://example.invalid/v1").toString())
        assertEquals("https://example.invalid/chat/completions", AiClient.normalizeEndpoint("https://example.invalid/chat/completions").toString())
        listOf("http://example.invalid/v1", "https://user@example.invalid/v1", "https://example.invalid/v1?q=x", "https://example.invalid/v1#x", "https://example.invalid/v1/%2e%2e/private")
            .forEach { assertThrows(IllegalArgumentException::class.java) { AiClient.normalizeEndpoint(it) } }
    }

    @Test fun generateValidatesCompleteKnownReferencesAndStableQuestionId() = runBlocking {
        val reportJson = buildJsonObject {
            put("paragraphs", buildJsonArray { add(buildJsonObject {
                put("text", "先记录一，后记录二。")
                put("recordIds", buildJsonArray { add(JsonPrimitive("r1")); add(JsonPrimitive("r2")) })
                put("answerIds", buildJsonArray {})
            }) })
            put("questions", buildJsonArray { add(buildJsonObject {
                put("text", "结果是什么？")
                put("recordIds", buildJsonArray { add(JsonPrimitive("r2")); add(JsonPrimitive("r1")) })
            }) })
        }.toString()
        val generated = client(200, envelope("```json\n$reportJson\n```")).generate(config, input)
        assertEquals(setOf("r1", "r2"), generated.paragraphs.single().recordIds.toSet())
        assertEquals(AiClient.questionId(listOf("r1", "r2")), generated.questions.single().id)
        assertEquals(generated.questions.single().id, AiClient.questionId(listOf("r2", "r1", "r1")))
    }

    @Test fun rejectsUnknownMissingAndPreviouslyAnsweredGroups() = runBlocking {
        val withPrevious = input.copy(questions = listOf(ReportQuestion("old", listOf("r1"), "结果？", answer = "合成回答")))
        assertFailure { client(200, envelope(report("r1", "unknown", questionIds = emptyList()))).generate(config, withPrevious) }
        assertFailure { client(200, envelope(report("r1", questionIds = emptyList()))).generate(config, withPrevious) }
        assertFailure { client(200, envelope(report("r1", "r2", questionIds = listOf("r1")))).generate(config, withPrevious) }
    }

    @Test fun rejectsTruncatedNonStrictAndNonStringProtocolValues() = runBlocking {
        val valid = report("r1", "r2", questionIds = emptyList())
        assertFailure { client(200, envelope(valid, finish = "length")).generate(config, input) }
        assertFailure { client(200, envelope("prefix $valid")).generate(config, input) }
        val extra = Json.parseToJsonElement(valid).jsonObject.toMutableMap().also { it["unexpected"] = kotlinx.serialization.json.JsonPrimitive(true) }
        assertFailure { client(200, envelope(kotlinx.serialization.json.JsonObject(extra).toString())).generate(config, input) }
        assertFailure { client(200, envelope(valid.replace("\"r1\"", "1"))).generate(config, input) }
        assertFailure { client(200, envelope(valid.replace("\"r1\"", "null"))).generate(config, input) }
    }

    @Test fun classifiesAuthBalanceTransientAndRetryAfter() = runBlocking {
        val auth = failure { client(401, "secret server body").test(config) }
        assertFalse(auth.retryable)
        assertFalse(auth.userMessage.contains("secret"))
        assertFalse(failure { client(402, "").test(config) }.retryable)
        val limited = failure(mapOf("Retry-After" to listOf("17"))) { client(429, "", it).test(config) }
        assertTrue(limited.retryable)
        assertEquals(17_000L, limited.retryAfterMillis)
        val dated = failure(mapOf("Retry-After" to listOf("Tue, 03 Nov 2026 00:00:20 GMT"))) { client(503, "", it).test(config) }
        assertEquals(20_000L, dated.retryAfterMillis)
    }

    @Test fun ioIsRetryableAndTestUsesMinimalSyntheticPayload() = runBlocking {
        var captured: ChatRequest? = null
        val ai = AiClient(fixedClock(), ChatTransport { request -> captured = request; throw IOException("synthetic") })
        assertTrue(failure { ai.test(config) }.retryable)
        val sent = Json.parseToJsonElement(captured!!.body).jsonObject
        assertEquals("synthetic-model", sent.getValue("model").jsonPrimitive.content)
        assertFalse(captured!!.body.contains("2026-11-02"))
    }

    private fun client(status: Int, body: String, headers: Map<String, List<String>> = emptyMap()) = AiClient(fixedClock(), ChatTransport { ChatResponse(status, headers, body) })
    private fun envelope(content: String, finish: String = "stop") = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", finish)
            put("message", buildJsonObject { put("content", content) })
        }) })
    }.toString()

    private fun report(vararg paragraphIds: String, questionIds: List<String>) = buildJsonObject {
        put("paragraphs", buildJsonArray { add(buildJsonObject {
            put("text", "合成事实")
            put("recordIds", buildJsonArray { paragraphIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("answerIds", buildJsonArray {})
        }) })
        put("questions", buildJsonArray { if (questionIds.isNotEmpty()) add(buildJsonObject {
            put("text", "合成问题？")
            put("recordIds", buildJsonArray { questionIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }) })
    }.toString()

    private suspend fun assertFailure(block: suspend () -> Unit) { failure(block) }
    private suspend fun failure(headers: Map<String, List<String>>, block: suspend (Map<String, List<String>>) -> Unit): AiFailure =
        try { block(headers); throw AssertionError("expected failure") } catch (value: AiFailure) { value }
    private suspend fun failure(block: suspend () -> Unit): AiFailure =
        try { block(); throw AssertionError("expected failure") } catch (value: AiFailure) { value }
    private fun record(id: String, instant: Long) = Record(id, "合成记录$id", EventTime("2026-11-02T0$instant:00", "UTC", "Z", instant), "created", null, null)
    private fun fixedClock() = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC)
}
