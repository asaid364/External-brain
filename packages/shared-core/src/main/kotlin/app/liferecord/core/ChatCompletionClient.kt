package app.liferecord.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ChatRequest(val url: URL, val apiKey: String, val body: String)
data class ChatResponse(val status: Int, val headers: Map<String, List<String>>, val body: String)
fun interface ChatTransport { suspend fun execute(request: ChatRequest): ChatResponse }

class ChatCompletionClient(
    private val clock: Clock = Clock.systemUTC(),
    private val transport: ChatTransport = HttpsChatTransport()
) {
    suspend fun complete(
        configuration: AiConfiguration,
        body: String,
        beforeSend: suspend () -> Unit = {}
    ): String {
        val config = AiSettings.validateCredentials(configuration)
        val request = ChatRequest(URL(config.endpoint), config.apiKey, body)
        beforeSend()
        val response = try {
            transport.execute(request)
        } catch (failure: AiFailure) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            throw AiFailure("无法连接 AI 服务，请检查网络后重试", retryable = true)
        } catch (_: Exception) {
            throw AiFailure("AI 请求失败，未保存任何 AI 结果")
        }
        when (response.status) {
            in 200..299 -> return content(response.body)
            401, 403 -> throw AiFailure("API Key 无效或无权使用该模型")
            402 -> throw AiFailure("AI 服务余额不足，请在服务商处处理")
            429 -> throw AiFailure("AI 服务请求过多，请稍后重试", true, retryAfter(response.headers))
            in 500..599 -> throw AiFailure("AI 服务暂时不可用，请稍后重试", true, retryAfter(response.headers))
            in 300..399 -> throw AiFailure("AI 服务地址发生重定向；为保护 API Key，已拒绝继续")
            else -> throw AiFailure("AI 服务拒绝了请求，请检查服务地址、模型和配置")
        }
    }

    private fun content(body: String): String = try {
        val root = parseObject(body)
        val choices = requiredArray(root, "choices")
        require(choices.isNotEmpty())
        val choice = choices.first().requiredObject()
        val finish = requiredString(choice, "finish_reason")
        if (finish != "stop") throw AiFailure("AI 返回内容不完整，未保存 AI 结果")
        val content = requiredString(requiredObject(choice, "message"), "content")
        if (content.isBlank()) throw AiFailure("AI 返回了空内容，未保存 AI 结果")
        content
    } catch (failure: AiFailure) {
        throw failure
    } catch (_: Exception) {
        throw AiFailure("AI 返回格式无效，未保存 AI 结果")
    }

    private fun retryAfter(headers: Map<String, List<String>>): Long? {
        val raw = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        raw.toLongOrNull()?.let { seconds ->
            if (seconds < 0 || seconds > Long.MAX_VALUE / 1000) return null
            return seconds * 1000
        }
        return try {
            val at = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (at - clock.millis()).coerceAtLeast(0)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    companion object {
        private val JSON = Json { isLenient = false; ignoreUnknownKeys = false; explicitNulls = true }

        fun normalizeEndpoint(value: String): URI {
            val raw = value.trim()
            require(raw.isNotEmpty() && raw.length <= 2048) { "请填写有效的 HTTPS 服务地址" }
            val uri = try {
                URI(raw)
            } catch (_: Exception) {
                throw IllegalArgumentException("请填写有效的 HTTPS 服务地址")
            }
            require(
                uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null &&
                    uri.rawQuery == null && uri.rawFragment == null
            ) { "服务地址必须是无账号、查询参数或片段的 HTTPS 地址" }
            require(
                uri.rawPath.orEmpty().let { !it.contains("/../") && !it.contains("/./") } && uri.rawPath == uri.path
            ) { "服务地址路径无效" }
            val path = uri.path.orEmpty().trimEnd('/')
            val finalPath = when {
                path.endsWith("/chat/completions") -> path
                path.endsWith("/v1") -> "$path/chat/completions"
                else -> "$path/v1/chat/completions"
            }.ifEmpty { "/v1/chat/completions" }
            return URI("https", null, uri.host, uri.port, finalPath, null, null)
        }

        private fun parseObject(value: String) = JSON.parseToJsonElement(value).requiredObject()
        private fun requiredArray(value: JsonObject, key: String): JsonArray =
            value[key]?.let { require(it is JsonArray); it } as JsonArray
        private fun requiredObject(value: JsonObject, key: String): JsonObject =
            value[key]?.requiredObject() ?: throw IllegalArgumentException("missing $key")
        private fun requiredString(value: JsonObject, key: String): String {
            val primitive = value[key] as? JsonPrimitive ?: throw IllegalArgumentException("missing $key")
            require(primitive.isString)
            return primitive.content
        }
        private fun JsonElement.requiredObject(): JsonObject =
            this as? JsonObject ?: throw IllegalArgumentException("object required")
    }
}

internal class HttpsChatTransport : ChatTransport {
    override suspend fun execute(request: ChatRequest): ChatResponse = suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpsURLConnection?>()
        val timedOut = AtomicBoolean(false)
        val timeout = TIMER.schedule(
            { timedOut.set(true); connection.get()?.disconnect() },
            TOTAL_TIMEOUT_SECONDS,
            TimeUnit.SECONDS
        )
        continuation.invokeOnCancellation { connection.get()?.disconnect() }
        IO.execute {
            try {
                val opened = request.url.openConnection() as? HttpsURLConnection ?: throw IOException("HTTPS required")
                connection.set(opened)
                if (!continuation.isActive || timedOut.get()) {
                    opened.disconnect()
                    return@execute
                }
                opened.instanceFollowRedirects = false
                opened.connectTimeout = TOTAL_TIMEOUT_MILLIS
                opened.readTimeout = TOTAL_TIMEOUT_MILLIS
                opened.requestMethod = "POST"
                opened.doOutput = true
                opened.setRequestProperty("Authorization", "Bearer ${request.apiKey}")
                opened.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                opened.setRequestProperty("Accept", "application/json")
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                opened.setFixedLengthStreamingMode(bytes.size)
                opened.outputStream.use { it.write(bytes) }
                val status = opened.responseCode
                val body = if (status in 200..299) readBounded(opened) else ""
                val headers = opened.headerFields.entries
                    .filter { it.key != null && it.value != null }
                    .associate { it.key!! to it.value!!.toList() }
                if (continuation.isActive) continuation.resume(ChatResponse(status, headers, body))
            } catch (failure: AiFailure) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            } catch (_: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        if (timedOut.get()) AiFailure("AI 请求超时，请稍后重试", true)
                        else AiFailure("无法连接 AI 服务，请检查网络后重试", true)
                    )
                }
            } catch (_: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(AiFailure("AI 请求失败，未保存任何 AI 结果"))
                }
            } finally {
                timeout.cancel(false)
                connection.getAndSet(null)?.disconnect()
            }
        }
    }

    private fun readBounded(connection: HttpURLConnection): String {
        val declared = connection.contentLengthLong
        if (declared > MAX_RESPONSE_BYTES) throw AiFailure("AI 返回数据过大，未保存 AI 结果")
        val output = ByteArrayOutputStream(if (declared in 1..MAX_RESPONSE_BYTES) declared.toInt() else 8192)
        connection.inputStream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) {
                    throw AiFailure("AI 返回数据过大，未保存 AI 结果")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val TOTAL_TIMEOUT_SECONDS = 120L
        const val TOTAL_TIMEOUT_MILLIS = 120_000
        val IO = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "chat-completion-http").apply { isDaemon = true }
        }
        val TIMER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "chat-completion-timeout").apply { isDaemon = true }
        }
    }
}
