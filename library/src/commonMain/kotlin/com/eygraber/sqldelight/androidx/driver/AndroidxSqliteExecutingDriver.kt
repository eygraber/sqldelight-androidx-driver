package com.eygraber.sqldelight.androidx.driver

import androidx.collection.LruCache
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransactionContextProvider
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineName
import kotlin.coroutines.coroutineContext

internal interface ConnectionHolder {
  val connection: SQLiteConnection
}

internal class AndroidxSqliteExecutingDriver(
  private val connectionPool: ConnectionPool,
  private val isStatementCacheSkipped: Boolean,
  private val statementCache: MutableMap<SQLiteConnection, LruCache<Int, AndroidxStatement>>,
  private val statementCacheLock: ReentrantLock,
  private val statementCacheSize: Int,
) : SqlDriver, TransactionContextProvider {
  private var activeTransaction: Transacter.Transaction? = null
  private var activeTransactionCoroutineName: CoroutineName? = null

  override suspend fun <R> withTransactionContext(block: suspend () -> R): R =
    withContext(isWrite = true, block = block)

  override fun newTransaction(): QueryResult<Transacter.Transaction> =
    QueryResult.AsyncValue {
      if(activeTransactionCoroutineName == null) {
        activeTransactionCoroutineName = coroutineContext[CoroutineName]
      }

      val enclosing = activeTransaction
      val transactionConnection = when {
        isActiveTransactionOwner() -> when(enclosing as? ConnectionHolder) {
          null -> connectionPool.acquireWriterConnection()
          else -> enclosing.connection
        }

        else -> connectionPool.acquireWriterConnection()
      }
      val transaction = Transaction(enclosing, transactionConnection)

      activeTransaction = transaction

      transaction
    }

  override fun currentTransaction(): Transacter.Transaction? = activeTransaction

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
        val isWrite = specialCase == AndroidxSqliteSpecialCase.ForeignKeys ||
          specialCase == AndroidxSqliteSpecialCase.Synchronous

        withContext(isWrite = isWrite) {
          withConnection(isWrite = isWrite) {
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
      -> withContext(isWrite = true) {
        withConnection(isWrite = true) {
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
  }

  private suspend fun <R> setJournalMode(
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ) = withContext(isWrite = true) {
    connectionPool.setJournalMode { connection ->
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

  private suspend inline fun isActiveTransactionOwner() =
    coroutineContext[CoroutineName].let { name ->
      name != null && name == activeTransactionCoroutineName
    }

  private suspend inline fun <R> withContext(
    isWrite: Boolean,
    noinline block: suspend () -> R,
  ) = when {
    isActiveTransactionOwner() -> block()
    isWrite -> connectionPool.withWriteContext(block)
    else -> connectionPool.withReadContext(block)
  }

  private suspend inline fun <R> withConnection(
    isWrite: Boolean,
    block: SQLiteConnection.() -> R,
  ): R = when {
    isActiveTransactionOwner() -> {
      val currentTransaction = currentTransaction()
      require(currentTransaction is ConnectionHolder) {
        "Coroutine ${coroutineContext[CoroutineName]} owns the active transaction but it is not a connection holder."
      }
      currentTransaction.connection.block()
    }

    else -> {
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
            activeTransactionCoroutineName = null
            connectionPool.releaseWriterConnection()
          }
        }
        activeTransaction = enclosingTransaction
      }
  }
}

private fun SQLiteConnection.getTotalChangedRows() =
  prepare("SELECT changes()").use { statement ->
    if(statement.step()) statement.getLong(0) else 0
  }
