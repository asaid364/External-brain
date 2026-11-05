package app.liferecord

import android.content.Context
import app.liferecord.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

internal data class GenerationConfirmation(val date: String, val takeover: Boolean, val message: String)

/** Keeps known ambiguous external calls durable; expiry never grants permission to repeat a paid call. */
internal class AndroidGenerationGate(context: Context, private val database: SqlDatabase,
    private val repository: ReportRepository, private val sync: SyncRuntime) : ReportGenerationGate {
    private val storage = AndroidSyncStorage(context)
    private val approvals = ConcurrentHashMap<String, Boolean>()
    private val pending = MutableStateFlow<GenerationConfirmation?>(null)
    val confirmation = pending.asStateFlow()
    private var initialized = false

    fun approve(value: GenerationConfirmation) { approvals[value.date] = value.takeover; pending.value = null }
    fun dismiss() { pending.value = null }

    @Synchronized private fun initialize() {
        if (initialized) return
        database.transaction {
            it.exec("CREATE TABLE IF NOT EXISTS android_generation_known(date TEXT PRIMARY KEY,peer_database_id TEXT NOT NULL,run_id TEXT NOT NULL,state TEXT NOT NULL)")
            it.exec("UPDATE android_generation_known SET state='UNKNOWN' WHERE state='RUNNING'")
        }
        initialized = true
    }
    private fun known(date: String, peer: AndroidSyncPeer): String? = database.transaction {
        it.query("SELECT state FROM android_generation_known WHERE date=? AND peer_database_id=?", listOf(date, peer.peerDatabaseId)).use { c -> if(c.next()) c.string(0) else null }
    }
    private fun remember(date: String, peer: AndroidSyncPeer, runId: String, state: String) = database.transaction {
        val args = listOf(peer.peerDatabaseId, runId, state, date)
        if (it.exec("UPDATE android_generation_known SET peer_database_id=?,run_id=?,state=? WHERE date=?", args) == 0)
            it.exec("INSERT INTO android_generation_known(peer_database_id,run_id,state,date) VALUES(?,?,?,?)", args)
    }
    private fun requireConfirmation(date: String, takeover: Boolean, message: String): Nothing {
        pending.value = GenerationConfirmation(date, takeover, message)
        throw AiFailure(message)
    }
    private fun JsonObject.string(name: String) = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw AiFailure("另一端生成协调响应无效，未调用AI")

    override suspend fun run(input: ReportInput, automatic: Boolean, generate: suspend () -> Unit) {
        val peer = storage.read() ?: return generate()
        initialize()
        val approval = approvals.remove(input.date)
        val old = known(input.date, peer)
        if (automatic && old in setOf("RUNNING", "UNKNOWN"))
            throw AiFailure("另一端生成状态待核对，未自动调用；请手动核对后确认是否重试")
        val synchronized = sync.synchronizeForGeneration()
        if (!synchronized) {
            if (old in setOf("RUNNING", "UNKNOWN") && automatic)
                throw AiFailure("另一端生成状态待核对，未自动调用")
            if (!automatic && approval == null)
                requireConfirmation(input.date, false, "无法协调另一端，可能重复调用或计费。确认后才发送所选日期内容。")
            // Unknown isolation preserves the pre-existing Android automatic behavior, not a pretend distributed lock.
            if (!automatic && old in setOf("RUNNING", "UNKNOWN") && approval != true)
                requireConfirmation(input.date, true, "已知另一端生成仍在运行或状态未知。请先核对；确认重试可能再次计费。")
            return generate()
        }
        if (repository.input(input.date).hash != input.hash)
            throw AiFailure("双向同步已改变当天输入；请核对最新来源后重新生成，未调用AI")
        val runId = requireNotNull(input.claimToken)
        val lease = try {
            sync.generation("claim", buildJsonObject {
                put("date", input.date); put("runId", runId); put("inputHash", input.hash); put("takeover", approval == true)
            })
        } catch (error: AndroidSyncFailure) {
            when (error.code) {
                "GENERATION_RUNNING", "RUNNING", "GENERATION_BUSY" -> {
                    remember(input.date, peer, runId, "RUNNING")
                    throw AiFailure("该日期另一端仍在生成，未重复调用；请等待并同步结果")
                }
                "GENERATION_UNKNOWN", "UNKNOWN", "TAKEOVER_REQUIRED" -> {
                    remember(input.date, peer, runId, "UNKNOWN")
                    if (automatic) throw AiFailure("另一端生成状态待核对，未自动调用")
                    requireConfirmation(input.date, true, "另一端生成状态未知。请核对；确认重试可能再次计费。")
                }
                else -> throw AiFailure("跨端生成协调未成功，未调用AI；请检查同步状态后重试")
            }
        }
        require(lease.string("date") == input.date && lease.string("runId") == runId && lease.string("inputHash") == input.hash && lease.string("state") == "RUNNING") { "生成协调身份不一致，未调用AI" }
        val token = lease.string("token")
        remember(input.date, peer, runId, "RUNNING")
        val body = buildJsonObject { put("date", input.date); put("runId", runId); put("token", token) }
        coroutineScope {
            val heartbeat = launch {
                while (isActive) {
                    delay(30_000)
                    try { sync.generation("heartbeat", body) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        remember(input.date, peer, runId, "UNKNOWN")
                        // Do not discard an already paid result or start a replacement request.
                        return@launch
                    }
                }
            }
            try { generate() }
            finally {
                withContext(NonCancellable) {
                    heartbeat.cancelAndJoin()
                    try { sync.generation("finish", body); remember(input.date, peer, runId, "FINISHED") }
                    catch (_: Exception) { remember(input.date, peer, runId, "UNKNOWN") }
                }
            }
        }
    }
}
