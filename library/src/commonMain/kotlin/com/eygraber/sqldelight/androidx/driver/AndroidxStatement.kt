package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteStatement
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlPreparedStatement

internal interface AndroidxStatement : SqlPreparedStatement {
  fun execute()
  suspend fun <R> executeQuery(mapper: suspend (SqlCursor) -> QueryResult<R>): R
  fun reset()
  fun close()
}

internal class AndroidxPreparedStatement(
  private val sql: String,
  private val statement: SQLiteStatement,
) : AndroidxStatement {
  override fun bindBytes(index: Int, bytes: ByteArray?) {
    if(bytes == null) statement.bindNull(index + 1) else statement.bindBlob(index + 1, bytes)
  }

  override fun bindLong(index: Int, long: Long?) {
    if(long == null) statement.bindNull(index + 1) else statement.bindLong(index + 1, long)
  }

  override fun bindDouble(index: Int, double: Double?) {
    if(double == null) statement.bindNull(index + 1) else statement.bindDouble(index + 1, double)
  }

  override fun bindString(index: Int, string: String?) {
    if(string == null) statement.bindNull(index + 1) else statement.bindText(index + 1, string)
  }

  override fun bindBoolean(index: Int, boolean: Boolean?) {
    if(boolean == null) {
      statement.bindNull(index + 1)
    } else {
      statement.bindLong(index + 1, if(boolean) 1L else 0L)
    }
  }

  override suspend fun <R> executeQuery(mapper: suspend (SqlCursor) -> QueryResult<R>): R =
    throw UnsupportedOperationException()

  override fun execute() {
    var cont = true
    while(cont) {
      cont = statement.step()
    }
  }

  override fun toString() = sql

  override fun reset() {
    statement.reset()
  }

  override fun close() {
    statement.close()
  }
}

internal class AndroidxQuery(
  private val sql: String,
  private val statement: SQLiteStatement,
  argCount: Int,
) : AndroidxStatement {
  private val binds = MutableList<((SQLiteStatement) -> Unit)?>(argCount) { null }

  override fun bindBytes(index: Int, bytes: ByteArray?) {
    binds[index] = { if(bytes == null) it.bindNull(index + 1) else it.bindBlob(index + 1, bytes) }
  }

  override fun bindLong(index: Int, long: Long?) {
    binds[index] = { if(long == null) it.bindNull(index + 1) else it.bindLong(index + 1, long) }
  }

  override fun bindDouble(index: Int, double: Double?) {
    binds[index] =
      { if(double == null) it.bindNull(index + 1) else it.bindDouble(index + 1, double) }
  }

  override fun bindString(index: Int, string: String?) {
    binds[index] =
      { if(string == null) it.bindNull(index + 1) else it.bindText(index + 1, string) }
  }

  override fun bindBoolean(index: Int, boolean: Boolean?) {
    binds[index] = { statement ->
      if(boolean == null) {
        statement.bindNull(index + 1)
      } else {
        statement.bindLong(index + 1, if(boolean) 1L else 0L)
      }
    }
  }

  override fun execute() = throw UnsupportedOperationException()

  override suspend fun <R> executeQuery(mapper: suspend (SqlCursor) -> QueryResult<R>): R {
    for(action in binds) {
      requireNotNull(action).invoke(statement)
    }

    return mapper(AndroidxCursor(statement)).await()
  }

  override fun toString() = sql

  override fun reset() {
    statement.reset()
  }

  override fun close() {
    statement.close()
  }
}

private class AndroidxCursor(
  private val statement: SQLiteStatement,
) : SqlCursor {
  override fun next(): QueryResult.Value<Boolean> = QueryResult.Value(statement.step())
  override fun getString(index: Int) =
    if(statement.isNull(index)) null else statement.getText(index)

  override fun getLong(index: Int) = if(statement.isNull(index)) null else statement.getLong(index)
  override fun getBytes(index: Int) =
    if(statement.isNull(index)) null else statement.getBlob(index)

  override fun getDouble(index: Int) =
    if(statement.isNull(index)) null else statement.getDouble(index)

  override fun getBoolean(index: Int) =
    if(statement.isNull(index)) null else statement.getLong(index) == 1L
}
