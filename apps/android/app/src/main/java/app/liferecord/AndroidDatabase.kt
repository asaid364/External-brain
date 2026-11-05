package app.liferecord

import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import app.liferecord.core.RecordSchema
import app.liferecord.core.SqlCursor
import app.liferecord.core.SqlDatabase
import app.liferecord.core.SqlFailure
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Android SQLite adapter. The application runtime owns and closes this instance. */
class AndroidDatabase(context: Context, name: String = "records.db") : SqlDatabase {
    private val committed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val contentChanges = committed.asSharedFlow()
    private val helper = object : SQLiteOpenHelper(
        context,
        name,
        null,
        RecordSchema.VERSION,
        DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("本机数据库无法读取，原文件已保留") },
    ) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            AndroidConnection(db, ownsDatabase = false).use { RecordSchema.create(it) }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            AndroidConnection(db, ownsDatabase = false).use { RecordSchema.upgrade(it, oldVersion, newVersion) }
        }
    }

    private val transactionConnection = ThreadLocal<AndroidConnection?>()

    private fun connection(): AndroidConnection = transactionConnection.get() ?: try {
        AndroidConnection(helper.writableDatabase, ownsDatabase = false)
    } catch (error: SQLiteException) {
        throw SqlFailure("数据库无法打开", error)
    }

    override fun exec(sql: String, args: List<Any?>): Int = connection().exec(sql, args)

    override fun query(sql: String, args: List<Any?>): SqlCursor = connection().query(sql, args)

    override fun <T> transaction(block: (SqlDatabase) -> T): T {
        val existing = transactionConnection.get()
        if (existing != null) return block(existing)
        val db = try {
            helper.writableDatabase
        } catch (error: SQLiteException) {
            throw SqlFailure("数据库无法打开", error)
        }
        val connection = AndroidConnection(db, ownsDatabase = false)
        var began = false
        var primaryFailure: Throwable? = null
        var contentChanged = false
        var committedSuccessfully = false
        try {
            db.beginTransaction()
            began = true
            transactionConnection.set(connection)
            val before = contentVersion(connection)
            val result = block(connection)
            contentChanged = contentVersion(connection) != before
            db.setTransactionSuccessful()
            return result
        } catch (error: Throwable) {
            val mapped = if (error is SQLiteException) SqlFailure("数据库事务失败", error) else error
            primaryFailure = mapped
            throw mapped
        } finally {
            transactionConnection.remove()
            if (began) try {
                db.endTransaction()
                committedSuccessfully = primaryFailure == null
            } catch (error: SQLiteException) {
                val endFailure = SqlFailure("数据库事务结束失败", error)
                if (primaryFailure != null) primaryFailure.addSuppressed(endFailure) else throw endFailure
            }
            // Notification only: consumers dispatch IO after the outer transaction has ended.
            if (committedSuccessfully && contentChanged) committed.tryEmit(Unit)
        }
    }

    private fun contentVersion(db: SqlDatabase): Pair<Long, Long> =
        db.query("SELECT commit_seq,content_epoch FROM sync_local WHERE singleton=1").use {
            check(it.next()) { "同步身份尚未初始化" }
            it.long(0) to it.long(1)
        }

    override fun close() = helper.close()
}

private class AndroidConnection(
    private val database: SQLiteDatabase,
    private val ownsDatabase: Boolean,
) : SqlDatabase {
    override fun exec(sql: String, args: List<Any?>): Int = try {
        val statement = database.compileStatement(sql)
        statement.use {
            args.forEachIndexed { index, value -> it.bind(index + 1, value) }
            when (sql.trimStart().substringBefore(' ').uppercase()) {
                "INSERT" -> if (it.executeInsert() == -1L) 0 else 1
                "UPDATE", "DELETE" -> it.executeUpdateDelete()
                else -> {
                    it.execute()
                    0
                }
            }
        }
    } catch (error: SQLiteException) {
        throw SqlFailure("数据库写入失败", error)
    }

    override fun query(sql: String, args: List<Any?>): SqlCursor = try {
        val bindings = args.map { value ->
            when (value) {
                null -> null
                is String -> value
                is Int, is Long -> value.toString()
                else -> throw IllegalArgumentException("不支持的 SQL 参数类型: ${value::class.java.name}")
            }
        }.toTypedArray()
        AndroidCursor(database.rawQuery(sql, bindings))
    } catch (error: SQLiteException) {
        throw SqlFailure("数据库查询失败", error)
    }

    override fun <T> transaction(block: (SqlDatabase) -> T): T {
        if (database.inTransaction()) return block(this)
        var began = false
        var primaryFailure: Throwable? = null
        try {
            database.beginTransaction()
            began = true
            val result = block(this)
            database.setTransactionSuccessful()
            return result
        } catch (error: Throwable) {
            val mapped = if (error is SQLiteException) SqlFailure("数据库事务失败", error) else error
            primaryFailure = mapped
            throw mapped
        } finally {
            if (began) try {
                database.endTransaction()
            } catch (error: SQLiteException) {
                val endFailure = SqlFailure("数据库事务结束失败", error)
                if (primaryFailure != null) primaryFailure.addSuppressed(endFailure) else throw endFailure
            }
        }
    }

    override fun close() {
        if (ownsDatabase) database.close()
    }

    private fun android.database.sqlite.SQLiteStatement.bind(index: Int, value: Any?) {
        when (value) {
            null -> bindNull(index)
            is String -> bindString(index, value)
            is Int -> bindLong(index, value.toLong())
            is Long -> bindLong(index, value)
            else -> throw IllegalArgumentException("不支持的 SQL 参数类型: ${value::class.java.name}")
        }
    }
}

private class AndroidCursor(private val cursor: Cursor) : SqlCursor {
    private inline fun <T> sql(block: () -> T): T = try {
        block()
    } catch (error: SQLiteException) {
        throw SqlFailure("数据库查询失败", error)
    }

    override fun next(): Boolean = sql { cursor.moveToNext() }
    override fun columnIndex(name: String): Int = sql { cursor.getColumnIndexOrThrow(name) }
    override fun isNull(index: Int): Boolean = sql { cursor.isNull(index) }
    override fun string(index: Int): String = sql { cursor.getString(index) }
    override fun long(index: Int): Long = sql { cursor.getLong(index) }
    override fun close(): Unit = sql { cursor.close() }
}
