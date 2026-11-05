package app.liferecord.desktop

import app.liferecord.core.ReportRepository
import app.liferecord.core.SqlDatabase
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Local-only durable state. Never part of sync snapshots or AI configuration. */
data class GenerationLease(
    val date: String,
    val runId: String,
    val holder: String,
    val inputHash: String,
    val token: String,
    val state: String,
    val expiresAt: Long,
)

class GenerationCoordinator(
    private val database: SqlDatabase,
    private val reports: ReportRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        database.transaction { db ->
            db.exec("CREATE TABLE IF NOT EXISTS generation_coord(date TEXT PRIMARY KEY,run_id TEXT NOT NULL,holder TEXT NOT NULL,input_hash TEXT NOT NULL,token TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('RUNNING','UNKNOWN','FINISHED')),expires_at INTEGER NOT NULL)")
            // A process restart cannot prove that an external billable call stopped.
            db.exec("UPDATE generation_coord SET state='UNKNOWN' WHERE state='RUNNING'")
        }
    }

    fun current(date: String): GenerationLease? = database.transaction { db ->
        LocalDate.parse(date)
        expire(db, date)
        read(db, date)
    }

    fun claim(date: String, runId: String, holder: String, inputHash: String, takeover: Boolean = false): GenerationLease = database.transaction { db ->
        LocalDate.parse(date)
        require(runId.isNotBlank() && holder.isNotBlank() && inputHash.isNotBlank()) { "生成协调身份无效" }
        require(reports.input(date).hash == inputHash) { "两端日报输入不一致，请先完成双向同步" }
        expire(db, date)
        val old = read(db, date)
        if (old != null && old.runId == runId && old.holder == holder) {
            require(old.inputHash == inputHash) { "同一生成任务的输入已变化" }
            check(old.state == "RUNNING") { "此生成任务已结束或状态未知，不能重放" }
            return@transaction old
        }
        check(old?.state != "RUNNING") { "该日期另一生成任务仍在运行" }
        check(old?.state != "UNKNOWN" || takeover) { "另一端生成状态未知；需明确确认可能重复计费后重试" }
        val lease = GenerationLease(date, runId, holder, inputHash, UUID.randomUUID().toString(), "RUNNING", clock.millis() + LEASE_MILLIS)
        val args = listOf(lease.runId, lease.holder, lease.inputHash, lease.token, lease.state, lease.expiresAt, date)
        if (db.exec("UPDATE generation_coord SET run_id=?,holder=?,input_hash=?,token=?,state=?,expires_at=? WHERE date=?", args) == 0) {
            db.exec("INSERT INTO generation_coord(run_id,holder,input_hash,token,state,expires_at,date) VALUES(?,?,?,?,?,?,?)", args)
        }
        lease
    }

    fun heartbeat(date: String, runId: String, token: String): GenerationLease = database.transaction { db ->
        expire(db, date)
        val lease = authorized(db, date, runId, token)
        check(lease.state == "RUNNING") { "生成协调租约已失效，不能自动恢复或接管" }
        val expires = clock.millis() + LEASE_MILLIS
        db.exec("UPDATE generation_coord SET expires_at=? WHERE date=? AND run_id=? AND token=? AND state='RUNNING'", listOf(expires, date, runId, token))
        lease.copy(expiresAt = expires)
    }

    fun finish(date: String, runId: String, token: String): GenerationLease = database.transaction { db ->
        val lease = authorized(db, date, runId, token)
        db.exec("UPDATE generation_coord SET state='FINISHED' WHERE date=? AND run_id=? AND token=?", listOf(date, runId, token))
        lease.copy(state = "FINISHED")
    }

    private fun authorized(db: SqlDatabase, date: String, runId: String, token: String): GenerationLease {
        val lease = requireNotNull(read(db, date)) { "生成协调任务不存在" }
        require(lease.runId == runId && MessageDigest.isEqual(lease.token.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))) { "生成协调凭据已被替代" }
        return lease
    }

    private fun expire(db: SqlDatabase, date: String) {
        db.exec("UPDATE generation_coord SET state='UNKNOWN' WHERE date=? AND state='RUNNING' AND expires_at<=?", listOf(date, clock.millis()))
    }

    private fun read(db: SqlDatabase, date: String): GenerationLease? = db.query("SELECT run_id,holder,input_hash,token,state,expires_at FROM generation_coord WHERE date=?", listOf(date)).use {
        if (!it.next()) null else GenerationLease(date, it.string(0), it.string(1), it.string(2), it.string(3), it.string(4), it.long(5))
    }

    companion object {
        const val LEASE_MILLIS = 180_000L
        const val HEARTBEAT_MILLIS = 30_000L
    }
}
