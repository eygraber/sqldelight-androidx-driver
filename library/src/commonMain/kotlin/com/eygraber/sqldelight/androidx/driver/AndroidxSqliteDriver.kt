package com.eygraber.sqldelight.androidx.driver

import androidx.collection.LruCache
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import kotlinx.atomicfu.locks.SynchronizedObject

internal expect class TransactionsThreadLocal() {
  internal fun get(): Transacter.Transaction?
  internal fun set(transaction: Transacter.Transaction?)
}

internal const val DEFAULT_CACHE_SIZE = 20

/**
 * @param name Name of the database file, an empty string for a temporary database, or null for an in-memory database
 * (see [Sqlite open documentation](https://www.sqlite.org/c3ref/open.html)).
 *
 * @see AndroidxSqliteDriver
 * @see SqlSchema.create
 * @see SqlSchema.migrate
 */
public class AndroidxSqliteDriver(
  driver: SQLiteDriver,
  databaseType: AndroidxSqliteDatabaseType,
  cacheSize: Int = DEFAULT_CACHE_SIZE,
) : SqlDriver {
  private val transactions = TransactionsThreadLocal()
  private val connection by lazy {
    driver.open(
      when(databaseType) {
        is AndroidxSqliteDatabaseType.File -> databaseType.filename
        AndroidxSqliteDatabaseType.Memory -> ":memory:"
        AndroidxSqliteDatabaseType.Temporary -> ""
      },
    )
  }

  private val statements = object : LruCache<Int, AndroidxStatement>(cacheSize) {
    override fun entryRemoved(
      evicted: Boolean,
      key: Int,
      oldValue: AndroidxStatement,
      newValue: AndroidxStatement?,
    ) {
      if(evicted) oldValue.close()
    }
  }

  private val listenersLock = SynchronizedObject()
  private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()

  override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
    synchronized(listenersLock) {
      queryKeys.forEach {
        listeners.getOrPut(it) { linkedSetOf() }.add(listener)
      }
    }
  }

  override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
    synchronized(listenersLock) {
      queryKeys.forEach {
        listeners[it]?.remove(listener)
      }
    }
  }

  override fun notifyListeners(vararg queryKeys: String) {
    val listenersToNotify = linkedSetOf<Query.Listener>()
    synchronized(listenersLock) {
      queryKeys.forEach { listeners[it]?.let(listenersToNotify::addAll) }
    }
    listenersToNotify.forEach(Query.Listener::queryResultsChanged)
  }

  override fun newTransaction(): QueryResult<Transacter.Transaction> {
    val enclosing = transactions.get()
    val transaction = Transaction(enclosing)
    transactions.set(transaction)

    if(enclosing == null) {
      connection.execSQL("BEGIN IMMEDIATE")
    }

    return QueryResult.Value(transaction)
  }

  override fun currentTransaction(): Transacter.Transaction? = transactions.get()

  public inner class Transaction(
    override val enclosingTransaction: Transacter.Transaction?,
  ) : Transacter.Transaction() {
    override fun endTransaction(successful: Boolean): QueryResult<Unit> {
      if(enclosingTransaction == null) {
        if(successful) {
          connection.execSQL("COMMIT")
        } else {
          connection.execSQL("ROLLBACK")
        }
      }
      transactions.set(enclosingTransaction)
      return QueryResult.Unit
    }
  }

  private fun <T> execute(
    identifier: Int?,
    createStatement: () -> AndroidxStatement,
    binders: (SqlPreparedStatement.() -> Unit)?,
    result: AndroidxStatement.() -> T,
  ): QueryResult.Value<T> {
    var statement: AndroidxStatement? = null
    if(identifier != null) {
      statement = statements[identifier]

      // remove temporarily from the cache
      if (statement != null) {
        statements.remove(identifier)
      }
    }
    if(statement == null) {
      statement = createStatement()
    }
    try {
      if(binders != null) {
        statement.binders()
      }
      return QueryResult.Value(statement.result())
    } finally {
      if(identifier != null) {
        statements.put(identifier, statement)?.close()
        statement.reset()
      } else {
        statement.close()
      }
    }
  }

  override fun execute(
    identifier: Int?,
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<Long> =
    execute(identifier, { AndroidxPreparedStatement(connection.prepare(sql)) }, binders, { execute() })

  override fun <R> executeQuery(
    identifier: Int?,
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult.Value<R> =
    execute(identifier, { AndroidxQuery(sql, connection, parameters) }, binders) { executeQuery(mapper) }

  override fun close() {
    statements.snapshot().values.forEach { it.close() }
    statements.evictAll()
    return connection.close()
  }
}

internal interface AndroidxStatement : SqlPreparedStatement {
  fun execute(): Long
  fun <R> executeQuery(mapper: (SqlCursor) -> QueryResult<R>): R
  fun reset()
  fun close()
}

private class AndroidxPreparedStatement(
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

  override fun <R> executeQuery(mapper: (SqlCursor) -> QueryResult<R>): R =
    throw UnsupportedOperationException()

  override fun execute(): Long {
    var cont = true
    while(cont) {
      cont = statement.step()
    }
    return statement.getColumnCount().toLong()
  }

  override fun reset() {
    statement.reset()
  }

  override fun close() {
    statement.close()
  }
}

private class AndroidxQuery(
  private val sql: String,
  private val connection: SQLiteConnection,
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

  override fun <R> executeQuery(mapper: (SqlCursor) -> QueryResult<R>): R {
    val statement = connection.prepare(sql)
    for(action in binds) {
      requireNotNull(action).invoke(statement)
    }

    return try {
      mapper(AndroidxCursor(statement)).value
    } finally {
      statement.close()
    }
  }

  override fun toString() = sql

  override fun reset() {}

  override fun close() {}
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
