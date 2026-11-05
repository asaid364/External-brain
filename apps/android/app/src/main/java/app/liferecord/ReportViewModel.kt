package app.liferecord

import app.liferecord.core.*

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

class ReportViewModel @JvmOverloads constructor(application: Application, private val runtime: AppRuntime = AppRuntime.get(application)) : AndroidViewModel(application) {
    var state by mutableStateOf(ReportUiState()); private set
    private var selection = 0L
    internal var generationConfirmation by mutableStateOf<GenerationConfirmation?>(null); private set
    private val generationRequests = mutableMapOf<String, Pair<Boolean, Boolean>>()
    init {
        viewModelScope.launch {
            try {
                runtime.ready.await()
                refresh()
                launch { runtime.engine.changes.collect { refresh() } }
                launch { runtime.sync.changes.collect { refresh() } }
                runtime.generation?.let { gate -> launch { gate.confirmation.collect { generationConfirmation = it } } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state = state.copy(loading = false, message = "日报读取失败，未覆盖原数据，请重试") }
        }
    }
    fun select(date: String) {
        val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return
        if (parsed.isAfter(LocalDate.now())) return
        selection++
        state = state.copy(date = date, day = null, loading = true, message = "")
        refresh()
    }
    fun refresh() {
        val date = state.date
        val generation = ++selection
        viewModelScope.launch {
            try {
                runtime.ready.await()
                val (day, config) = withContext(Dispatchers.IO) { runtime.repository.day(date) to runtime.settings.load() }
                if (generation != selection || date != state.date) return@launch
                val missed = day.report == null && day.records.isNotEmpty() && LocalDate.parse(date).isBefore(LocalDate.now()) && day.message.isBlank()
                state = state.copy(day = day, loading = false, configured = config.consent && config.apiKey.isNotBlank() && config.model.isNotBlank(), automatic = config.automatic,
                    configuration = config.copy(apiKey = ""), message = if (missed && state.message.isBlank()) "该日尚未生成，可点击生成；不会自动补发历史请求" else state.message)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == selection) state = state.copy(loading = false, message = "本机日报读取失败，未覆盖旧内容。请重试读取") }
        }
    }
    private fun operation(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { runtime.ready.await(); withContext(Dispatchers.IO) { block() }; state = state.copy(message = ""); refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: AiFailure) { state = state.copy(message = e.userMessage); refresh() }
            catch (e: IllegalArgumentException) { state = state.copy(message = e.message ?: "输入无效，内容保留") }
            catch (_: Exception) { state = state.copy(message = "本机操作未成功，内容保留，请重试") }
        }
    }
    fun generate(force: Boolean = false, clearEmpty: Boolean = false) {
        val date = state.date
        generationRequests[date] = force to clearEmpty
        operation { runtime.engine.request(date, force, clearEmpty) }
    }
    fun confirmGeneration() {
        val decision = generationConfirmation ?: return
        val request = generationRequests[decision.date] ?: (false to false)
        runtime.generation?.approve(decision)
        generationConfirmation = null
        operation {
            runtime.engine.awaitCompletion(decision.date)
            runtime.engine.request(decision.date, request.first, request.second)
        }
    }
    fun dismissGeneration() { runtime.generation?.dismiss(); generationConfirmation = null }
    fun cancel() { val date = state.date; operation { runtime.engine.cancel(date) } }
    fun saveAnswer(id: String, text: String, baseHeads: Set<String>, intentId: String) { val date = state.date; operation { runtime.engine.mutate { runtime.repository.answer(date, id, text, baseHeads, intentId) } } }
    fun skip(id: String, skipped: Boolean, baseHeads: Set<String>, intentId: String) { val date = state.date; operation { runtime.engine.mutate { runtime.repository.skip(date, id, skipped, baseHeads, intentId) } } }
    fun saveConfiguration(config: AiConfiguration) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    runtime.settings.save(config)
                    if (runtime.production) ReportSchedule.schedule(getApplication(), includeCurrentWindow = true)
                }
                refresh()
                state = state.copy(message = "配置已保存；本地保存不代表数据不发送外部服务")
            } catch (e: IllegalArgumentException) { state = state.copy(message = e.message ?: "配置无效") }
            catch (_: Exception) { state = state.copy(message = "配置未保存，原配置保留，请检查本机存储或密钥状态") }
        }
    }
    fun testConfiguration(config: AiConfiguration) {
        if (state.testing) return
        state = state.copy(testing = true, message = "正在发送最小合成内容测试连接，可能计费")
        viewModelScope.launch {
            try { runtime.ready.await(); withContext(Dispatchers.IO) { runtime.engine.test(config) }; state = state.copy(message = "连接请求成功；不代表日报事实正确或所有功能通过") }
            catch (e: AiFailure) { state = state.copy(message = e.userMessage) }
            catch (e: IllegalArgumentException) { state = state.copy(message = e.message ?: "配置无效") }
            catch (_: Exception) { state = state.copy(message = "连接测试失败，请检查服务、网络与配置") }
            finally { state = state.copy(testing = false) }
        }
    }
}
