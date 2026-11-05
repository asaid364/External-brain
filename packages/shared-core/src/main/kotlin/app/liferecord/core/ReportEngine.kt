package app.liferecord.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** Application-scoped generation state machine. Platform runtimes own its lifetime and connectivity input. */
class ReportEngine(
    private val repository: ReportRepository,
    private val loadConfiguration: () -> AiConfiguration,
    private val ai: ReportAi = AiClient(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val online: () -> Boolean = { true },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val generationGate: ReportGenerationGate? = null,
    private val serviceGate: Mutex = Mutex(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()
    private data class Pending(val input: ReportInput, val result: GeneratedReport, val service: String, val model: String)
    private val pending = ConcurrentHashMap<String, Pending>()
    private val revisions = MutableStateFlow(0L)
    val changes = revisions.asStateFlow()

    private fun changed() { revisions.value += 1 }
    suspend fun recover() = gate.withLock { repository.recoverInterrupted(); changed() }
    private fun service(configuration: AiConfiguration): String = URI(configuration.endpoint).let {
        "${it.scheme.lowercase()}://${it.host.lowercase()}:${if (it.port < 0) 443 else it.port}"
    }

    suspend fun request(date: String, force: Boolean = false, clearEmpty: Boolean = false, automatic: Boolean = false) {
        LocalDate.parse(date).also { require(!it.isAfter(LocalDate.now(clock))) { "不能生成未来日报" } }
        gate.withLock {
            if (jobs[date]?.isActive == true) return
            pending[date]?.let { saved ->
                try {
                    repository.commit(saved.input, saved.result, saved.service, saved.model)
                    pending.remove(date)
                    repository.finish(date, "已保存到本机")
                } catch (failure: IllegalArgumentException) {
                    pending.remove(date)
                    repository.finish(date, failure.message ?: "来源变化，请重新生成")
                } catch (_: Exception) {
                    repository.progress(date, "AI结果尚未可靠保存；点击更新仅重试本地保存，不重新计费")
                }
                changed()
                return
            }
            val input = repository.input(date)
            val day = repository.day(date)
            if (automatic && day.report != null) return
            if (input.records.isEmpty()) {
                if (clearEmpty) {
                    if (!repository.claim(date, automatic = false)) { changed(); return }
                    val captured = repository.input(date)
                    repository.clearEmpty(date, captured.baseReportHeads, requireNotNull(captured.claimToken))
                    repository.finish(date, "当前无有效来源，旧正文已移除；未调用AI")
                } else repository.finish(date, if (day.report == null) "当天暂无记录，未调用AI" else "来源已无有效记录，请确认移除旧正文；未调用AI")
                changed()
                return
            }
            if (!force && day.report?.inputHash == input.hash) {
                repository.finish(date, "暂无新内容，未调用AI")
                changed()
                return
            }
            val config = loadConfiguration()
            require(config.consent && config.apiKey.isNotBlank() && config.model.isNotBlank() && config.endpoint.isNotBlank()) { "请先配置AI并同意发送范围" }
            if (automatic && !config.automatic) return
            val service = service(config)
            val until = repository.serviceCooldown(service)
            if (until > clock.millis()) {
                repository.finish(date, "服务限流，请在 ${java.time.Instant.ofEpochMilli(until).atZone(clock.zone).toLocalTime().withNano(0)} 后手动重试")
                changed()
                return
            }
            if (!online()) {
                repository.finish(date, "当前离线，联网后请手动生成")
                changed()
                return
            }
            if (!repository.claim(date, automatic)) { changed(); return }
            val captured = repository.input(date)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val coordination = generationGate
                    if (coordination == null) generate(captured, config, service)
                    else coordination.run(captured, automatic) { generate(captured, config, service) }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: AiFailure) { repository.finish(date, failure.userMessage) }
                catch (failure: IllegalArgumentException) { repository.finish(date, failure.message ?: "来源变化，请重新生成") }
                catch (_: Exception) { runCatching { repository.finish(date, "本机处理失败，未替换旧日报；请重试") } }
                finally { changed() }
            }
            jobs[date] = job
            job.start()
            changed()
        }
    }

    private suspend fun generate(input: ReportInput, config: AiConfiguration, service: String) {
        val result = serviceGate.withLock {
            var retried = false
            while (true) {
                currentCoroutineContext().ensureActive()
                val until = repository.serviceCooldown(service)
                if (until > clock.millis()) throw AiFailure("服务仍在限流等待期，请稍后手动重试")
                try { return@withLock ai.generate(config, input) }
                catch (failure: AiFailure) {
                    val wait = maxOf(10_000L, failure.retryAfterMillis ?: 10_000L)
                    if (failure.retryAfterMillis != null) repository.setServiceCooldown(service, clock.millis() + failure.retryAfterMillis.coerceAtMost(Long.MAX_VALUE - clock.millis()))
                    if (!failure.retryable || retried || wait > 60_000L) throw failure
                    retried = true
                    repository.progress(input.date, "${wait / 1000}秒后自动重试（1/1）；可能额外计费", clock.millis() + wait)
                    changed()
                    pause(wait)
                    currentCoroutineContext().ensureActive()
                    if (!online()) throw AiFailure("当前离线，联网后请手动重试")
                    repository.progress(input.date, "正在重试（1/1）")
                    changed()
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        currentCoroutineContext().ensureActive()
        pending[input.date] = Pending(input, result, service, config.model)
        try { repository.commit(input, result, service, config.model) }
        catch (failure: IllegalArgumentException) { pending.remove(input.date); throw failure }
        catch (_: Exception) {
            repository.progress(input.date, "AI结果尚未可靠保存；点击更新仅重试本地保存，不重新计费")
            return
        }
        pending.remove(input.date)
        repository.finish(input.date, "日报已保存到本机；不代表同步或备份成功")
    }

    suspend fun cancel(date: String) = gate.withLock {
        jobs.remove(date)?.cancelAndJoin()
        pending.remove(date)
        repository.finish(date, "本机生成已取消；服务端可能仍处理或计费")
        changed()
    }

    suspend fun awaitCompletion(date: String) { jobs[date]?.join() }
    suspend fun mutate(block: () -> Unit) = withContext(Dispatchers.IO) { block(); changed() }

    suspend fun test(configuration: AiConfiguration) = serviceGate.withLock {
        val previous = loadConfiguration()
        if (configuration.apiKey.isBlank() && previous.apiKey.isNotBlank()) {
            require(AiSettings.endpointAuthority(configuration.endpoint) == AiSettings.endpointAuthority(previous.endpoint)) { "服务地址已更改，请重新填写 API Key" }
        }
        val effective = AiSettings.validate(configuration.copy(apiKey = configuration.apiKey.ifBlank { previous.apiKey }))
        val service = service(effective)
        if (repository.serviceCooldown(service) > clock.millis()) throw AiFailure("服务限流等待中，暂不能测试连接")
        try { ai.test(effective) }
        catch (failure: AiFailure) {
            failure.retryAfterMillis?.let { repository.setServiceCooldown(service, clock.millis() + it) }
            throw failure
        }
    }

    fun hasPending(date: String) = pending.containsKey(date)
}
