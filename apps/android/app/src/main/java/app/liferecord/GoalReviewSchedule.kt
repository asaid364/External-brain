package app.liferecord

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import app.liferecord.core.*
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One CONNECTED work chain per goal; old missed windows are always manual. */
object GoalReviewSchedule {
    private const val TAG = "goal-review-clock"
    private const val GOAL_ID = "goalId"
    private const val SIGNATURE = "goalScheduleSignature:"
    internal data class Plan(val key: GoalReviewKey, val at: Instant)

    internal fun plan(
        goal: GoalSpec,
        clock: Clock,
        enabled: Boolean,
        blocked: (GoalReviewKey) -> Boolean = { false },
        skipCurrent: Boolean = false,
        retryNotBefore: Instant? = null,
    ): Plan? {
        if (!enabled || goal.archived) return null
        val now = clock.instant()
        val latest = GoalReviewPeriods.latestCompleted(goal, now)
        if (latest != null && !skipCurrent && !blocked(latest)) {
            return Plan(latest, maxOf(now, GoalReviewPeriods.due(latest, goal), retryNotBefore ?: Instant.MIN))
        }
        val nextFrom = latest?.let { LocalDate.parse(it.throughDate).plusDays(1) } ?: LocalDate.parse(goal.startDate)
        val end = LocalDate.parse(goal.endDate)
        if (nextFrom.isAfter(end)) return null
        val through = minOf(nextFrom.plusDays(goal.cadenceDays.toLong() - 1), end)
        val next = GoalReviewKey(goal.id, nextFrom.toString(), through.toString())
        return Plan(next, GoalReviewPeriods.due(next, goal))
    }

    internal fun cooldown(runtime: AppRuntime, endpoint: String): Long = URI(endpoint).let {
        runtime.goals.serviceCooldown("${it.scheme.lowercase()}://${it.host.lowercase()}:${if (it.port < 0) 443 else it.port}")
    }
    private fun blocked(runtime: AppRuntime, key: GoalReviewKey, detail: GoalDetail): Boolean =
        runtime.goals.externalAttempted(key) || detail.reviews.any { it.key == key } ||
            detail.conflicts.any { it.kind == "goal-review" && it.key == key.encoded } ||
            runtime.goals.known(key) != null || runtime.goals.run(key)?.state == GoalRunState.CANCELLED


    /** Call after ready, resume, local permission/configuration changes, and committed content. */
    suspend fun rearm(context: Context, runtime: AppRuntime = AppRuntime.get(context)) {
        if (!runtime.production) return
        runtime.ready.await()
        runtime.goalScheduleGate.withLock {
            val manager = WorkManager.getInstance(context.applicationContext)
            val summaries = runtime.goals.list(includeArchived = true)
            val ids = summaries.mapNotNull { it.spec?.id }.toSet()
            manager.getWorkInfosByTag(TAG).get().filter { !it.state.isFinished }.forEach { work ->
                val id = work.tags.firstOrNull { it.startsWith("$TAG/") }?.removePrefix("$TAG/")
                if (id !in ids) manager.cancelWorkById(work.id)
            }
            summaries.forEach { summary -> summary.spec?.let { scheduleLocked(context, runtime, it, summary.conflicted) } }
        }
    }

    private fun scheduleLocked(
        context: Context,
        runtime: AppRuntime,
        goal: GoalSpec,
        conflicted: Boolean,
        afterWorker: Boolean = false,
        skipCurrent: Boolean = false,
        retryNotBefore: Instant? = null,
    ) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val name = "$TAG/${goal.id}"
        val permission = runtime.goals.permission(goal.id)
        val configuration = runCatching { AiSettings.validateCredentials(runtime.settings.load()) }.getOrNull()
        val enabled = !conflicted && permission?.sendAllowed == true && permission.automaticAllowed &&
            permission.specHash == GoalCodec.specHash(goal) && configuration != null &&
            permission.endpoint == configuration.endpoint && permission.model == configuration.model
        val detail = if (enabled) runtime.goals.detail(goal.id) else null
        val target = plan(goal, runtime.goalClock, enabled, blocked = { key ->
            blocked(runtime, key, requireNotNull(detail))
        }, skipCurrent = skipCurrent, retryNotBefore = retryNotBefore)
        if (target == null) {
            if (!afterWorker) {
                // Publishing the final review commits content before this worker returns.
                // Preserve that completing worker; permission/definition invalidation still cancels it.
                val work = manager.getWorkInfosForUniqueWork(name).get()
                if (enabled && !goal.archived) {
                    work.filter { !it.state.isFinished && it.state != WorkInfo.State.RUNNING }
                        .forEach { manager.cancelWorkById(it.id) }
                } else manager.cancelUniqueWork(name)
            }
            return
        }
        val signature = SIGNATURE + GoalCodec.specHash(goal) + "/" + permission!!.endpoint + "/" + permission.model
        val unfinished = manager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }
        if (!afterWorker && unfinished.any { it.state == WorkInfo.State.RUNNING && signature in it.tags }) return
        val targetTag = "goalReviewAt:${target.key.encoded}/${target.at}"
        if (unfinished.any { it.state != WorkInfo.State.RUNNING && signature in it.tags && targetTag in it.tags }) return
        val request = OneTimeWorkRequestBuilder<GoalReviewWorker>()
            .setInputData(workDataOf(GOAL_ID to goal.id))
            .addTag(TAG).addTag(name).addTag(signature).addTag(targetTag)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(Duration.between(runtime.goalClock.instant(), target.at).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniqueWork(name, if (afterWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
    }

    internal suspend fun runWorker(context: Context, runtime: AppRuntime, goalId: String) {
        if (!runtime.production) return
        runtime.ready.await()
        var target: GoalReviewKey? = null
        var processed = false
        var retryNotBefore: Instant? = null
        try {
            val detail = runtime.goals.detail(goalId)
            val goal = detail.summary.spec ?: return
            val key = GoalReviewPeriods.latestCompleted(goal, runtime.goalClock.instant()) ?: return
            target = key
            if (runtime.goalClock.instant() < GoalReviewPeriods.due(key, goal)) return
            if (blocked(runtime, key, detail)) return
            processed = true
            runtime.goalEngine.request(key, automatic = true)
            runtime.goalEngine.awaitCompletion(key)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { target?.let { runtime.goalEngine.cancel(it) } }
            throw cancelled
        } catch (_: Exception) {
            // Only unsent temporary offline/cooldown preflights may run again automatically.
        } finally {
            withContext(NonCancellable) {
                val key = target
                if (key != null && !runtime.goals.externalAttempted(key)) {
                    val permission = runtime.goals.permission(goalId)
                    val until = permission?.let { runCatching { cooldown(runtime, it.endpoint) }.getOrDefault(0) } ?: 0
                    if (!runtime.goalNetworkAvailable() || until > runtime.goalClock.millis()) {
                        retryNotBefore = Instant.ofEpochMilli(maxOf(runtime.goalClock.millis() + TimeUnit.MINUTES.toMillis(30), until))
                    }
                }
                runtime.goalScheduleGate.withLock {
                    runtime.goals.detail(goalId).summary.let { summary -> summary.spec?.let {
                        scheduleLocked(context, runtime, it, summary.conflicted, afterWorker = true,
                            skipCurrent = processed && retryNotBefore == null, retryNotBefore = retryNotBefore)
                    } }
                }
            }
        }
    }
}

class GoalReviewWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val goalId = inputData.getString("goalId") ?: return Result.success()
        GoalReviewSchedule.runWorker(applicationContext, AppRuntime.get(applicationContext), goalId)
        // Never ask WorkManager to replay a potentially paid request.
        return Result.success()
    }
}

class GoalReviewClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        val runtime = AppRuntime.get(context)
        runtime.scope.launch {
            try { GoalReviewSchedule.rearm(context.applicationContext, runtime) } finally { pending.finish() }
        }
    }
}
