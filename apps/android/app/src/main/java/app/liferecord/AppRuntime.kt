package app.liferecord

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.liferecord.core.*
import kotlinx.coroutines.*
import java.time.Clock
import kotlinx.coroutines.sync.Mutex

/** Owns the application's connection and background generation lifecycle. */
class AppRuntime internal constructor(
    context: Context,
    val database: SqlDatabase = AndroidDatabase(context.applicationContext),
    val settings: AiSettings = AiSettings(AndroidAiStorage(context.applicationContext)),
    val production: Boolean = false,
    goalAi: GoalAnalysisAi = GoalAnalysisClient(),
    val goalClock: Clock = Clock.systemDefaultZone(),
) {
    val store = RecordStore(database)
    val repository = ReportRepository(database)
    val goals = GoalRepository(database, goalClock)
    private val serviceGate = Mutex()
    val sync = SyncRuntime(context.applicationContext, database, enabled = production)
    internal val generation = if (production) AndroidGenerationGate(context.applicationContext, database, repository, sync) else null
    val goalGeneration = if (production) AndroidGoalGenerationGate(context.applicationContext, database, goals, sync) else null
    private val online: () -> Boolean = {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    val engine = ReportEngine(repository, { settings.load() }, online = online,
        generationGate = generation, serviceGate = serviceGate)
    val goalEngine = GoalAnalysisEngine(goals, { settings.load() }, ai = goalAi,
        clock = goalClock, online = { if (production) online() else goalAi !is GoalAnalysisClient },
        serviceGate = serviceGate, generationGate = goalGeneration)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val goalScheduleGate = Mutex()
    private val applicationContext = context.applicationContext

    internal fun goalNetworkAvailable(): Boolean = online()

    /** Resume and AI settings entry points; injected runtimes never enqueue work. */
    fun rearmGoalReviews() {
        if (production) scope.launch { GoalReviewSchedule.rearm(applicationContext, this@AppRuntime) }
    }

    /** Call after changing this device's goal permission, including revocation. */
    suspend fun goalPermissionsChanged(goalId: String) {
        val active = database.transaction { db ->
            db.query("SELECT review_key FROM goal_review_runs WHERE state IN ('PREPARING','RUNNING','RESULT_READY')").use { cursor ->
                buildList {
                    while (cursor.next()) GoalReviewKey.decode(cursor.string(0)).takeIf { it.goalId == goalId }?.let(::add)
                }
            }
        }
        active.forEach { goalEngine.cancel(it) }
        if (production) GoalReviewSchedule.rearm(applicationContext, this)
    }
    val ready = scope.async {
        (database as? AndroidDatabase)?.let { db ->
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                db.contentChanges.collect {
                    sync.contentCommitted()
                    rearmGoalReviews()
                }
            }
        }
        engine.recover()
        goalEngine.recover()
        if (production) {
            sync.start()
            rearmGoalReviews()
        }
    }

    companion object {
        @Volatile private var instance: AppRuntime? = null
        fun get(context: Context): AppRuntime = instance ?: synchronized(this) {
            instance ?: AppRuntime(context.applicationContext, production = true).also { instance = it }
        }
    }
}
