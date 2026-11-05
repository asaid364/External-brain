package app.liferecord.core

import java.io.Closeable

interface SqlDatabase : Closeable {
    fun exec(sql: String, args: List<Any?> = emptyList()): Int
    fun query(sql: String, args: List<Any?> = emptyList()): SqlCursor
    fun <T> transaction(block: (SqlDatabase) -> T): T
}

interface SqlCursor : Closeable {
    fun next(): Boolean
    fun columnIndex(name: String): Int
    fun isNull(index: Int): Boolean
    fun string(index: Int): String
    fun long(index: Int): Long
}

class SqlFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal fun SqlCursor.index(name: String): Int = columnIndex(name)
internal fun SqlCursor.string(name: String): String = string(index(name))
internal fun SqlCursor.optional(name: String): String? = index(name).let { if (isNull(it)) null else string(it) }
internal fun SqlCursor.long(name: String): Long = long(index(name))
