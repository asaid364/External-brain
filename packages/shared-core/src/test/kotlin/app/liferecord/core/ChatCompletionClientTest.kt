package app.liferecord.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ChatCompletionClientTest {
    private val configuration = AiConfiguration(
        endpoint = "https://synthetic.invalid/v1",
        apiKey = "synthetic-key",
        model = " synthetic-model ",
        consent = false
    )

    @Test fun credentialsDoNotRequireReportConsentAndRequestIsCanonical(): Unit = runBlocking {
        var captured: ChatRequest? = null
        val client = ChatCompletionClient(fixedClock(), ChatTransport { request ->
            captured = request
            ChatResponse(200, emptyMap(), envelope("synthetic result"))
        })

        assertEquals("synthetic result", client.complete(configuration, "{\"fixture\":true}"))
        assertEquals("https://synthetic.invalid/v1/chat/completions", captured!!.url.toString())
        assertEquals("synthetic-key", captured!!.apiKey)
        assertEquals("{\"fixture\":true}", captured!!.body)
        assertEquals("synthetic-model", AiSettings.validateCredentials(configuration).model)
        assertThrows(IllegalArgumentException::class.java) { AiSettings.validate(configuration) }
    }

    @Test fun beforeSendRunsImmediatelyBeforeTransport() = runBlocking {
        val events = mutableListOf<String>()
        val client = ChatCompletionClient(fixedClock(), ChatTransport {
            events += "transport"
            ChatResponse(200, emptyMap(), envelope("ok"))
        })

        assertEquals("ok", client.complete(configuration, "{}") { events += "beforeSend" })
        assertEquals(listOf("beforeSend", "transport"), events)
    }

    @Test fun callbackFailurePropagatesUnchangedWithoutTransport() = runBlocking {
        var executions = 0
        val client = ChatCompletionClient(fixedClock(), ChatTransport {
            executions++
            ChatResponse(200, emptyMap(), envelope("unexpected"))
        })
        val expected = SyntheticCallbackFailure()

        val actual = try {
            client.complete(configuration, "{}") { throw expected }
            throw AssertionError("expected callback failure")
        } catch (failure: SyntheticCallbackFailure) {
            failure
        }

        assertSame(expected, actual)
        assertEquals(0, executions)
    }

    @Test fun callbackCancellationPropagatesUnchangedWithoutTransport() = runBlocking {
        var executions = 0
        val client = ChatCompletionClient(fixedClock(), ChatTransport {
            executions++
            ChatResponse(200, emptyMap(), envelope("unexpected"))
        })
        val expected = CancellationException("synthetic cancellation")

        val actual = try {
            client.complete(configuration, "{}") { throw expected }
            throw AssertionError("expected cancellation")
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertSame(expected, actual)
        assertEquals(0, executions)
    }

    @Test fun invalidCredentialsFailBeforeCallbackAndTransport() = runBlocking {
        var callbackCalled = false
        var transportCalled = false
        val client = ChatCompletionClient(fixedClock(), ChatTransport {
            transportCalled = true
            ChatResponse(200, emptyMap(), envelope("unexpected"))
        })

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client.complete(configuration.copy(endpoint = "http://synthetic.invalid"), "{}") {
                    callbackCalled = true
                }
            }
        }
        assertFalse(callbackCalled)
        assertFalse(transportCalled)
    }

    @Test fun classifiesTransportAndRetryAfterWithoutLeakingResponseBody() = runBlocking {
        val ioFailure = failure(ChatTransport { throw IOException("synthetic secret") })
        assertTrue(ioFailure.retryable)
        assertFalse(ioFailure.userMessage.contains("secret"))

        val limited = failure(ChatTransport {
            ChatResponse(429, mapOf("retry-after" to listOf("17")), "synthetic secret")
        })
        assertTrue(limited.retryable)
        assertEquals(17_000L, limited.retryAfterMillis)
        assertFalse(limited.userMessage.contains("secret"))

        val unavailable = failure(ChatTransport {
            ChatResponse(
                503,
                mapOf("Retry-After" to listOf("Tue, 03 Nov 2026 00:00:20 GMT")),
                ""
            )
        })
        assertEquals(20_000L, unavailable.retryAfterMillis)
    }

    @Test fun rejectsIncompleteBlankAndMalformedEnvelopesWithNeutralErrors() = runBlocking {
        listOf(
            envelope("valid", finish = "length"),
            envelope("   "),
            "{\"choices\":[]}",
            "not-json"
        ).forEach { body ->
            val failure = failure(ChatTransport { ChatResponse(200, emptyMap(), body) })
            assertTrue(failure.userMessage.contains("AI"))
            assertFalse(failure.userMessage.contains("日报"))
        }
    }

    private suspend fun failure(transport: ChatTransport): AiFailure = try {
        ChatCompletionClient(fixedClock(), transport).complete(configuration, "{}")
        throw AssertionError("expected failure")
    } catch (failure: AiFailure) {
        failure
    }

    private fun envelope(content: String, finish: String = "stop") = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("finish_reason", finish)
                put("message", buildJsonObject { put("content", content) })
            })
        })
    }.toString()

    private fun fixedClock() = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC)

    private class SyntheticCallbackFailure : RuntimeException()
}
