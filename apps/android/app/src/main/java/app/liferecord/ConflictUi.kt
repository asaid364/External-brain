package app.liferecord

import app.liferecord.core.EntityState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Display-only projection. Version IDs remain the action baseline, never timestamps. */
fun conflictUi(entities: List<EntityState>, endedDraftIds: Set<String> = emptySet()): ConflictScreenUiState {
    fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun origin(headId: String): String = headId.substringBefore('/').ifBlank { "未知来源" }

    return ConflictScreenUiState(
        recordConflicts = entities.filter { it.kind == "record" }.map { entity ->
            RecordConflictItem(entity.key, entity.heads.mapNotNull { head ->
                val payload = head.payload as? JsonObject ?: return@mapNotNull null
                RecordConflictHead(
                    headId = head.id,
                    originName = origin(head.id),
                    modifiedTime = payload.text("modified").ifBlank { payload.text("created") },
                    eventTime = payload.text("local"),
                    zone = listOf(payload.text("zone"), payload.text("offset")).filter { it.isNotBlank() }.joinToString(" "),
                    text = payload.text("text"),
                    isDeleted = payload.text("deleted").isNotBlank(),
                )
            })
        },
        draftConflicts = entities.filter { it.kind == "draft-slot" }.map { entity ->
            val payloads = entity.heads.mapNotNull { it.payload as? JsonObject }
            DraftConflictItem(
                slot = entity.key,
                targetRecordId = payloads.firstNotNullOfOrNull { it.text("targetRecordId").ifBlank { null } },
                heads = entity.heads.mapNotNull { head ->
                    val payload = head.payload as? JsonObject ?: return@mapNotNull null
                    val mode = payload.text("mode")
                    DraftConflictHead(
                        headId = head.id,
                        originName = origin(head.id),
                        updatedAt = payload.text("updatedAt").ifBlank { payload.text("local") },
                        text = payload.text("text").ifBlank {
                            when (mode) {
                                "SAVED" -> "该草稿已在另一端保存；这是终结候选。"
                                "DISCARDED" -> "该草稿已在另一端放弃；这是终结候选。"
                                "DRAFT_EMPTY" -> "该草稿已明确清空；这是终结候选。"
                                else -> "草稿终结状态：${mode.ifBlank { "未知" }}"
                            }
                        },
                        local = payload.text("local"),
                        isTerminal = mode.isNotBlank(),
                        mode = mode.ifBlank { null },
                        isEndedGeneration = mode.isBlank() && payload.text("id") in endedDraftIds,
                    )
                },
            )
        },
        reportConflicts = entities.filter { it.kind == "report" }.map { entity ->
            ReportConflictItem(entity.key, entity.heads.mapNotNull { head ->
                val payload = head.payload as? JsonObject ?: return@mapNotNull null
                val report = payload["report"] as? JsonObject
                val cleared = (payload["cleared"] as? JsonPrimitive)?.booleanOrNull == true
                ReportConflictCandidate(
                    headId = head.id,
                    model = report?.text("model").orEmpty().ifBlank { if (cleared) "明确清空" else "未知模型" },
                    generatedAt = report?.text("generatedAt").orEmpty(),
                    body = (report?.get("paragraphs") as? JsonArray)?.joinToString("\n\n") {
                        (it as? JsonObject)?.text("text").orEmpty()
                    }?.ifBlank { "正文已明确清空" } ?: "正文已明确清空",
                    sourceCount = (report?.get("sources") as? JsonArray)?.size ?: 0,
                )
            })
        },
        answerConflicts = entities.filter { it.kind == "answer" }.map { entity ->
            val first = entity.heads.firstNotNullOfOrNull { it.payload as? JsonObject }
            val keyDate = entity.key.substringBefore('/')
            val keyId = entity.key.substringAfter('/', "")
            AnswerConflictItem(
                date = first?.text("date").orEmpty().ifBlank { keyDate },
                questionId = first?.text("id").orEmpty().ifBlank { keyId },
                questionText = first?.text("questionText").orEmpty().ifBlank { "题面未知（原证据仍保留）" },
                candidates = entity.heads.mapNotNull { head ->
                    val answer = head.payload as? JsonObject ?: return@mapNotNull null
                    AnswerConflictCandidate(
                        headId = head.id,
                        originName = origin(head.id),
                        answeredAt = answer.text("answeredAt"),
                        text = answer.text("answer"),
                        skipped = (answer["skipped"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    )
                },
            )
        },
        goalConflicts = entities.filter { it.kind in setOf("goal", "goal-confirmation", "goal-review") },
    )
}
