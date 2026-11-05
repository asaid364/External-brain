package app.liferecord.desktop

import app.liferecord.core.*
import java.nio.file.Path
import java.sql.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One connection and reentrant lock: nested reads participate in the current transaction. */
class WindowsDatabase(path: Path) : SqlDatabase {
    private val lock = ReentrantLock()
    private val connection: Connection
    private inline fun <T> sql(block: () -> T): T = try { block() }
    catch (failure: SQLException) { throw SqlFailure("本机数据库操作失败，原内容未被替换", failure) }
    init {
        path.toAbsolutePath().parent.toFile().mkdirs()
        Class.forName("org.sqlite.JDBC")
        connection = sql { DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}") }
        try {
            exec("PRAGMA foreign_keys=ON")
            exec("PRAGMA journal_mode=WAL")
            exec("PRAGMA synchronous=FULL")
            transaction {
                val version = query("PRAGMA user_version").use { check(it.next()); it.long(0).toInt() }
                require(version in 0..RecordSchema.VERSION) { "不支持的数据库版本，未修改原有内容" }
                if (version == 0) RecordSchema.create(this)
                else if (version < RecordSchema.VERSION) RecordSchema.upgrade(this, version, RecordSchema.VERSION)
                exec("PRAGMA user_version=${RecordSchema.VERSION}")
            }
        } catch (failure: Throwable) { connection.close(); throw failure }
    }
    private fun PreparedStatement.bind(args: List<Any?>) {
        args.forEachIndexed { index, value -> when (value) {
            null -> setNull(index + 1, Types.NULL)
            is String -> setString(index + 1, value)
            is Int -> setInt(index + 1, value)
            is Long -> setLong(index + 1, value)
            else -> error("Unsupported SQL binding")
        } }
    }
    override fun exec(sql: String, args: List<Any?>): Int = lock.withLock {
        sql {
            connection.prepareStatement(sql).use { statement ->
                statement.bind(args)
                if (statement.execute()) { statement.resultSet.close(); 0 } else statement.updateCount.coerceAtLeast(0)
            }
        }
    }
    override fun query(sql: String, args: List<Any?>): SqlCursor {
        lock.lock()
        try {
            val statement = connection.prepareStatement(sql)
            try {
                statement.bind(args)
                return JdbcCursor(statement, statement.executeQuery()) { lock.unlock() }
            } catch (failure: Throwable) { statement.close(); throw failure }
        } catch (failure: Throwable) {
            lock.unlock()
            if (failure is SQLException) throw SqlFailure("本机数据库读取失败", failure)
            throw failure
        }
    }
    override fun <T> transaction(block: (SqlDatabase) -> T): T = lock.withLock {
        sql {
            if (!connection.autoCommit) return@sql block(this)
            connection.autoCommit = false
            try { val value = block(this); connection.commit(); value }
            catch (failure: Throwable) {
                try { connection.rollback() } catch (rollback: SQLException) { failure.addSuppressed(rollback) }
                throw failure
            } finally { connection.autoCommit = true }
        }
    }
    override fun close() = lock.withLock { sql { connection.close() } }
    private class JdbcCursor(val statement: PreparedStatement, val rows: ResultSet, val release: () -> Unit) : SqlCursor {
        private var closed = false
        private inline fun <T> sql(block: () -> T): T = try { block() }
        catch (failure: SQLException) { throw SqlFailure("本机数据库读取失败", failure) }
        override fun next() = sql { rows.next() }
        override fun columnIndex(name: String) = sql { rows.findColumn(name) - 1 }
        override fun isNull(index: Int): Boolean = sql { rows.getObject(index + 1); rows.wasNull() }
        override fun string(index: Int): String = sql { rows.getString(index + 1) }
        override fun long(index: Int) = sql { rows.getLong(index + 1) }
        override fun close() { if (!closed) { closed = true; try { rows.close() } finally { try { statement.close() } finally { release() } } } }
    }
}
