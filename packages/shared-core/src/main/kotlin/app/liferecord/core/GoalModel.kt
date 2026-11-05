package app.liferecord.core

import kotlinx.serialization.Serializable

@Serializable
data class GoalMilestone(val id: String, val criterion: String)

@Serializable
data class GoalSpec(
    val id: String,
    val title: String,
    val desiredResult: String,
    val startDate: String,
    val endDate: String,
    val reviewZone: String,
    val cadenceDays: Int,
    val milestones: List<GoalMilestone>,
    val archived: Boolean,
)

data class GoalEditSession(val goalId: String, val baseHeads: Set<String>, val initial: GoalSpec?)

@Serializable
data class GoalReviewKey(val goalId: String, val fromDate: String, val throughDate: String) {
    val encoded: String get() = "$goalId/$fromDate/$throughDate"

    companion object {
        fun decode(value: String): GoalReviewKey {
            val parts = value.split('/')
            require(parts.size == 3) { "目标回顾标识格式无效" }
            return GoalCodec.validateReviewKey(GoalReviewKey(parts[0], parts[1], parts[2]))
        }
    }
}

@Serializable
enum class GoalDirection { ADVANCING, STALLED, OFF_TRACK, INSUFFICIENT_EVIDENCE }

@Serializable
enum class GoalRunState { PREPARING, RUNNING, RESULT_READY, SUCCEEDED, FAILED, UNKNOWN, CANCELLED, INVALIDATED }

@Serializable
enum class GoalEvidenceKind { RECORD, ANSWER, REPORT }

@Serializable
data class GoalEvidenceRef(
    val id: String,
    val kind: GoalEvidenceKind,
    val entityKey: String,
    val version: String,
    val recordIds: Set<String>,
)

@Serializable
data class GoalMilestoneConfirmation(
    val goalId: String,
    val milestoneId: String,
    val criterionHash: String,
    val criterionSnapshot: String,
    val confirmed: Boolean,
    val note: String,
    val evidence: List<GoalEvidenceRef>,
    val confirmedAt: String,
)

@Serializable
data class GoalEvidenceDependency(
    val ref: GoalEvidenceRef,
    val heads: Set<String>,
    val sourceVersions: Map<String, String>,
)

@Serializable
data class GoalInputEvidence(val dependency: GoalEvidenceDependency, val text: String)

@Serializable
data class GoalConfirmationSnapshot(val value: GoalMilestoneConfirmation, val heads: Set<String>)

@Serializable
data class GoalCoverageDay(
    val date: String,
    val recordCount: Int,
    val reportStatus: String,
    val unanswered: Int,
    val skipped: Int,
    val inapplicable: Int,
)

@Serializable
data class GoalEvidenceDay(
    val records: List<GoalInputEvidence>,
    val answers: List<GoalInputEvidence>,
    val reportParagraphs: List<GoalInputEvidence>,
    val coverage: GoalCoverageDay,
)

@Serializable
data class GoalAnalysisInput(
    val key: GoalReviewKey,
    val goal: GoalSpec,
    val goalHeads: Set<String>,
    val confirmations: List<GoalConfirmationSnapshot>,
    val evidence: List<GoalInputEvidence>,
    val coverage: List<GoalCoverageDay>,
    val hash: String,
    val baseReviewHeads: Set<String>,
    val runId: String? = null,
)

@Serializable
data class GoalEvidenceClaim(val text: String, val evidenceIds: List<String>)

@Serializable
data class GoalMilestoneSuggestion(val milestoneId: String, val text: String, val evidenceIds: List<String>)

@Serializable
data class GeneratedGoalReview(
    val direction: GoalDirection,
    val assessment: GoalEvidenceClaim,
    val observations: List<GoalEvidenceClaim>,
    val achievements: List<GoalEvidenceClaim>,
    val recurringProblems: List<GoalEvidenceClaim>,
    val positiveFeedback: List<GoalEvidenceClaim>,
    val milestoneSuggestions: List<GoalMilestoneSuggestion>,
    val nextActions: List<GoalEvidenceClaim>,
    val unknowns: List<String>,
)

@Serializable
data class GoalReviewContext(
    val runId: String,
    val key: GoalReviewKey,
    val goalSnapshot: GoalSpec,
    val goalHeads: Set<String>,
    val confirmationSnapshot: List<GoalConfirmationSnapshot>,
    val inputHash: String,
    val dependencies: List<GoalEvidenceDependency>,
    val coverage: List<GoalCoverageDay>,
    val baseReviewHeads: Set<String>,
    val service: String,
    val model: String,
)

@Serializable
data class GoalReviewPayload(
    val key: GoalReviewKey,
    val goalSnapshot: GoalSpec,
    val goalHeads: Set<String>,
    val confirmationSnapshot: List<GoalConfirmationSnapshot>,
    val inputHash: String,
    val dependencies: List<GoalEvidenceDependency>,
    val coverage: List<GoalCoverageDay>,
    val direction: GoalDirection,
    val assessment: GoalEvidenceClaim,
    val observations: List<GoalEvidenceClaim>,
    val achievements: List<GoalEvidenceClaim>,
    val recurringProblems: List<GoalEvidenceClaim>,
    val positiveFeedback: List<GoalEvidenceClaim>,
    val milestoneSuggestions: List<GoalMilestoneSuggestion>,
    val nextActions: List<GoalEvidenceClaim>,
    val unknowns: List<String>,
    val generatedAt: String,
    val service: String,
    val model: String,
)

data class GoalSummary(
    val spec: GoalSpec?,
    val heads: Set<String>,
    val completed: Int,
    val total: Int,
    val percent: Int?,
    val conflicted: Boolean,
)

data class GoalDetail(
    val summary: GoalSummary,
    val confirmations: List<GoalConfirmationSnapshot>,
    val conflicts: List<EntityState>,
    val reviews: List<GoalReviewPayload> = emptyList(),
)


data class GoalReviewLease(
    val key: GoalReviewKey,
    val runId: String,
    val holder: String,
    val inputHash: String,
    val token: String,
    val state: String,
    val expiresAt: Long,
)

interface GoalReviewGenerationGate {
    suspend fun beforeCapture(key: GoalReviewKey, automatic: Boolean)
    suspend fun run(input: GoalAnalysisInput, automatic: Boolean, generate: suspend () -> Unit)
}
