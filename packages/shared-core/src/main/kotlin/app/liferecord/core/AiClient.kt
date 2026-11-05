package app.liferecord.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.time.Clock

class AiClient(
    clock: Clock = Clock.systemUTC(),
    transport: ChatTransport = HttpsChatTransport()
) : ReportAi {
    private val completion = ChatCompletionClient(clock, transport)

    override suspend fun generate(configuration: AiConfiguration, input: ReportInput): GeneratedReport {
        val config = AiSettings.validate(configuration)
        require(input.records.isNotEmpty()) { "所选日期没有可生成日报的记录" }
        return parseReport(completion.complete(config, reportRequest(config, input)), input)
    }

    override suspend fun test(configuration: AiConfiguration) {
        val config = AiSettings.validate(configuration)
        val body = buildJsonObject {
            put("model", config.model)
            put("messages", buildJsonArray { add(buildJsonObject {
                put("role", "user")
                put("content", "连接测试。只回复 OK。不要复述这句话。")
            }) })
            put("max_tokens", 4)
            put("temperature", 0)
        }.toString()
        completion.complete(config, body)
    }

    private fun reportRequest(configuration: AiConfiguration, input: ReportInput): String {
        val records = buildJsonArray {
            input.records.sortedWith(compareBy<Record> { it.time.instant }.thenBy { it.id }).forEach { record ->
                add(buildJsonObject {
                    put("id", record.id); put("occurredAt", record.time.local); put("zone", record.time.zone)
                    put("offset", record.time.offset); put("text", record.text)
                })
            }
        }
        val priorQuestions = buildJsonArray {
            input.questions.filter { it.applicable }.sortedBy { it.id }.forEach { question ->
                add(buildJsonObject {
                    put("id", question.id)
                    put("recordIds", buildJsonArray { question.recordIds.sorted().forEach { add(JsonPrimitive(it)) } })
                    put("question", question.text); put("answer", question.answer)
                    put("skipped", question.skipped); put("applicable", question.applicable)
                })
            }
        }
        val payload = buildJsonObject {
            put("targetDate", input.date); put("records", records); put("previousQuestions", priorQuestions)
        }
        val system = """
            你是事实日报整理器。仅根据用户消息中的目标日期记录和已有回答，按发生时间升序写客观中文日报。
            不评价成长或效率，不推断情绪、动机、结果，不添加计划、建议或输入中不存在的事实。计划只作为“记录了某计划”，不得写成已完成。
            信息未知就保持未知。每条记录必须被至少一个段落引用；可合并相关记录。段落必须简洁、非空，recordIds 只能引用输入记录；answerIds 只能引用已有且非空的回答 ID。
            仅当重要活动缺少结果且其同一来源组没有已回答或已跳过的问题时，才提出问题；休息娱乐不强制追问。问题可以为 0 个，最多 3 个。
            只返回一个 JSON 对象，不要 Markdown 或解释。严格结构：
            {"paragraphs":[{"text":"...","recordIds":["..."],"answerIds":["..."]}],"questions":[{"text":"...","recordIds":["..."]}]}
        """.trimIndent()
        return buildJsonObject {
            put("model", configuration.model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", payload.toString()) })
            })
            put("temperature", 0)
        }.toString()
    }

    private fun parseReport(raw: String, input: ReportInput): GeneratedReport {
        try {
            val root = parseObject(unwrapJson(raw))
            requireKeys(root, setOf("paragraphs", "questions"))
            val knownRecords = input.records.map { it.id }.toSet()
            require(knownRecords.size == input.records.size)
            val usableAnswers = input.questions.filter { it.applicable && it.answer.isNotBlank() }.map { it.id }.toSet()
            val covered = mutableSetOf<String>()
            val paragraphs = requiredArray(root, "paragraphs").also { require(it.isNotEmpty()) }.map { element ->
                val value = element.requiredObject()
                requireKeys(value, setOf("text", "recordIds", "answerIds"))
                val text = requiredString(value, "text").trim()
                val recordIds = strings(requiredArray(value, "recordIds"))
                val answerIds = strings(requiredArray(value, "answerIds"))
                require(text.isNotEmpty() && recordIds.isNotEmpty() && recordIds.all { it in knownRecords })
                require(answerIds.all { it in usableAnswers })
                covered += recordIds
                ReportParagraph(text, recordIds, answerIds)
            }
            require(covered == knownRecords)
            val blockedGroups = input.questions.filter { it.applicable && (it.answer.isNotBlank() || it.skipped) }
                .map { groupKey(it.recordIds) }.toSet()
            val seenGroups = mutableSetOf<String>()
            val questions = requiredArray(root, "questions").also { require(it.size <= 3) }.map { element ->
                val value = element.requiredObject()
                requireKeys(value, setOf("text", "recordIds"))
                val text = requiredString(value, "text").trim()
                val ids = strings(requiredArray(value, "recordIds")).distinct().sorted()
                val group = groupKey(ids)
                require(text.isNotEmpty() && ids.isNotEmpty() && ids.all { it in knownRecords })
                require(group !in blockedGroups && seenGroups.add(group))
                ReportQuestion(questionId(ids), ids, text)
            }
            return GeneratedReport(paragraphs, questions)
        } catch (failure: AiFailure) {
            throw failure
        } catch (_: SerializationException) {
            throw AiFailure("AI 返回格式无效，未保存日报")
        } catch (_: IllegalArgumentException) {
            throw AiFailure("AI 返回内容未通过事实引用校验，未保存日报")
        } catch (_: Exception) {
            throw AiFailure("AI 返回格式无效，未保存日报")
        }
    }

    private fun unwrapJson(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstBreak = trimmed.indexOf('\n')
        require(firstBreak > 0)
        val tag = trimmed.substring(3, firstBreak).trim()
        require(tag.isEmpty() || tag.equals("json", ignoreCase = true))
        require(trimmed.endsWith("```"))
        return trimmed.substring(firstBreak + 1, trimmed.length - 3).trim().also { require(!it.contains("```")) }
    }


    companion object {
        private val JSON = Json { isLenient = false; ignoreUnknownKeys = false; explicitNulls = true }

        fun normalizeEndpoint(value: String): URI = ChatCompletionClient.normalizeEndpoint(value)

        fun questionId(recordIds: Collection<String>): String {
            val canonical = recordIds.distinct().sorted().joinToString("\u0000")
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return "q-" + digest.joinToString("") { "%02x".format(it) }
        }

        private fun parseObject(value: String) = JSON.parseToJsonElement(value).requiredObject()
        private fun groupKey(recordIds: Collection<String>) = recordIds.distinct().sorted().joinToString("\u0000")
        private fun requireKeys(value: JsonObject, expected: Set<String>) = require(value.keys == expected)
        private fun requiredArray(value: JsonObject, key: String): JsonArray = value[key]?.let { require(it is JsonArray); it } as JsonArray
        private fun requiredObject(value: JsonObject, key: String): JsonObject = value[key]?.requiredObject() ?: throw IllegalArgumentException("missing $key")
        private fun requiredString(value: JsonObject, key: String): String {
            val primitive = value[key] as? JsonPrimitive ?: throw IllegalArgumentException("missing $key")
            require(primitive.isString)
            return primitive.content
        }
        private fun JsonElement.requiredObject(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException("object required")
        private fun strings(array: JsonArray): List<String> = array.map {
            val primitive = it as? JsonPrimitive ?: throw IllegalArgumentException("string required")
            require(primitive.isString && primitive.content.isNotBlank()); primitive.content
        }
    }
}

