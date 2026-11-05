package app.liferecord.core

import java.time.LocalDate

/** UI never carries API credentials. Persisted sources contain identifiers and fingerprints, not raw copies. */
data class AiConfiguration(val endpoint: String = "", val apiKey: String = "", val model: String = "", val consent: Boolean = false, val automatic: Boolean = false)
data class ReportSource(val id: String, val version: String)
data class ReportParagraph(val text: String, val recordIds: List<String>, val answerIds: List<String> = emptyList())
data class ReportQuestion(
    val id: String,
    val recordIds: List<String>,
    val text: String,
    val answer: String = "",
    val answeredAt: String? = null,
    val skipped: Boolean = false,
    val applicable: Boolean = true,
    /** Observed answer entity heads captured for UI editing; never encoded in report-definition payloads. */
    val heads: Set<String> = emptySet(),
)
data class GeneratedReport(val paragraphs: List<ReportParagraph>, val questions: List<ReportQuestion>)
data class DailyReport(val date: String, val paragraphs: List<ReportParagraph>, val sources: List<ReportSource>, val inputHash: String, val generatedAt: String, val service: String, val model: String)
data class ReportInput(
    val date: String,
    val records: List<Record>,
    val questions: List<ReportQuestion>,
    val hash: String,
    val sources: List<ReportSource>,
    val claimToken: String? = null,
    val baseReportHeads: Set<String> = emptySet(),
)
data class ReportDay(val date: String, val report: DailyReport?, val questions: List<ReportQuestion>, val records: List<Record>, val changed: Boolean, val message: String, val running: Boolean, val retryAt: Long? = null)
data class ReportUiState(val date: String = LocalDate.now().minusDays(1).toString(), val day: ReportDay? = null, val loading: Boolean = true, val message: String = "", val configured: Boolean = false, val automatic: Boolean = false, val configuration: AiConfiguration = AiConfiguration(), val testing: Boolean = false)

class AiFailure(val userMessage: String, val retryable: Boolean = false, val retryAfterMillis: Long? = null) : Exception(userMessage)

interface ReportAi {
    suspend fun generate(configuration: AiConfiguration, input: ReportInput): GeneratedReport
    suspend fun test(configuration: AiConfiguration)
}
