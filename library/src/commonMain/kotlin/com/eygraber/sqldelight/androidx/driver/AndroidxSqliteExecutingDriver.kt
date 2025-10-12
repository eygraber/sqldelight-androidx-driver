package com.eygraber.sqldelight.androidx.driver

import androidx.collection.LruCache
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

internal interface ConnectionHolder {
  val connection: SQLiteConnection
}

internal class AndroidxSqliteExecutingDriver(
  private val connectionPool: ConnectionPool,
  private val isStatementCacheSkipped: Boolean,
  private val statementCache: MutableMap<SQLiteConnection, LruCache<Int, AndroidxStatement>>,
  private val statementCacheLock: ReentrantLock,
  private val statementCacheSize: Int,
  private val transactions: TransactionsThreadLocal,
) : SqlDriver {
  override fun newTransaction(): QueryResult<Transacter.Transaction> =
    QueryResult.AsyncValue {
      val enclosing = transactions.get()
      val transactionConnection = when(enclosing as? ConnectionHolder) {
        null -> connectionPool.acquireWriterConnection()
        else -> enclosing.connection
      }
      val transaction = Transaction(enclosing, transactionConnection)

      transactions.set(transaction)

      transaction
    }

  override fun currentTransaction(): Transacter.Transaction? = transactions.get()

  override fun <R> executeQuery(
    identifier: Int?,
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<R> {
    val specialCase = AndroidxSqliteUtils.findSpecialCase(sql)

    return QueryResult.AsyncValue {
      if(specialCase == AndroidxSqliteSpecialCase.SetJournalMode) {
        setJournalMode(
          sql = sql,
          mapper = mapper,
          parameters = parameters,
          binders = binders,
        )
      } else {
        withConnection(
          isWrite = specialCase == AndroidxSqliteSpecialCase.ForeignKeys ||
            specialCase == AndroidxSqliteSpecialCase.Synchronous,
        ) {
          executeStatement(
            identifier = identifier,
            isStatementCacheSkipped = isStatementCacheSkipped,
            connection = this,
            createStatement = { c ->
              AndroidxQuery(
                sql = sql,
                statement = c.prepare(sql),
                argCount = parameters,
              )
            },
            binders = binders,
            result = { executeQuery(mapper) },
          )
        }
      }
    }
  }

  override fun execute(
    identifier: Int?,
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ) = QueryResult.AsyncValue {
    when(AndroidxSqliteUtils.findSpecialCase(sql)) {
      AndroidxSqliteSpecialCase.SetJournalMode -> {
        setJournalMode(
          sql = sql,
          mapper = { cursor ->
            cursor.next()
            QueryResult.AsyncValue { cursor.getString(0) }
          },
          parameters = parameters,
          binders = binders,
        )

        // hardcode 1 as the QueryResult value
        1L
      }

      AndroidxSqliteSpecialCase.ForeignKeys,
      AndroidxSqliteSpecialCase.Synchronous,
      null,
      -> withConnection(isWrite = true) {
        executeStatement(
          identifier = identifier,
          isStatementCacheSkipped = isStatementCacheSkipped,
          connection = this,
          createStatement = { c ->
            AndroidxPreparedStatement(
              sql = sql,
              statement = c.prepare(sql),
            )
          },
          binders = binders,
          result = {
            execute()
            getTotalChangedRows()
          },
        )
      }
    }
  }

  private suspend fun <R> setJournalMode(
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ) = connectionPool.setJournalMode { connection ->
    executeStatement(
      identifier = null,
      isStatementCacheSkipped = true,
      connection = connection,
      createStatement = { c ->
        AndroidxQuery(
          sql = sql,
          statement = c.prepare(sql),
          argCount = parameters,
        )
      },
      binders = binders,
      result = { executeQuery(mapper) },
    )
  }

  private suspend fun <T> executeStatement(
    identifier: Int?,
    isStatementCacheSkipped: Boolean,
    connection: SQLiteConnection,
    createStatement: (SQLiteConnection) -> AndroidxStatement,
    binders: (SqlPreparedStatement.() -> Unit)?,
    result: suspend AndroidxStatement.() -> T,
  ): T {
    val statementsCache = if(!isStatementCacheSkipped) getStatementCache(connection) else null
    var statement: AndroidxStatement? = null
    if(identifier != null && statementsCache != null) {
      // remove temporarily from the cache if present
      statement = statementsCache.remove(identifier)
    }
    if(statement == null) {
      statement = createStatement(connection)
    }
    try {
      if(binders != null) {
        statement.binders()
      }
      return statement.result()
    } finally {
      if(identifier != null && !isStatementCacheSkipped) {
        statement.reset()

        // put the statement back in the cache
        // closing any statement with this identifier
        // that was put into the cache while we used this one
        statementsCache?.put(identifier, statement)?.close()
      } else {
        statement.close()
      }
    }
  }

  private fun getStatementCache(connection: SQLiteConnection) =
    statementCacheLock.withLock {
      when {
        statementCacheSize > 0 ->
          statementCache.getOrPut(connection) {
            object : LruCache<Int, AndroidxStatement>(statementCacheSize) {
              override fun entryRemoved(
                evicted: Boolean,
                key: Int,
                oldValue: AndroidxStatement,
                newValue: AndroidxStatement?,
              ) {
                if(evicted) oldValue.close()
              }
            }
          }

        else -> null
      }
    }

  private suspend inline fun <R> withConnection(
    isWrite: Boolean,
    block: SQLiteConnection.() -> R,
  ): R = when(val holder = currentTransaction() as? ConnectionHolder) {
    null -> {
      val connection = when {
        isWrite -> connectionPool.acquireWriterConnection()
        else -> connectionPool.acquireReaderConnection()
      }

      try {
        connection.block()
      } finally {
        when {
          isWrite -> connectionPool.releaseWriterConnection()
          else -> connectionPool.releaseReaderConnection(connection)
        }
      }
    }

    else -> holder.connection.block()
  }

  override fun addListener(vararg queryKeys: String, listener: Query.Listener) {}
  override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {}
  override fun notifyListeners(vararg queryKeys: String) {}
  override fun close() {}

  private inner class Transaction(
    override val enclosingTransaction: Transacter.Transaction?,
    override val connection: SQLiteConnection,
  ) : Transacter.Transaction(), ConnectionHolder {
    init {
      if(enclosingTransaction == null) {
        connection.execSQL("BEGIN IMMEDIATE")
      }
    }

    override fun endTransaction(successful: Boolean): QueryResult<Unit> =
      QueryResult.AsyncValue {
        if(enclosingTransaction == null) {
          try {
            if(successful) {
              connection.execSQL("COMMIT")
            } else {
              connection.execSQL("ROLLBACK")
            }
          } finally {
            connectionPool.releaseWriterConnection()
          }
        }
        transactions.set(enclosingTransaction)
      }
  }
}

private fun SQLiteConnection.getTotalChangedRows() =
  prepare("SELECT changes()").use { statement ->
    if(statement.step()) statement.getLong(0) else 0
  }
