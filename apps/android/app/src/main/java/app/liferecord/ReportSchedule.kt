package app.liferecord

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import kotlinx.coroutines.launch
import java.time.*
import java.util.concurrent.TimeUnit

object ReportSchedule {
    fun inWindow(now: ZonedDateTime): Boolean = !now.toLocalTime().isBefore(LocalTime.of(0, 10)) && now.toLocalTime().isBefore(LocalTime.of(1, 0))
    fun next(now: ZonedDateTime): ZonedDateTime = now.toLocalDate().atTime(0, 10).atZone(now.zone).let { if (it.isAfter(now)) it else now.toLocalDate().plusDays(1).atTime(0, 10).atZone(now.zone) }
    fun schedule(context: Context, includeCurrentWindow: Boolean = false, append: Boolean = false) {
        val manager = WorkManager.getInstance(context)
        val enabled = runCatching { AppRuntime.get(context).settings.load().let { it.automatic && it.consent } }.getOrDefault(false)
        if (!enabled) { manager.cancelUniqueWork("daily-report-clock"); return }
        val now = ZonedDateTime.now()
        val delay = if (includeCurrentWindow && inWindow(now)) 0L else Duration.between(now, next(now)).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<DailyReportWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        manager.enqueueUniqueWork("daily-report-clock", if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else if (includeCurrentWindow) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }
}

class DailyReportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        var target: String? = null
        try {
            val runtime = AppRuntime.get(applicationContext)
            runtime.ready.await()
            val now = ZonedDateTime.now()
            if (ReportSchedule.inWindow(now)) {
                val day = now.toLocalDate().minusDays(1).toString()
                target = day
                runtime.engine.request(day, automatic = true)
                runtime.engine.awaitCompletion(day)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            val runtime = AppRuntime.get(applicationContext)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { target?.let { runtime.engine.cancel(it) } }
            throw cancelled
        } catch (_: Exception) {
            // No Worker retry: request outcomes are durable, missed/interrupted runs require explicit action.
        } finally { ReportSchedule.schedule(applicationContext, append = true) }
        return Result.success()
    }
}

class ReportClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_BOOT_COMPLETED)) {
            val result = goAsync()
            AppRuntime.get(context).scope.launchSchedule(context.applicationContext, result)
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchSchedule(context: Context, result: BroadcastReceiver.PendingResult) = launch {
    try { ReportSchedule.schedule(context, includeCurrentWindow = true) } finally { result.finish() }
}
