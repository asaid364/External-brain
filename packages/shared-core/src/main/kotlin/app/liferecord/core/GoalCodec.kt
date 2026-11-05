package app.liferecord.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

object GoalCodec {
    const val GOAL_KIND: String = "goal"
    const val CONFIRMATION_KIND: String = "goal-confirmation"
    const val REVIEW_KIND: String = "goal-review"

    val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        encodeDefaults = true
    }

    fun encodeGoal(value: GoalSpec): JsonElement = json.encodeToJsonElement(validateGoal(value))

    fun decodeGoal(value: JsonElement): GoalSpec = decode("目标") {
        requireKeys(value, GOAL_FIELDS)
        validateGoal(json.decodeFromJsonElement(value))
    }

    fun encodeConfirmation(value: GoalMilestoneConfirmation): JsonElement =
        json.encodeToJsonElement(validateConfirmation(value))

    fun decodeConfirmation(value: JsonElement): GoalMilestoneConfirmation = decode("里程碑确认") {
        requireKeys(value, CONFIRMATION_FIELDS)
        validateConfirmation(json.decodeFromJsonElement(value))
    }

    fun encodeReview(value: GoalReviewPayload): JsonElement =
        json.encodeToJsonElement(validateReview(value))

    fun decodeReview(value: JsonElement): GoalReviewPayload = decode("目标回顾") {
        requireKeys(value, REVIEW_FIELDS)
        validateReview(json.decodeFromJsonElement(value))
    }

    fun encodeContext(value: GoalReviewContext): JsonElement =
        json.encodeToJsonElement(validateContext(value))

    fun decodeContext(value: JsonElement): GoalReviewContext = decode("目标回顾上下文") {
        requireKeys(value, CONTEXT_FIELDS)
        validateContext(json.decodeFromJsonElement(value))
    }

    fun encodeResult(value: GeneratedGoalReview): JsonElement =
        json.encodeToJsonElement(validateResult(value))

    fun decodeResult(value: JsonElement): GeneratedGoalReview = decode("AI目标回顾结果") {
        requireKeys(value, RESULT_FIELDS)
        validateResult(json.decodeFromJsonElement(value))
    }

    fun decodeResult(value: JsonElement, input: GoalAnalysisInput): GeneratedGoalReview = decode("AI目标回顾结果") {
        requireKeys(value, RESULT_FIELDS)
        validateResult(json.decodeFromJsonElement(value), input)
    }

    fun encodeGoalString(value: GoalSpec): String = json.encodeToString(validateGoal(value))
    fun decodeGoalString(value: String): GoalSpec = decodeGoal(json.parseToJsonElement(value))
    fun encodeConfirmationString(value: GoalMilestoneConfirmation): String =
        json.encodeToString(validateConfirmation(value))
    fun decodeConfirmationString(value: String): GoalMilestoneConfirmation =
        decodeConfirmation(json.parseToJsonElement(value))
    fun encodeReviewString(value: GoalReviewPayload): String = json.encodeToString(validateReview(value))
    fun decodeReviewString(value: String): GoalReviewPayload = decodeReview(json.parseToJsonElement(value))
    fun encodeContextString(value: GoalReviewContext): String = json.encodeToString(validateContext(value))
    fun decodeContextString(value: String): GoalReviewContext = decodeContext(json.parseToJsonElement(value))
    fun encodeResultString(value: GeneratedGoalReview): String = json.encodeToString(validateResult(value))
    fun decodeResultString(value: String): GeneratedGoalReview = decodeResult(json.parseToJsonElement(value))
    fun decodeResultString(value: String, input: GoalAnalysisInput): GeneratedGoalReview =
        decodeResult(json.parseToJsonElement(value), input)

    fun validateResult(value: GeneratedGoalReview, input: GoalAnalysisInput): GeneratedGoalReview {
        val goal = validateGoal(input.goal)
        require(input.key.goalId == goal.id) { "分析输入目标与回顾窗口不一致" }
        val confirmations = input.confirmations.map { validateConfirmationSnapshot(it, goal.id) }
        val dependencies = validateDependencies(input.evidence.map { it.dependency })
        return validateResult(value, dependencies, goal, confirmations)
    }

    fun validateGoal(value: GoalSpec): GoalSpec {
        requireUuid(value.id, "目标ID")
        val title = value.title.trim().also { require(it.isNotEmpty()) { "目标标题不能为空" } }
        val desiredResult = value.desiredResult.trim().also { require(it.isNotEmpty()) { "期望成果不能为空" } }
        val start = parseDate(value.startDate, "目标开始日期")
        val end = parseDate(value.endDate, "目标截止日期")
        require(!end.isBefore(start)) { "目标截止日期不能早于开始日期" }
        require(value.reviewZone in ZoneId.getAvailableZoneIds()) { "目标回顾时区必须为IANA时区" }
        val totalDays = ChronoUnit.DAYS.between(start, end) + 1L
        require(value.cadenceDays > 0) { "回顾周期必须为正整数" }
        require(value.cadenceDays.toLong() <= totalDays) { "回顾周期不能超过目标总自然日数" }
        require(value.milestones.isNotEmpty()) { "目标至少需要一项完成标准" }
        val ids = hashSetOf<String>()
        val milestones = value.milestones.map { milestone ->
            requireUuid(milestone.id, "里程碑ID")
            require(ids.add(milestone.id)) { "里程碑ID不能重复" }
            GoalMilestone(
                id = milestone.id,
                criterion = milestone.criterion.trim().also { require(it.isNotEmpty()) { "完成标准不能为空" } },
            )
        }
        return value.copy(title = title, desiredResult = desiredResult, reviewZone = ZoneId.of(value.reviewZone).id, milestones = milestones)
    }

    fun validateConfirmation(value: GoalMilestoneConfirmation): GoalMilestoneConfirmation {
        requireUuid(value.goalId, "目标ID")
        requireUuid(value.milestoneId, "里程碑ID")
        val snapshot = value.criterionSnapshot.trim().also { require(it.isNotEmpty()) { "确认标准快照不能为空" } }
        requireHash(value.criterionHash, "完成标准摘要")
        require(value.criterionHash == criterionHash(snapshot)) { "完成标准摘要与快照不一致" }
        val confirmedAt = parseInstant(value.confirmedAt, "确认时间").toString()
        val evidence = value.evidence.map(::validateEvidenceRef)
        require(evidence.map { it.id }.toSet().size == evidence.size) { "确认依据ID不能重复" }
        return value.copy(
            criterionSnapshot = snapshot,
            note = value.note.trim(),
            evidence = evidence,
            confirmedAt = confirmedAt,
        )
    }

    fun criterionHash(criterion: String): String =
        SyncCodec.sha256(criterion.trim().toByteArray(Charsets.UTF_8))

    /** Digest of analysis-relevant goal semantics. Archiving is deliberately excluded. */
    fun specHash(value: GoalSpec): String {
        val goal = validateGoal(value)
        val canonical = JsonObject(linkedMapOf(
            "id" to JsonPrimitive(goal.id),
            "title" to JsonPrimitive(goal.title),
            "desiredResult" to JsonPrimitive(goal.desiredResult),
            "startDate" to JsonPrimitive(goal.startDate),
            "endDate" to JsonPrimitive(goal.endDate),
            "reviewZone" to JsonPrimitive(goal.reviewZone),
            "cadenceDays" to JsonPrimitive(goal.cadenceDays),
            "milestones" to json.encodeToJsonElement(goal.milestones),
        ))
        return SyncCodec.sha256(canonical.toString().toByteArray(Charsets.UTF_8))
    }

    fun validatePayload(kind: String, key: String, payload: JsonElement): JsonElement = when (kind) {
        GOAL_KIND -> encodeGoal(decodeGoal(payload)).also { validated ->
            require((validated as JsonObject).getValue("id").asString() == key) { "目标键与内容不一致" }
        }
        CONFIRMATION_KIND -> encodeConfirmation(decodeConfirmation(payload)).also { validated ->
            val confirmation = decodeConfirmation(validated)
            require(key == "${confirmation.goalId}/${confirmation.milestoneId}") { "里程碑确认键与内容不一致" }
        }
        REVIEW_KIND -> encodeReview(decodeReview(payload)).also { validated ->
            require(decodeReview(validated).key.encoded == key) { "目标回顾键与内容不一致" }
        }
        else -> throw IllegalArgumentException("不支持的目标载荷类型：$kind")
    }

    fun validateReviewKey(value: GoalReviewKey): GoalReviewKey {
        requireUuid(value.goalId, "目标ID")
        val from = parseDate(value.fromDate, "回顾开始日期")
        val through = parseDate(value.throughDate, "回顾结束日期")
        require(!through.isBefore(from)) { "回顾结束日期不能早于开始日期" }
        return value
    }

    private fun validateReview(value: GoalReviewPayload): GoalReviewPayload {
        val key = validateReviewKey(value.key)
        val goal = validateGoal(value.goalSnapshot)
        require(key.goalId == goal.id) { "回顾目标与目标快照不一致" }
        validateWindowWithinGoal(key, goal)
        requireNonBlankSet(value.goalHeads, "目标版本")
        requireHash(value.inputHash, "分析输入摘要")
        val confirmations = value.confirmationSnapshot.map { validateConfirmationSnapshot(it, goal.id) }
        val dependencies = validateDependencies(value.dependencies)
        val coverage = validateCoverage(value.coverage, key)
        val result = validateResult(value.toGeneratedResult(), dependencies, goal, confirmations)
        val generatedAt = parseInstant(value.generatedAt, "回顾生成时间").toString()
        val service = value.service.trim().also { require(it.isNotEmpty()) { "AI服务不能为空" } }
        val model = value.model.trim().also { require(it.isNotEmpty()) { "AI模型不能为空" } }
        return value.copy(
            key = key,
            goalSnapshot = goal,
            goalHeads = sortedSet(value.goalHeads),
            confirmationSnapshot = confirmations,
            dependencies = dependencies,
            coverage = coverage,
            assessment = result.assessment,
            observations = result.observations,
            achievements = result.achievements,
            recurringProblems = result.recurringProblems,
            positiveFeedback = result.positiveFeedback,
            milestoneSuggestions = result.milestoneSuggestions,
            nextActions = result.nextActions,
            unknowns = result.unknowns,
            generatedAt = generatedAt,
            service = service,
            model = model,
        )
    }

    private fun validateContext(value: GoalReviewContext): GoalReviewContext {
        requireUuid(value.runId, "运行ID")
        val key = validateReviewKey(value.key)
        val goal = validateGoal(value.goalSnapshot)
        require(key.goalId == goal.id) { "回顾目标与目标快照不一致" }
        validateWindowWithinGoal(key, goal)
        requireNonBlankSet(value.goalHeads, "目标版本")
        requireHash(value.inputHash, "分析输入摘要")
        val confirmations = value.confirmationSnapshot.map { validateConfirmationSnapshot(it, goal.id) }
        val dependencies = validateDependencies(value.dependencies)
        val coverage = validateCoverage(value.coverage, key)
        require(value.baseReviewHeads.none { it.isBlank() }) { "基础回顾版本不能为空" }
        val service = value.service.trim().also { require(it.isNotEmpty()) { "AI服务不能为空" } }
        val model = value.model.trim().also { require(it.isNotEmpty()) { "AI模型不能为空" } }
        return value.copy(
            key = key,
            goalSnapshot = goal,
            goalHeads = sortedSet(value.goalHeads),
            confirmationSnapshot = confirmations,
            dependencies = dependencies,
            coverage = coverage,
            baseReviewHeads = sortedSet(value.baseReviewHeads),
            service = service,
            model = model,
        )
    }

    private fun validateResult(value: GeneratedGoalReview): GeneratedGoalReview =
        validateResult(value, null, null, emptyList())

    private fun validateResult(
        value: GeneratedGoalReview,
        dependencies: List<GoalEvidenceDependency>?,
        goal: GoalSpec?,
        confirmations: List<GoalConfirmationSnapshot>,
    ): GeneratedGoalReview {
        val knownIds = dependencies?.mapTo(hashSetOf()) { it.ref.id }
        val allowUnreferenced = value.direction == GoalDirection.INSUFFICIENT_EVIDENCE
        val assessment = validateClaim(value.assessment, "方向评价", knownIds, allowUnreferenced)
        val observations = value.observations.map { validateClaim(it, "观察", knownIds, false) }
        val achievements = value.achievements.map { validateClaim(it, "成果", knownIds, false) }
        val recurring = value.recurringProblems.map { claim ->
            val normalized = validateClaim(claim, "重复问题", knownIds, false)
            if (dependencies != null) {
                val records = dependencies.filter { it.ref.id in normalized.evidenceIds }
                    .flatMapTo(hashSetOf()) { it.ref.recordIds }
                require(records.size >= 2) { "重复问题必须涉及至少两条不同底层记录" }
            }
            normalized
        }
        val feedback = value.positiveFeedback.map { validateClaim(it, "正反馈", knownIds, false) }
        require(value.nextActions.size <= 3) { "下一步建议不能超过三条" }
        val actions = value.nextActions.map { validateClaim(it, "下一步建议", knownIds, allowUnreferenced) }
        val suggestions = value.milestoneSuggestions.map { suggestion ->
            requireUuid(suggestion.milestoneId, "建议里程碑ID")
            val text = suggestion.text.trim().also { require(it.isNotEmpty()) { "里程碑建议不能为空" } }
            val ids = validateEvidenceIds(suggestion.evidenceIds, "里程碑建议", knownIds, false)
            GoalMilestoneSuggestion(suggestion.milestoneId, text, ids)
        }
        require(suggestions.map { it.milestoneId }.toSet().size == suggestions.size) { "同一里程碑不能重复建议" }
        if (goal != null) {
            val currentIds = goal.milestones.mapTo(hashSetOf()) { it.id }
            val confirmedIds = confirmations.filter { it.value.confirmed }.mapTo(hashSetOf()) { it.value.milestoneId }
            suggestions.forEach {
                require(it.milestoneId in currentIds) { "里程碑建议引用未知标准" }
                require(it.milestoneId !in confirmedIds) { "不能建议已确认的里程碑" }
            }
        }
        val unknowns = value.unknowns.map { it.trim().also { text -> require(text.isNotEmpty()) { "信息缺口不能为空" } } }
        return value.copy(
            assessment = assessment,
            observations = observations,
            achievements = achievements,
            recurringProblems = recurring,
            positiveFeedback = feedback,
            milestoneSuggestions = suggestions,
            nextActions = actions,
            unknowns = unknowns,
        )
    }

    private fun validateClaim(
        value: GoalEvidenceClaim,
        label: String,
        knownIds: Set<String>?,
        allowUnreferenced: Boolean,
    ): GoalEvidenceClaim {
        val text = value.text.trim().also { require(it.isNotEmpty()) { "$label 内容不能为空" } }
        return GoalEvidenceClaim(text, validateEvidenceIds(value.evidenceIds, label, knownIds, allowUnreferenced))
    }

    private fun validateEvidenceIds(
        values: List<String>,
        label: String,
        knownIds: Set<String>?,
        allowEmpty: Boolean,
    ): List<String> {
        require(values.none { it.isBlank() }) { "$label 的依据ID不能为空" }
        require(values.toSet().size == values.size) { "$label 的依据ID不能重复" }
        require(allowEmpty || values.isNotEmpty()) { "$label 必须引用依据" }
        if (knownIds != null) require(values.all { it in knownIds }) { "$label 引用了未知依据" }
        return values
    }

    private fun validateConfirmationSnapshot(value: GoalConfirmationSnapshot, goalId: String): GoalConfirmationSnapshot {
        val confirmation = validateConfirmation(value.value)
        require(confirmation.goalId == goalId) { "确认快照属于其他目标" }
        requireNonBlankSet(value.heads, "确认版本")
        return GoalConfirmationSnapshot(confirmation, sortedSet(value.heads))
    }

    private fun validateDependencies(values: List<GoalEvidenceDependency>): List<GoalEvidenceDependency> {
        val normalized = values.map { dependency ->
            val ref = validateEvidenceRef(dependency.ref)
            requireNonBlankSet(dependency.heads, "依据版本")
            require(dependency.sourceVersions.keys.none { it.isBlank() }) { "来源记录ID不能为空" }
            require(dependency.sourceVersions.values.none { it.isBlank() }) { "来源版本不能为空" }
            require(dependency.sourceVersions.keys == ref.recordIds) { "来源版本必须覆盖依据的全部底层记录" }
            GoalEvidenceDependency(ref, sortedSet(dependency.heads), dependency.sourceVersions.toSortedMap())
        }
        require(normalized.map { it.ref.id }.toSet().size == normalized.size) { "依据ID不能重复" }
        return normalized
    }

    private fun validateEvidenceRef(value: GoalEvidenceRef): GoalEvidenceRef {
        val entityKey = value.entityKey.trim().also { require(it.isNotEmpty()) { "依据实体键不能为空" } }
        val version = value.version.trim().also { require(it.isNotEmpty()) { "依据版本不能为空" } }
        require(value.recordIds.none { it.isBlank() }) { "底层记录ID不能为空" }
        val expectedPrefix = when (value.kind) {
            GoalEvidenceKind.RECORD -> "r:"
            GoalEvidenceKind.ANSWER -> "a:"
            GoalEvidenceKind.REPORT -> "d:"
        }
        require(value.id.startsWith(expectedPrefix) && value.id.length > 2) { "依据ID与类型不一致" }
        when (value.kind) {
            GoalEvidenceKind.RECORD -> {
                require(value.id == "r:$entityKey") { "记录依据ID与实体键不一致" }
                require(value.recordIds == setOf(entityKey)) { "记录依据必须引用自身记录" }
            }
            GoalEvidenceKind.ANSWER -> {
                require(value.id.removePrefix("a:") == entityKey) { "答案依据ID与实体键不一致" }
                validateDatedEntityKey(entityKey, "答案")
            }
            GoalEvidenceKind.REPORT -> {
                val suffix = value.id.removePrefix("d:")
                val separator = suffix.lastIndexOf('/')
                require(separator > 0 && suffix.substring(separator + 1).toIntOrNull()?.let { it >= 0 } == true) {
                    "日报段落依据ID格式无效"
                }
                parseDate(suffix.substring(0, separator), "日报段落日期")
                require(entityKey == suffix.substring(0, separator)) { "日报依据实体键与日期不一致" }
            }
        }
        return value.copy(entityKey = entityKey, version = version, recordIds = sortedSet(value.recordIds))
    }

    private fun validateDatedEntityKey(value: String, label: String) {
        val separator = value.indexOf('/')
        require(separator > 0 && separator < value.lastIndex) { "$label 实体键格式无效" }
        parseDate(value.substring(0, separator), "$label 日期")
    }

    private fun validateCoverage(values: List<GoalCoverageDay>, key: GoalReviewKey): List<GoalCoverageDay> {
        val from = LocalDate.parse(key.fromDate)
        val through = LocalDate.parse(key.throughDate)
        val seen = hashSetOf<String>()
        return values.map { day ->
            val date = parseDate(day.date, "覆盖日期")
            require(!date.isBefore(from) && !date.isAfter(through)) { "覆盖日期超出回顾窗口" }
            require(seen.add(day.date)) { "覆盖日期不能重复" }
            require(day.recordCount >= 0 && day.unanswered >= 0 && day.skipped >= 0 && day.inapplicable >= 0) {
                "覆盖计数不能为负数"
            }
            require(day.reportStatus in REPORT_STATUSES) { "日报覆盖状态无效" }
            day
        }
    }

    private fun validateWindowWithinGoal(key: GoalReviewKey, goal: GoalSpec) {
        val from = LocalDate.parse(key.fromDate)
        val through = LocalDate.parse(key.throughDate)
        require(!from.isBefore(LocalDate.parse(goal.startDate)) && !through.isAfter(LocalDate.parse(goal.endDate))) {
            "回顾窗口超出目标日期范围"
        }
    }


    private fun GoalReviewPayload.toGeneratedResult(): GeneratedGoalReview = GeneratedGoalReview(
        direction, assessment, observations, achievements, recurringProblems, positiveFeedback,
        milestoneSuggestions, nextActions, unknowns,
    )

    private fun requireKeys(value: JsonElement, expected: Set<String>) {
        val objectValue = value as? JsonObject ?: throw IllegalArgumentException("JSON必须为对象")
        require(objectValue.keys == expected) { "JSON字段必须严格匹配：${expected.sorted().joinToString()}" }
    }

    private inline fun <T> decode(label: String, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("$label JSON格式无效", e)
    }

    private fun requireUuid(value: String, label: String) {
        try {
            require(UUID.fromString(value).toString() == value)
        } catch (e: Exception) {
            throw IllegalArgumentException("$label 必须为UUID", e)
        }
    }

    private fun requireHash(value: String, label: String) {
        require(HEX_256.matches(value)) { "$label 必须为小写SHA-256" }
    }

    private fun parseDate(value: String, label: String): LocalDate = try {
        LocalDate.parse(value).also { require(it.toString() == value) }
    } catch (e: Exception) {
        throw IllegalArgumentException("$label 必须为ISO日期", e)
    }

    private fun parseInstant(value: String, label: String): Instant = try {
        Instant.parse(value)
    } catch (e: Exception) {
        throw IllegalArgumentException("$label 必须为ISO时间", e)
    }

    private fun requireNonBlankSet(value: Set<String>, label: String) {
        require(value.isNotEmpty() && value.none { it.isBlank() }) { "$label 不能为空" }
    }

    private fun sortedSet(values: Set<String>): Set<String> = values.toSortedSet().toCollection(linkedSetOf())
    private fun JsonElement.asString(): String = (this as JsonPrimitive).content

    private val HEX_256 = Regex("[0-9a-f]{64}")
    private val REPORT_STATUSES = setOf("MISSING", "CURRENT", "STALE")
    private val GOAL_FIELDS = setOf(
        "id", "title", "desiredResult", "startDate", "endDate", "reviewZone", "cadenceDays", "milestones", "archived",
    )
    private val CONFIRMATION_FIELDS = setOf(
        "goalId", "milestoneId", "criterionHash", "criterionSnapshot", "confirmed", "note", "evidence", "confirmedAt",
    )
    private val RESULT_FIELDS = setOf(
        "direction", "assessment", "observations", "achievements", "recurringProblems", "positiveFeedback",
        "milestoneSuggestions", "nextActions", "unknowns",
    )
    private val CONTEXT_FIELDS = setOf(
        "runId", "key", "goalSnapshot", "goalHeads", "confirmationSnapshot", "inputHash", "dependencies", "coverage",
        "baseReviewHeads", "service", "model",
    )
    private val REVIEW_FIELDS = setOf(
        "key", "goalSnapshot", "goalHeads", "confirmationSnapshot", "inputHash", "dependencies", "coverage", "direction",
        "assessment", "observations", "achievements", "recurringProblems", "positiveFeedback", "milestoneSuggestions",
        "nextActions", "unknowns", "generatedAt", "service", "model",
    )
}
