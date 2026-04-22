package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReaders
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.SingleReaderWriter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

internal interface ConnectionPool : AutoCloseable {
  suspend fun <R> runOnDispatcher(block: suspend () -> R): R

  suspend fun acquireWriterConnection(): SQLiteConnection
  suspend fun releaseWriterConnection()
  suspend fun acquireReaderConnection(): SQLiteConnection
  suspend fun releaseReaderConnection(connection: SQLiteConnection)
  suspend fun <R> setJournalMode(
    executeStatement: suspend (SQLiteConnection) -> R,
  ): R
}

internal suspend inline fun <R> ConnectionPool.withWriterConnection(
  block: SQLiteConnection.() -> R,
): R {
  val connection = acquireWriterConnection()
  try {
    return connection.block()
  }
  finally {
    withContext(NonCancellable) {
      releaseWriterConnection()
    }
  }
}

internal class AndroidxDriverConnectionPool(
  connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  private val isFileBased: Boolean,
  private val configuration: AndroidxSqliteConfiguration,
  onConnectionClosed: (SQLiteConnection) -> Unit = {},
) : ConnectionPool {
  private val name by lazy { nameProvider() }

  private val lazyWriterConnection = lazy {
    connectionFactory
      .createConnection(name)
      .withWriterConfiguration(configuration)
  }
  private val writerConnection: SQLiteConnection get() = lazyWriterConnection.value

  private val writerMutex = Mutex()

  // For ephemeral databases, MultipleReadersSingleWriter is replaced with a SingleReaderWriter
  // because each connection to an in-memory or temporary database sees its own private database.
  // The replaced model's dispatcher must still be closed when the pool closes, otherwise a
  // CpuCacheHitOptimizedProvider-backed thread pool would leak.
  private val orphanedConcurrencyModel: AndroidxSqliteConcurrencyModel?

  @Volatile
  private var concurrencyModel: AndroidxSqliteConcurrencyModel

  init {
    val (resolved, orphan) = when {
      isFileBased -> configuration.concurrencyModel to null
      else -> when(val model = configuration.concurrencyModel) {
        is SingleReaderWriter -> model to null
        is MultipleReaders -> model to null
        is MultipleReadersSingleWriter -> SingleReaderWriter(
          dispatcherProvider = model.dispatcherProvider,
        ) to model
      }
    }
    concurrencyModel = resolved
    orphanedConcurrencyModel = orphan
  }

  private val readerPool = ReaderPool(
    connectionFactory = connectionFactory,
    name = { name },
    onConnectionClosed = onConnectionClosed,
  )

  init {
    readerPool.populate(concurrencyModel.readerCount)
  }

  override suspend fun <R> runOnDispatcher(block: suspend () -> R) =
    when(currentCoroutineContext()[TransactionElement]) {
      null -> withContext(concurrencyModel.dispatcher) {
        block()
      }

      else -> block()
    }

  /**
   * Acquires the writer connection, suspending if it's currently in use.
   * @return The writer SQLiteConnection
   */
  override suspend fun acquireWriterConnection(): SQLiteConnection {
    writerMutex.lock()
    return try {
      writerConnection
    }
    catch(t: Throwable) {
      // If the lazy writer connection's initializer throws (e.g. failed to open the db or run
      // writer PRAGMAs), we must release the mutex so future acquires aren't blocked forever.
      writerMutex.unlock()
      throw t
    }
  }

  /**
   * Releases the writer connection (mutex unlocks automatically).
   */
  override suspend fun releaseWriterConnection() {
    writerMutex.unlock()
  }

  /**
   * Acquires a reader connection, suspending if none are available.
   * @return A reader SQLiteConnection
   */
  override suspend fun acquireReaderConnection(): SQLiteConnection =
    when(concurrencyModel.readerCount) {
      0 -> acquireWriterConnection()
      else -> readerPool.acquire(
        onEmpty = {
          withTimeoutOrNull(50) { acquireWriterConnection() }
        },
      )
    }

  /**
   * Releases a reader connection back to the pool.
   * @param connection The SQLiteConnection to release
   */
  override suspend fun releaseReaderConnection(connection: SQLiteConnection) {
    when(concurrencyModel.readerCount) {
      0 -> releaseWriterConnection()
      // The writer is only a possible reader if the lazy was already materialized; reading
      // .value here would otherwise force-init the writer (open + writer PRAGMAs) just to do
      // a reference comparison against a connection we know came from the reader pool.
      else -> when {
        lazyWriterConnection.isInitialized() && connection === writerConnection ->
          releaseWriterConnection()
        else -> readerPool.release(connection)
      }
    }
  }

  override suspend fun <R> setJournalMode(
    executeStatement: suspend (SQLiteConnection) -> R,
  ): R = readerPool.withSwap(
    newCapacityAfter = { concurrencyModel.readerCount },
  ) {
    val writer = acquireWriterConnection()
    val previousConcurrencyModel = concurrencyModel as? MultipleReadersSingleWriter
    var isConcurrencyModelReplaced = false

    try {
      val isForeignKeyConstraintsEnabled =
        writer.prepare("PRAGMA foreign_keys;").use { statement ->
          statement.step()
          statement.getBoolean(0)
        }

      val queryResult = executeStatement(writer)

      // PRAGMA journal_mode currently wipes out foreign_keys - https://issuetracker.google.com/issues/447613208
      val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
      writer.execSQL("PRAGMA foreign_keys = $foreignKeys;")

      if(previousConcurrencyModel != null) {
        val result = when(queryResult) {
          null -> null
          is String -> queryResult
          else -> error(
            """
            PRAGMA journal_mode is intercepted by AndroidxSqliteDriver to keep its connection pool
            in sync with the database's journal mode, which requires the query result to be a String.
            Got ${queryResult::class.simpleName ?: "<type unknown>"} instead. Either remove the custom
            column adapter from this query, or set the journal mode via
            AndroidxSqliteConfigurableDriver.setJournalMode in onConfigure.
            """.trimIndent(),
          )
        }
        val isWal = result.equals("wal", ignoreCase = true)
        if(isWal != previousConcurrencyModel.isWal) {
          concurrencyModel = previousConcurrencyModel.copy(isWal = isWal)
          isConcurrencyModelReplaced = true
        }
      }

      queryResult
    }
    finally {
      withContext(NonCancellable) {
        releaseWriterConnection()
        if(isConcurrencyModelReplaced) {
          try {
            previousConcurrencyModel?.close()
          }
          catch(_: Throwable) {}
        }
      }
    }
  }

  /**
   * Closes all connections in the pool.
   */
  override fun close() {
    try {
      runBlocking {
        writerMutex.withLock {
          if(lazyWriterConnection.isInitialized()) {
            writerConnection.close()
          }
        }

        val priorCapacity = readerPool.currentCapacity
        val drained = readerPool.drainAndClose()
        val outstanding = priorCapacity - drained
        check(outstanding == 0) {
          "AndroidxDriverConnectionPool.close() called while $outstanding reader connection(s) still checked out"
        }
      }
    }
    finally {
      concurrencyModel.close()
      orphanedConcurrencyModel?.close()
    }
  }
}

internal class PassthroughConnectionPool(
  private val connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  private val configuration: AndroidxSqliteConfiguration,
) : ConnectionPool {
  private val name by lazy { nameProvider() }

  private val lazyDelegatedConnection = lazy {
    connectionFactory.createConnection(name).withWriterConfiguration(configuration)
  }
  private val delegatedConnection: SQLiteConnection get() = lazyDelegatedConnection.value

  override suspend fun <R> runOnDispatcher(block: suspend () -> R) = block()

  override suspend fun acquireWriterConnection() = delegatedConnection

  override suspend fun releaseWriterConnection() {}

  override suspend fun acquireReaderConnection() = delegatedConnection

  override suspend fun releaseReaderConnection(connection: SQLiteConnection) {}

  override suspend fun <R> setJournalMode(
    executeStatement: suspend (SQLiteConnection) -> R,
  ): R {
    val isForeignKeyConstraintsEnabled =
      delegatedConnection.prepare("PRAGMA foreign_keys;").use { statement ->
        statement.step()
        statement.getBoolean(0)
      }

    val queryResult = executeStatement(delegatedConnection)

    // PRAGMA journal_mode currently wipes out foreign_keys - https://issuetracker.google.com/issues/447613208
    val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
    delegatedConnection.execSQL("PRAGMA foreign_keys = $foreignKeys;")

    return queryResult
  }

  override fun close() {
    if(lazyDelegatedConnection.isInitialized()) {
      delegatedConnection.close()
    }
  }
}

private fun SQLiteConnection.withWriterConfiguration(
  configuration: AndroidxSqliteConfiguration,
): SQLiteConnection = this.apply {
  // copy the configuration for thread safety
  configuration.copy().apply {
    execSQL("PRAGMA journal_mode = ${journalMode.value};")
    execSQL("PRAGMA synchronous = ${sync.value};")

    // this must come after PRAGMA journal_mode while https://issuetracker.google.com/issues/447613208 is broken
    val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
    execSQL("PRAGMA foreign_keys = $foreignKeys;")
  }
}
