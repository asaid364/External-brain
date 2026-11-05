package app.liferecord

import android.content.Context
import app.liferecord.core.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

/** A decision authorizes only the suspended request, never a later click or automatic job. */
data class GoalGenerationConfirmation(
    val id: String,
    val key: GoalReviewKey,
    val takeover: Boolean,
    val message: String,
)

/** Coordinates only after sync; it never replaces the engine's captured input with a newer one. */
class AndroidGoalGenerationGate internal constructor(
    context: Context,
    private val database: SqlDatabase,
    private val goals: GoalRepository,
    private val sync: SyncRuntime,
) : GoalReviewGenerationGate {
    private data class Capture(val peer: AndroidSyncPeer?, val coordinated: Boolean, val takeover: Boolean)
    private val storage = AndroidSyncStorage(context)
    private val captures = ConcurrentHashMap<String, Capture>()
    private val decisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pending = MutableStateFlow<List<GoalGenerationConfirmation>>(emptyList())
    val confirmations = pending.asStateFlow()

    fun resolveConfirmation(id: String, approved: Boolean) {
        decisions[id]?.complete(approved)
    }

    private suspend fun confirm(key: GoalReviewKey, takeover: Boolean, message: String) {
        val value = GoalGenerationConfirmation(UUID.randomUUID().toString(), key, takeover, message)
        val decision = CompletableDeferred<Boolean>()
        decisions[value.id] = decision
        pending.update { it + value }
        try {
            if (!decision.await()) throw AiFailure("已取消本次目标分析，未调用AI")
        } finally {
            decisions.remove(value.id)
            pending.update { list -> list.filterNot { it.id == value.id } }
        }
    }

    override suspend fun beforeCapture(key: GoalReviewKey, automatic: Boolean) {
        captures.remove(key.encoded)
        val peer = storage.read()
        val old = goals.known(key)
        if (peer == null && old == null) {
            captures[key.encoded] = Capture(null, false, false)
            return
        }
        if (peer == null || !sync.synchronizeForGeneration()) {
            isolated(key, peer, automatic)
            return
        }
        val status = try {
            sync.goalGeneration("status", key.body()).also { validateStatus(it, key) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: AndroidSyncFailure) {
            if (error.code != "TRANSPORT") throw AiFailure("目标协调未成功：${error.publicMessage}；未调用AI")
            isolated(key, peer, automatic)
            return
        }
        val state = status.text("state")
        val known = goals.known(key)
        // A new pairing/IDLE response cannot erase risk learned from another database.
        if (known != null && known.peerDatabaseId != peer.peerDatabaseId) {
            isolated(key, peer, automatic)
            return
        }
        when (state) {
            "RUNNING" -> {
                goals.rememberKnown(key, state, peer.peerDatabaseId)
                throw AiFailure("本期另一端仍在分析，未重复调用；请等待并同步结果")
            }
            "UNKNOWN" -> {
                goals.rememberKnown(key, state, peer.peerDatabaseId)
                if (automatic) throw AiFailure("另一端目标分析状态未知，未自动调用")
                confirm(key, true, "另一端本期分析状态未知。接管并重新分析可能重复计费；仅授权本次请求。")
            }
            "FINISHED" -> {
                goals.rememberKnown(key, state, peer.peerDatabaseId)
                try {
                    require(goals.detail(key.goalId).reviews.any { it.key == key && it.inputHash == status.text("inputHash") })
                    goals.clearKnownFinished(key, peer.peerDatabaseId)
                }
                catch (_: IllegalArgumentException) { throw AiFailure("另一端已结束，但尚未同步取得完整回顾；请手动核对，未调用AI") }
            }
            "IDLE" -> if (known != null) {
                isolated(key, peer, automatic)
                return
            }
        }
        captures[key.encoded] = Capture(peer, true, state == "UNKNOWN")
    }

    private suspend fun isolated(key: GoalReviewKey, peer: AndroidSyncPeer?, automatic: Boolean) {
        val known = goals.known(key)
        if (known?.state == "RUNNING") throw AiFailure("已知另一端仍在分析，当前无法核验；未重复调用")
        if (known?.state == "FINISHED") throw AiFailure("另一端已结束但尚未核对完整结果；请先同步，未调用AI")
        if (automatic) {
            // Goal-level automatic consent includes partition/duplicate-fee risk, never a manual approval.
            val permission = goals.permission(key.goalId)
            if (known != null || permission?.sendAllowed != true || !permission.automaticAllowed)
                throw AiFailure("无法协调另一端或存在未知风险，未自动调用")
            captures[key.encoded] = Capture(peer, false, false)
            return
        }
        confirm(key, false, if (known != null)
            "已知另一端状态未知且当前无法协调。仅在本机重新尝试可能重复计费；不会清除另一端未知状态，仅授权本次请求。"
            else "已配对电脑当前无法协调。基于本机数据分析可能遗漏尚未同步内容并重复计费；仅授权本次请求。")
        captures[key.encoded] = Capture(peer, false, false)
    }

    override suspend fun run(input: GoalAnalysisInput, automatic: Boolean, generate: suspend () -> Unit) {
        val capture = captures.remove(input.key.encoded) ?: throw AiFailure("目标协调预检缺失，未调用AI")
        if (!capture.coordinated) {
            check(!automatic || goals.known(input.key) == null && goals.permission(input.key.goalId)?.automaticAllowed == true) { "未授权自动绕过跨端协调" }
            if (storage.read() != capture.peer) throw AiFailure("配对身份已变化，请重新核对，未调用AI")
            return generate()
        }
        val peer = requireNotNull(capture.peer)
        if (storage.read() != peer) throw AiFailure("配对身份已变化，请重新核对，未调用AI")
        require(goals.snapshot(input.key).hash == input.hash) { "同步后目标输入已变化，请重新分析，未调用AI" }
        val runId = requireNotNull(input.runId)
        val holder = SyncRepository(database).identity().databaseId
        val lease = try {
            sync.goalGeneration("claim", input.key.body {
                put("runId", runId); put("inputHash", input.hash); put("takeover", capture.takeover && !automatic)
            }).also { validateLease(it, input, holder, null, "RUNNING") }
        } catch (error: AndroidSyncFailure) {
            when (error.code) {
                "GOAL_GENERATION_RUNNING" -> goals.rememberKnown(input.key, "RUNNING", peer.peerDatabaseId)
                "GOAL_GENERATION_UNKNOWN", "TRANSPORT" -> goals.rememberKnown(input.key, "UNKNOWN", peer.peerDatabaseId)
            }
            throw AiFailure("目标协调未成功：${error.publicMessage}；未调用AI，请重新核对")
        }
        val token = lease.text("token")
        goals.rememberKnown(input.key, "RUNNING", peer.peerDatabaseId)
        val body = input.key.body { put("runId", runId); put("token", token) }
        coroutineScope {
            val heartbeat = launch {
                while (isActive) {
                    delay(30_000)
                    try {
                        sync.goalGeneration("heartbeat", body).also { validateLease(it, input, holder, token, "RUNNING") }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        goals.rememberKnown(input.key, "UNKNOWN", peer.peerDatabaseId)
                        return@launch
                    }
                }
            }
            try { generate() }
            finally {
                withContext(NonCancellable) {
                    heartbeat.cancelAndJoin()
                    try {
                        sync.goalGeneration("finish", body).also { validateLease(it, input, holder, token, "FINISHED") }
                        goals.rememberKnown(input.key, "FINISHED", peer.peerDatabaseId)
                    } catch (_: Exception) {
                        // The paid result remains publishable; coordination failure never replays AI.
                        goals.rememberKnown(input.key, "UNKNOWN", peer.peerDatabaseId)
                    }
                }
            }
        }
    }

    private fun GoalReviewKey.body(extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("goalId", goalId); put("fromDate", fromDate); put("throughDate", throughDate); extra()
    }
    private fun JsonObject.text(name: String): String = (get(name) as? JsonPrimitive)
        ?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: throw AiFailure("目标协调响应格式无效，未调用AI")
    private fun validateKey(value: JsonObject, key: GoalReviewKey) {
        require(value.text("goalId") == key.goalId && value.text("fromDate") == key.fromDate && value.text("throughDate") == key.throughDate) { "目标协调窗口身份不一致" }
    }
    private fun validateStatus(value: JsonObject, key: GoalReviewKey) {
        require(value.keys == setOf("goalId", "fromDate", "throughDate", "state", "runId", "holder", "inputHash", "expiresAt")) { "目标协调响应字段无效" }
        validateKey(value, key)
        val state = value.text("state")
        require(state in setOf("IDLE", "RUNNING", "UNKNOWN", "FINISHED"))
        if (state == "IDLE") require(listOf("runId", "holder", "inputHash", "expiresAt").all { value[it] == JsonNull })
        else {
            UUID.fromString(value.text("runId")); require(value.text("holder").isNotBlank())
            require(value.text("inputHash").matches(Regex("[0-9a-f]{64}")))
            require((value["expiresAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull != null)
        }
    }
    private fun validateLease(value: JsonObject, input: GoalAnalysisInput, holder: String, token: String?, state: String) {
        require(value.keys == setOf("goalId", "fromDate", "throughDate", "runId", "inputHash", "holder", "token", "state", "expiresAt")) { "目标租约响应字段无效" }
        validateKey(value, input.key)
        require(value.text("runId") == input.runId && value.text("inputHash") == input.hash && value.text("holder") == holder && value.text("state") == state) { "目标租约身份不一致" }
        require(token == null || value.text("token") == token) { "目标租约令牌不一致" }
        value.text("token")
        require((value["expiresAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull != null) { "目标租约到期格式无效" }
    }
}
