package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReaders
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.SingleReaderWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

internal class AndroidxDriverConnectionPool(
  connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  private val isFileBased: Boolean,
  private val configuration: AndroidxSqliteConfiguration,
  onConnectionClosed: (SQLiteConnection) -> Unit = {},
) : ConnectionPool {
  private val name by lazy { nameProvider() }

  // The suspend versions of `createConnection` / `executeSQL` exist for cross-platform parity
  // with web; on non-web they don't actually suspend, so wrapping in runBlocking inside the
  // lazy initializer is purely a type adapter.
  private val lazyWriterConnection = lazy {
    runBlocking {
      connectionFactory
        .createConnection(name)
        .withWriterConfiguration(configuration)
    }
  }
  private val writerConnection: SQLiteConnection get() = lazyWriterConnection.value

  private val writerMutex = Mutex()

  @Volatile
  private var concurrencyModel: AndroidxSqliteConcurrencyModel

  init {
    val (resolved, orphan) = when {
      isFileBased -> configuration.concurrencyModel to null
      else -> when(val model = configuration.concurrencyModel) {
        is SingleReaderWriter -> model to null
        is MultipleReaders -> SingleReaderWriter(
          dispatcherProvider = model.dispatcherProvider,
        ) to model

        is MultipleReadersSingleWriter -> SingleReaderWriter(
          dispatcherProvider = model.dispatcherProvider,
        ) to model
      }
    }
    concurrencyModel = resolved

    // the dispatcher is lazily created,
    // so this will likely be a no-op in most cases
    orphan?.close()
  }

  // Readers intentionally skip withWriterConfiguration:
  //   - journal_mode = WAL is persisted in the db file header, so readers opened after the
  //     writer switches the db to WAL inherit it automatically
  //   - synchronous and foreign_keys only affect write paths, and all writes route through the
  //     writer connection
  // If a future PRAGMA is added that matters for reads (cache_size, temp_store, etc.), it
  // needs to be applied here too.
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

  override suspend fun releaseWriterConnection() {
    writerMutex.unlock()
  }

  override suspend fun acquireReaderConnection(): SQLiteConnection {
    while(true) {
      if(concurrencyModel.readerCount == 0) return acquireWriterConnection()
      // acquire() returns null when capacity dropped to 0 mid-wait (e.g. WAL → non-WAL swap).
      // Loop so we re-check concurrencyModel and route to the writer.
      val reader = readerPool.acquire(
        onEmpty = {
          withTimeoutOrNull(50) { acquireWriterConnection() }
        },
      )
      if(reader != null) return reader
    }
  }

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
      writer.executeSQL("PRAGMA foreign_keys = $foreignKeys;")

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
          catch(_: Throwable) {
          }
        }
      }
    }
  }

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
    }
  }
}

private suspend fun SQLiteConnection.withWriterConfiguration(
  configuration: AndroidxSqliteConfiguration,
): SQLiteConnection {
  try {
    configuration.apply {
      executeSQL("PRAGMA journal_mode = ${journalMode.value};")
      executeSQL("PRAGMA synchronous = ${sync.value};")

      // this must come after PRAGMA journal_mode while https://issuetracker.google.com/issues/447613208 is broken
      val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
      executeSQL("PRAGMA foreign_keys = $foreignKeys;")
    }
  }
  catch(c: CancellationException) {
    throw c
  }
  catch(t: Throwable) {
    try {
      close()
    }
    catch(closeFailure: Throwable) {
      t.addSuppressed(closeFailure)
    }
    throw t
  }
  return this
}
