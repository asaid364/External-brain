package app.liferecord

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.*
import app.liferecord.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Application-scoped exchange/discovery owner. Workers and foreground share this single gate. */
class SyncRuntime(private val context: Context, private val database: SqlDatabase, private val enabled: Boolean = false) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val storage by lazy { AndroidSyncStorage(context) }
    val exchange = SyncExchange(database)
    private val repository = SyncRepository(database)
    private val client by lazy { AndroidSyncClient(context) }
    private val hints by lazy { AndroidRouteHints(context) }
    private val networks by lazy { context.getSystemService(ConnectivityManager::class.java) }
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private val status = MutableStateFlow("未配对")
    val message = status.asStateFlow()
    private val active = MutableStateFlow(false)
    val syncing = active.asStateFlow()
    private val discovered = MutableStateFlow(emptyList<AndroidDiscoveredPeer>())
    val discoveredPeers = discovered.asStateFlow()
    private val remote = MutableStateFlow<SyncStatus?>(null)
    val remoteStatus = remote.asStateFlow()
    private val categories = MutableStateFlow<AndroidPeerCounts?>(null)
    val remoteCounts = categories.asStateFlow()
    private val started = AtomicBoolean(false)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var stopped = false
    private var foreground: Job? = null
    @Volatile var route: AndroidPeerRoute? = null
        private set
    private val discovery by lazy { AndroidSyncDiscovery(context, { values ->
        discovered.value = values
        request()
    }, { error -> status.value = error }) }
    private val completedRounds = AtomicLong()

    suspend fun synchronizeForGeneration(): Boolean {
        val before = completedRounds.get()
        runOnce()
        return !stopped && completedRounds.get() > before
    }
    suspend fun generation(operation: String, body: JsonObject): JsonObject = gate.withLock {
        check(enabled) { "隔离运行未启用跨端协调" }
        val peer = requireNotNull(storage.read()) { "尚未配对" }
        val selected = requireNotNull(restoreRoute(peer)) { "另一端暂不可达" }
        client.generation(selected, peer, repository.identity().databaseId, operation, body)
    }

    suspend fun goalGeneration(operation: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) { gate.withLock {
        check(enabled && !stopped) { "当前未启用跨端目标协调，请核验同步状态" }
        val peer = requireNotNull(storage.read()) { "尚未配对" }
        val selected = requireNotNull(restoreRoute(peer)) { "另一端暂不可达" }
        client.goalGeneration(selected, peer, repository.identity().databaseId, operation, body)
    } }

    fun selectRoute(value: AndroidPeerRoute) {
        if (!enabled) return
        route = value
        scope.launch {
            try { hints.write(value, networks) }
            catch (_: Exception) { status.value = "当前地址可用，但重启地址提示未保存" }
        }
    }

    private fun restoreRoute(peer: AndroidSyncPeer): AndroidPeerRoute? {
        val saved = hints.read()?.takeIf { it.serverId == peer.serverId }
        val found = discovered.value.firstOrNull { it.serverId == peer.serverId }
        val address = found?.address ?: route?.takeIf { it.serverId == peer.serverId }?.address ?: saved?.address ?: return null
        val port = found?.port ?: route?.takeIf { it.serverId == peer.serverId }?.port ?: saved?.port ?: peer.port
        val network = hints.network(context, address, saved?.interfaceName) ?: return null
        // Only routing changes here; trust always comes from encrypted, previously approved peer storage.
        return AndroidPeerRoute(peer.serverId, peer.stableHost, address, port, peer.certificateSha256, network).also { route = it }
    }

    suspend fun authenticatedStatus(): SyncStatus? = withContext(Dispatchers.IO) { gate.withLock {
        if (!enabled) return@withLock null
        val peer = storage.read() ?: return@withLock null
        val selected = restoreRoute(peer) ?: return@withLock null
        client.status(selected, peer, exchange, repository).also {
            categories.value = client.remoteCounts
            remote.value = it
        }
    } }

    suspend fun runOnce(): Boolean = withContext(Dispatchers.IO) { gate.withLock {
        if (!enabled || stopped) return@withLock true
        try {
            val peer = storage.read() ?: run { status.value = "未配对"; return@withLock true }
            val selected = restoreRoute(peer) ?: run { status.value = "等待发现已配对电脑的局域网地址"; return@withLock false }
            active.value = true
            status.value = "正在交换两端持久变化"
            val offeredFences = exchange.fences().fences
            when (val result = client.exchangeOnce(selected, peer, exchange, repository)) {
                is SyncRoundResult.Complete -> {
                    database.transaction { db ->
                        offeredFences.forEach { fence ->
                            if (db.exec("UPDATE android_purge_ack SET purge_id=? WHERE record_id=? AND peer_database_id=?",
                                    listOf(fence.purgeId, fence.recordId, peer.peerDatabaseId)) == 0)
                                db.exec("INSERT INTO android_purge_ack(record_id,peer_database_id,purge_id) VALUES(?,?,?)",
                                    listOf(fence.recordId, peer.peerDatabaseId, fence.purgeId))
                        }
                    }
                    remote.value = client.status(selected, peer, exchange, repository)
                    categories.value = client.remoteCounts
                    status.value = "本轮双方已持久提交；后续变化及冲突独立显示"
                    try { hints.write(selected, networks) }
                    catch (_: Exception) { status.value = "本轮双方已持久提交；重启地址提示未保存" }
                    revision.update { it + 1 }
                    completedRounds.incrementAndGet()
                    true
                }
                is SyncRoundResult.Retryable -> { status.value = result.message; false }
                is SyncRoundResult.Stopped -> {
                    stopped = result.code != "CONSENT_REQUIRED"
                    status.value = "${result.code}：${result.message}"
                    true
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalStateException) { stopped = true; status.value = "同步凭据或持久状态不可用，请在同步页核验；本机内容保留"; true }
        catch (_: Exception) { status.value = "同步暂未完成，未确认变化仍保留"; false }
        finally { active.value = false; revision.update { it + 1 } }
    } }

    /** Called only by the post-commit observer; no disk or network IO in the callback. */
    fun contentCommitted() {
        revision.update { it + 1 }
        request()
    }
    private fun request() { if (enabled && started.get() && !stopped) requests.trySend(Unit) }
    /** Explicit user retry/consent may resume a stopped session; background writes cannot. */
    fun wake() { if (enabled) { stopped = false; request() } }
    fun startDiscovery() {
        if (!enabled) return
        scope.launch {
            try { discovery.start() }
            catch (_: Exception) { status.value = "局域网发现不可用，仍可使用已核验的手填地址" }
        }
    }
    fun foreground(active: Boolean) {
        foreground?.cancel(); foreground = null
        if (active && enabled) foreground = scope.launch {
            while (isActive) { request(); delay(15_000) }
        }
    }
    suspend fun clearPeer() = withContext(Dispatchers.IO) { gate.withLock {
        stopped = true
        storage.clear()
        hints.clear()
        route = null
        remote.value = null
        categories.value = null
        status.value = "未配对；本机业务内容保留"
        revision.update { it + 1 }
    } }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { startDiscovery(); request() }
        override fun onLost(network: Network) { if (route?.network == network) route = null; request() }
        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) { request() }
    }
    fun start() {
        if (!enabled || !started.compareAndSet(false, true)) return
        try {
            database.transaction { it.exec("CREATE TABLE IF NOT EXISTS android_purge_ack(record_id TEXT NOT NULL,peer_database_id TEXT NOT NULL,purge_id TEXT NOT NULL,PRIMARY KEY(record_id,peer_database_id))") }
            exchange.cleanup()
        }
        catch (_: Exception) { stopped = true; status.value = "同步暂存恢复失败；本机内容保留，请核验存储后手动重试" }
        // This callback only wakes the worker. `AndroidLanNetworks.available` independently selects Wi-Fi/Ethernet;
        // no network capability is required and cellular/VPN callbacks never become a route.
        try { networks.registerNetworkCallback(NetworkRequest.Builder().build(), callback) }
        catch (_: Exception) { status.value = "网络变化监听不可用；保留定期重试和手动同步" }
        startDiscovery()
        scope.launch {
            for (ignored in requests) {
                if (!runOnce()) try { enqueue(context) }
                catch (_: Exception) { status.value = "后台重试未能登记；本机内容保留，可手动同步" }
            }
        }
        try { schedule(context) }
        catch (_: Exception) { status.value = "后台同步调度不可用；本机内容保留，前台仍会尝试" }
        request()
    }
    companion object {
        private fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("content-sync", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ContentSyncWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun schedule(context: Context) {
            enqueue(context)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("content-sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ContentSyncWorker>(15, TimeUnit.MINUTES)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}

class ContentSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val runtime = AppRuntime.get(applicationContext)
        return try { runtime.ready.await(); if (runtime.sync.runOnce()) Result.success() else Result.retry() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}
