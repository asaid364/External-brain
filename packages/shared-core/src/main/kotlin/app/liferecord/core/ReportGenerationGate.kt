package app.liferecord.core

/** Optional platform coordination around a billable run; never used for a pending local-save retry. */
fun interface ReportGenerationGate {
    suspend fun run(input: ReportInput, automatic: Boolean, generate: suspend () -> Unit)
}
