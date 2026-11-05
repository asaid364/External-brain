package app.liferecord.core

import java.net.URI
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GoalAnalysisPermission(
    val goalId: String,
    val specHash: String,
    val endpoint: String,
    val model: String,
    val sendAllowed: Boolean,
    val automaticAllowed: Boolean,
)

data class GoalRunInfo(
    val key: GoalReviewKey,
    val runId: String,
    val state: GoalRunState,
    val inputHash: String,
    val requestSent: Boolean,
    val externalAttempted: Boolean,
    val message: String,
    val retryAt: Long?,
    val startedAt: Long,
)

data class GoalKnownReview(val key: GoalReviewKey, val state: String, val peerDatabaseId: String)

/** Application-scoped owner of one paid goal-analysis task per review window. */
class GoalAnalysisEngine(
    private val repository: GoalRepository,
    private val loadConfiguration: () -> AiConfiguration,
    private val ai: GoalAnalysisAi = GoalAnalysisClient(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val online: () -> Boolean = { true },
    private val serviceGate: Mutex = Mutex(),
    private val generationGate: GoalReviewGenerationGate? = null,
) {
    private data class MemoryPending(
        val input: GoalAnalysisInput,
        val result: GeneratedGoalReview,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val memoryPending = ConcurrentHashMap<String, MemoryPending>()
    private val revisions = MutableStateFlow(0L)
    val changes: StateFlow<Long> = revisions.asStateFlow()

    private fun changed() { revisions.value += 1L }

    suspend fun request(key: GoalReviewKey, force: Boolean = false, automatic: Boolean = false) {
        val normalized = GoalCodec.validateReviewKey(key)
        gate.withLock {
            if (jobs[normalized.encoded]?.isActive == true) return
            val pending = memoryPending[normalized.encoded]
            if (pending != null || repository.run(normalized)?.state == GoalRunState.RESULT_READY) {
                retryLocalSaveLocked(normalized, pending)
                changed()
                return
            }

            val preview = repository.snapshot(normalized)
            if (preview.evidence.none { it.dependency.ref.kind == GoalEvidenceKind.RECORD }) {
                changed()
                return
            }
            val config = AiSettings.validateCredentials(loadConfiguration())
            validatePermission(preview.goal, config, automatic)
            if (!force) {
                val existing = repository.detail(normalized.goalId).reviews.singleOrNull { it.key == normalized }
                if (existing != null && existing.inputHash == preview.hash && repository.reviewStatus(existing) == GoalEvidence.CURRENT) {
                    changed()
                    return
                }
            }
            val known = repository.known(normalized)
            if (known != null && generationGate == null) {
                if (automatic || known.state == "RUNNING" || known.state == "FINISHED") return
                require(force) { "对端状态未知；再次尝试可能重复计费，请明确确认" }
            }
            val priorRun = repository.run(normalized)
            if (priorRun?.externalAttempted == true) {
                if (automatic) return
                require(force) { "本期可能已经计费；再次尝试前请明确确认" }
            }
            if (automatic && repository.externalAttempted(normalized)) return
            require(online()) { "当前离线，联网后请手动分析" }
            val service = service(config)
            val cooldown = repository.serviceCooldown(service)
            require(cooldown <= clock.millis()) { "AI服务仍在限流等待期，请稍后手动重试" }

            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    generationGate?.beforeCapture(normalized, automatic)
                    serviceGate.withLock {
                        currentCoroutineContext().ensureActive()
                        require(online()) { "当前离线，联网后请手动分析" }
                        val latestConfig = AiSettings.validateCredentials(loadConfiguration())
                        val latestPreview = repository.snapshot(normalized)
                        validatePermission(latestPreview.goal, latestConfig, automatic)
                        val latestService = service(latestConfig)
                        val until = repository.serviceCooldown(latestService)
                        require(until <= clock.millis()) { "AI服务仍在限流等待期，请稍后手动重试" }
                        val input = repository.claim(normalized, automatic, latestConfig.endpoint, latestConfig.model)
                            ?: return@withLock
                        val generation: suspend () -> Unit = { generate(input, latestConfig, latestService) }
                        val coordination = generationGate
                        if (coordination == null) generation() else coordination.run(input, automatic, generation)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AiFailure) {
                    finishFailure(normalized, failure)
                } catch (failure: IllegalArgumentException) {
                    repository.run(normalized)?.takeIf { it.state in setOf(GoalRunState.PREPARING, GoalRunState.RUNNING) }?.let {
                        repository.finishRun(it.runId, GoalRunState.FAILED, failure.message ?: "目标分析未完成")
                    }
                } catch (_: Exception) {
                    repository.run(normalized)?.takeIf { it.state in setOf(GoalRunState.PREPARING, GoalRunState.RUNNING) }?.let {
                        repository.finishRun(it.runId, if (it.requestSent) GoalRunState.UNKNOWN else GoalRunState.FAILED,
                            if (it.requestSent) "请求可能已发送，结果未知；再次尝试可能重复计费" else "本机处理失败，尚未发送")
                    }
                } finally {
                    changed()
                }
            }
            jobs[normalized.encoded] = job
            job.start()
            changed()
        }
    }

    private suspend fun generate(input: GoalAnalysisInput, config: AiConfiguration, service: String) {
        val runId = requireNotNull(input.runId)
        val result = ai.generate(config, input) {
            currentCoroutineContext().ensureActive()
            require(online()) { "当前离线，尚未发送" }
            val current = AiSettings.validateCredentials(loadConfiguration())
            validatePermission(input.goal, current, automatic = false)
            require(current.endpoint == config.endpoint && current.model == config.model) { "AI服务或模型已变化，请重新授权" }
            require(repository.serviceCooldown(service) <= clock.millis()) { "AI服务仍在限流等待期，尚未发送" }
            repository.markSent(runId)
        }
        currentCoroutineContext().ensureActive()
        memoryPending[input.key.encoded] = MemoryPending(input, result)
        try {
            repository.stage(runId, result)
        } catch (_: Exception) {
            return
        }
        try {
            repository.publishPending(input.key)
            memoryPending.remove(input.key.encoded)
        } catch (failure: IllegalArgumentException) {
            memoryPending.remove(input.key.encoded)
            repository.finishRun(runId, GoalRunState.INVALIDATED, failure.message ?: "来源已变化，结果未发布")
        } catch (_: Exception) {
            // RESULT_READY is durable; retryLocalSave publishes without another AI request.
        }
    }

    suspend fun retryLocalSave(key: GoalReviewKey) = gate.withLock {
        val normalized = GoalCodec.validateReviewKey(key)
        if (jobs[normalized.encoded]?.isActive == true) return@withLock
        try {
            retryLocalSaveLocked(normalized, memoryPending[normalized.encoded])
        } catch (failure: IllegalArgumentException) {
            memoryPending.remove(normalized.encoded)
            repository.run(normalized)?.let {
                repository.finishRun(it.runId, GoalRunState.INVALIDATED, failure.message ?: "来源已变化，结果未发布")
            }
        }
        changed()
    }

    private fun retryLocalSaveLocked(key: GoalReviewKey, pending: MemoryPending?) {
        if (pending != null) {
            val runId = requireNotNull(pending.input.runId)
            if (repository.run(key)?.state == GoalRunState.RUNNING) repository.stage(runId, pending.result)
        }
        repository.publishPending(key)
        memoryPending.remove(key.encoded)
    }

    suspend fun cancel(key: GoalReviewKey) = gate.withLock {
        val normalized = GoalCodec.validateReviewKey(key)
        jobs.remove(normalized.encoded)?.cancelAndJoin()
        memoryPending.remove(normalized.encoded)
        repository.cancelRun(normalized, "本机分析已取消；服务端可能仍处理或计费")
        changed()
    }

    suspend fun recover() = gate.withLock {
        repository.recoverInterrupted()
        changed()
    }

    suspend fun awaitCompletion(key: GoalReviewKey) {
        jobs[GoalCodec.validateReviewKey(key).encoded]?.join()
    }

    private fun validatePermission(goal: GoalSpec, config: AiConfiguration, automatic: Boolean) {
        val permission = repository.permission(goal.id) ?: throw IllegalArgumentException("请先授权此目标发送给AI服务")
        require(permission.sendAllowed) { "此目标未允许发送给AI服务" }
        require(!automatic || permission.automaticAllowed) { "此目标未允许自动周期分析" }
        require(permission.specHash == GoalCodec.specHash(goal)) { "目标内容已变化，请重新授权" }
        require(permission.endpoint == config.endpoint && permission.model == config.model) { "AI服务或模型已变化，请重新授权" }
        require(!goal.archived) { "已归档目标不能生成回顾" }
        if (automatic) require(clock.instant() >= GoalReviewPeriods.due(GoalReviewPeriods.latestCompleted(goal, clock.instant())
            ?: throw IllegalArgumentException("尚无已结束周期"), goal)) { "自动回顾尚未到执行时间" }
    }

    private fun finishFailure(key: GoalReviewKey, failure: AiFailure) {
        val run = repository.run(key) ?: return
        if (failure.retryAfterMillis != null) {
            val until = clock.millis() + failure.retryAfterMillis.coerceAtMost(Long.MAX_VALUE - clock.millis())
            val endpoint = repository.context(run.runId)?.service ?: return
            repository.setServiceCooldown(serviceEndpoint(endpoint), until)
            repository.finishRun(run.runId, GoalRunState.FAILED, failure.userMessage, until)
        } else if (run.requestSent && failure.retryable) {
            repository.finishRun(run.runId, GoalRunState.UNKNOWN,
                "请求已发送但结果未知；再次尝试可能重复计费")
        } else {
            repository.finishRun(run.runId, GoalRunState.FAILED, failure.userMessage)
        }
    }

    private fun service(configuration: AiConfiguration): String = serviceEndpoint(configuration.endpoint)

    private fun serviceEndpoint(endpoint: String): String = URI(endpoint).let {
        "${it.scheme.lowercase()}://${it.host.lowercase()}:${if (it.port < 0) 443 else it.port}"
    }
}
