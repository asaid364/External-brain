package app.liferecord.core

import java.security.MessageDigest
import java.time.Clock
import java.util.UUID


enum class GoalReviewCoordinationError {
    INPUT_MISMATCH,
    GENERATION_RUNNING,
    GENERATION_UNKNOWN,
    LEASE_INVALID,
}

class GoalReviewCoordinationException(
    val error: GoalReviewCoordinationError,
    message: String,
) : IllegalStateException(message)

/** Durable, local-only single-flight coordination for billable goal review generation. */
class GoalReviewCoordinator(
    private val database: SqlDatabase,
    private val goals: GoalRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        database.transaction { db ->
            db.exec(
                "CREATE TABLE IF NOT EXISTS goal_review_coord(" +
                    "review_key TEXT PRIMARY KEY,run_id TEXT NOT NULL,holder TEXT NOT NULL," +
                    "input_hash TEXT NOT NULL,token TEXT NOT NULL," +
                    "state TEXT NOT NULL CHECK(state IN ('RUNNING','UNKNOWN','FINISHED'))," +
                    "expires_at INTEGER NOT NULL)",
            )
            // A restart cannot establish whether an already-started paid request completed.
            db.exec("UPDATE goal_review_coord SET state='UNKNOWN' WHERE state='RUNNING'")
        }
    }

    fun current(key: GoalReviewKey): GoalReviewLease? = database.transaction { db ->
        val normalized = GoalCodec.validateReviewKey(key)
        expire(db, normalized)
        read(db, normalized)
    }

    fun claim(
        key: GoalReviewKey,
        runId: String,
        holder: String,
        inputHash: String,
        takeover: Boolean = false,
    ): GoalReviewLease = database.transaction { db ->
        val normalized = GoalCodec.validateReviewKey(key)
        requireIdentity(runId, "runId")
        requireIdentity(holder, "holder")
        requireIdentity(inputHash, "inputHash")
        val capturedHash = try {
            goals.snapshot(normalized).hash
        } catch (failure: IllegalArgumentException) {
            throw inputMismatch(failure)
        } catch (failure: UnresolvedConflict) {
            throw inputMismatch(failure)
        }
        if (!constantEquals(capturedHash, inputHash)) {
            throw GoalReviewCoordinationException(
                GoalReviewCoordinationError.INPUT_MISMATCH,
                "两端目标回顾输入不一致，请先完成双向同步",
            )
        }

        expire(db, normalized)
        val old = read(db, normalized)
        if (old != null && old.runId == runId && old.holder == holder) {
            if (!constantEquals(old.inputHash, inputHash)) {
                throw GoalReviewCoordinationException(
                    GoalReviewCoordinationError.INPUT_MISMATCH,
                    "同一目标回顾任务的输入已变化",
                )
            }
            if (old.state != RUNNING) {
                throw GoalReviewCoordinationException(
                    GoalReviewCoordinationError.LEASE_INVALID,
                    "此目标回顾任务已结束或状态未知，不能复活",
                )
            }
            return@transaction old
        }
        if (old?.state == RUNNING) {
            throw GoalReviewCoordinationException(
                GoalReviewCoordinationError.GENERATION_RUNNING,
                "该目标回顾窗口的另一生成任务仍在运行",
            )
        }
        if (old?.state == UNKNOWN && !takeover) {
            throw GoalReviewCoordinationException(
                GoalReviewCoordinationError.GENERATION_UNKNOWN,
                "另一端目标回顾生成状态未知；需明确确认可能重复计费后重试",
            )
        }

        val lease = GoalReviewLease(
            normalized,
            runId,
            holder,
            inputHash,
            UUID.randomUUID().toString(),
            RUNNING,
            clock.millis() + LEASE_MILLIS,
        )
        val args = listOf(
            lease.runId, lease.holder, lease.inputHash, lease.token,
            lease.state, lease.expiresAt, normalized.encoded,
        )
        if (db.exec(
                "UPDATE goal_review_coord SET run_id=?,holder=?,input_hash=?,token=?,state=?,expires_at=? WHERE review_key=?",
                args,
            ) == 0
        ) {
            db.exec(
                "INSERT INTO goal_review_coord(run_id,holder,input_hash,token,state,expires_at,review_key) VALUES(?,?,?,?,?,?,?)",
                args,
            )
        }
        lease
    }

    fun heartbeat(key: GoalReviewKey, runId: String, token: String): GoalReviewLease = database.transaction { db ->
        val normalized = GoalCodec.validateReviewKey(key)
        expire(db, normalized)
        val lease = authorized(db, normalized, runId, token)
        if (lease.state != RUNNING) invalidLease()
        val expiresAt = clock.millis() + LEASE_MILLIS
        val changed = db.exec(
            "UPDATE goal_review_coord SET expires_at=? WHERE review_key=? AND run_id=? AND token=? AND state='RUNNING'",
            listOf(expiresAt, normalized.encoded, runId, token),
        )
        if (changed != 1) invalidLease()
        lease.copy(expiresAt = expiresAt)
    }

    fun finish(key: GoalReviewKey, runId: String, token: String): GoalReviewLease = database.transaction { db ->
        val normalized = GoalCodec.validateReviewKey(key)
        expire(db, normalized)
        val lease = authorized(db, normalized, runId, token)
        if (lease.state != RUNNING) invalidLease()
        val changed = db.exec(
            "UPDATE goal_review_coord SET state='FINISHED' WHERE review_key=? AND run_id=? AND token=? AND state='RUNNING'",
            listOf(normalized.encoded, runId, token),
        )
        if (changed != 1) invalidLease()
        lease.copy(state = FINISHED)
    }

    private fun authorized(db: SqlDatabase, key: GoalReviewKey, runId: String, token: String): GoalReviewLease {
        requireIdentity(runId, "runId")
        requireIdentity(token, "token")
        val lease = read(db, key) ?: invalidLease()
        if (lease.runId != runId || !constantEquals(lease.token, token)) invalidLease()
        return lease
    }

    private fun expire(db: SqlDatabase, key: GoalReviewKey) {
        db.exec(
            "UPDATE goal_review_coord SET state='UNKNOWN' WHERE review_key=? AND state='RUNNING' AND expires_at<=?",
            listOf(key.encoded, clock.millis()),
        )
    }

    private fun read(db: SqlDatabase, key: GoalReviewKey): GoalReviewLease? =
        db.query(
            "SELECT run_id,holder,input_hash,token,state,expires_at FROM goal_review_coord WHERE review_key=?",
            listOf(key.encoded),
        ).use { cursor ->
            if (!cursor.next()) null else GoalReviewLease(
                key,
                cursor.string(0),
                cursor.string(1),
                cursor.string(2),
                cursor.string(3),
                cursor.string(4),
                cursor.long(5),
            )
        }

    private fun requireIdentity(value: String, name: String) {
        require(value.isNotBlank() && value.length <= 256 && value.none { it <= ' ' }) { "$name 格式无效" }
    }

    private fun inputMismatch(cause: RuntimeException) = GoalReviewCoordinationException(
        GoalReviewCoordinationError.INPUT_MISMATCH,
        "目标回顾输入无法与本机当前数据核对，请先完成双向同步",
    ).also { it.initCause(cause) }

    private fun invalidLease(): Nothing = throw GoalReviewCoordinationException(
        GoalReviewCoordinationError.LEASE_INVALID,
        "目标回顾生成租约无效或已被替代",
    )

    private fun constantEquals(first: String, second: String): Boolean = MessageDigest.isEqual(
        first.toByteArray(Charsets.UTF_8),
        second.toByteArray(Charsets.UTF_8),
    )

    companion object {
        const val LEASE_MILLIS = 180_000L
        const val HEARTBEAT_MILLIS = 30_000L
        private const val RUNNING = "RUNNING"
        private const val UNKNOWN = "UNKNOWN"
        private const val FINISHED = "FINISHED"
    }
}
