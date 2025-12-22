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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal interface ConnectionHolder {
  val connection: SQLiteConnection
}

internal data class TransactionElement(
  val transaction: Transacter.Transaction,
  val transactionDispatcher: ContinuationInterceptor,
) : AbstractCoroutineContextElement(TransactionElement) {
  companion object Key : CoroutineContext.Key<TransactionElement>
}

internal class AndroidxSqliteExecutingDriver(
  private val connectionPool: ConnectionPool,
  private val isStatementCacheSkipped: Boolean,
  private val statementCache: MutableMap<SQLiteConnection, LruCache<Int, AndroidxStatement>>,
  private val statementCacheLock: ReentrantLock,
  private val statementCacheSize: Int,
) : SqlDriver, TransactionContextProvider {
  @Volatile
  private var activeTransaction: Transacter.Transaction? = null

  private val activeTransactionMutex = Mutex()

  override suspend fun <R> withTransactionContext(block: suspend () -> R): R {
    val connection = activeTransactionMutex.withLock {
      when(val enclosing = coroutineContext[TransactionElement]) {
        null -> connectionPool.acquireWriterConnection()
        else -> requireNotNull(enclosing.transaction as? ConnectionHolder) {
          "SqlDriver.newTransaction() must return an implementation of ConnectionHolder"
        }.connection
      }
    }

    // once we reach this point we know we for
    // sure have exclusive access to the write connection
    return when(val enclosing = coroutineContext[TransactionElement]) {
      null -> startTransactionCoroutine(
        writeConnection = connection,
        block = block,
      )

      else -> withContext(
        context = enclosing.copy(
          transaction = Transaction(
            enclosingTransaction = enclosing.transaction,
            connection = connection,
          ),
        ),
      ) {
        block()
      }
    }
  }

  private suspend inline fun <R> startTransactionCoroutine(
    writeConnection: SQLiteConnection,
    crossinline block: suspend () -> R,
  ): R = connectionPool.runOnDispatcher {
    val context = coroutineContext

    // borrow this trick from Room to "pin" a thread
    // on our dispatcher for the duration of the transaction
    // https://eygraber.short.gy/room-transaction-trick
    runBlocking(context.minusKey(ContinuationInterceptor)) {
      val dispatcher = requireNotNull(coroutineContext[ContinuationInterceptor]) {
        "Couldn't find a ContinuationInterceptor in the transaction's runBlocking context."
      }

      withContext(
        dispatcher +
          TransactionElement(
            transaction = Transaction(
              enclosingTransaction = null,
              connection = writeConnection,
            ).also {
              activeTransaction = it
            },
            transactionDispatcher = dispatcher,
          ),
      ) {
        block()
      }
    }
  }

  override fun newTransaction(): QueryResult<Transacter.Transaction> =
    QueryResult.AsyncValue {
      requireNotNull(
        coroutineContext[TransactionElement],
      ) {
        "No transaction found for the current coroutine. Was withTransactionContext called?"
      }.transaction
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

        connectionPool.runOnDispatcher {
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
      -> connectionPool.runOnDispatcher {
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
  ) = connectionPool.runOnDispatcher {
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

  private suspend inline fun <R> withConnection(
    isWrite: Boolean,
    block: SQLiteConnection.() -> R,
  ): R = when(val transaction = coroutineContext[TransactionElement]) {
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

    else -> {
      val currentTransaction = transaction.transaction
      require(currentTransaction is ConnectionHolder) {
        "Coroutine ${coroutineContext[CoroutineName]} owns the active transaction but it is not a connection holder."
      }
      currentTransaction.connection.block()
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
            activeTransaction = null
            connectionPool.releaseWriterConnection()
          }
        }
      }
  }
}

private fun SQLiteConnection.getTotalChangedRows() =
  prepare("SELECT changes()").use { statement ->
    if(statement.step()) statement.getLong(0) else 0
  }
