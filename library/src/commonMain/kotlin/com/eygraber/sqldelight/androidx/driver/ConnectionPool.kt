package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReaders
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.SingleReaderWriter
import kotlinx.coroutines.channels.Channel
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
  } finally {
    releaseWriterConnection()
  }
}

internal class AndroidxDriverConnectionPool(
  private val connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  private val isFileBased: Boolean,
  private val configuration: AndroidxSqliteConfiguration,
) : ConnectionPool {
  private data class ReaderSQLiteConnection(
    val isCreated: Boolean,
    val connection: Lazy<SQLiteConnection>,
  )

  private val name by lazy { nameProvider() }

  private val writerConnection: SQLiteConnection by lazy {
    connectionFactory
      .createConnection(name)
      .withWriterConfiguration(configuration)
  }

  private val writerMutex = Mutex()
  private val journalModeMutex = Mutex()

  @Volatile
  private var concurrencyModel = when {
    isFileBased -> configuration.concurrencyModel
    else -> when(val model = configuration.concurrencyModel) {
      is SingleReaderWriter -> model
      is MultipleReaders -> model
      is MultipleReadersSingleWriter -> SingleReaderWriter(
        dispatcherProvider = model.dispatcherProvider,
      )
    }
  }

  private val readerChannel = Channel<ReaderSQLiteConnection>(capacity = Channel.UNLIMITED)

  init {
    populateReaderConnectionChannel()
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
    return writerConnection
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
  override suspend fun acquireReaderConnection() =
    when(concurrencyModel.readerCount) {
      0 -> acquireWriterConnection()
      else -> journalModeMutex.withLock {
        readerChannel.tryReceive().getOrNull()?.connection?.value ?: run {
          withTimeoutOrNull(50) {
            acquireWriterConnection()
          } ?: readerChannel.receive().connection.value
        }
      }
    }

  /**
   * Releases a reader connection back to the pool.
   * @param connection The SQLiteConnection to release
   */
  override suspend fun releaseReaderConnection(connection: SQLiteConnection) {
    when(concurrencyModel.readerCount) {
      0 -> releaseWriterConnection()
      else -> when(connection) {
        writerConnection -> releaseWriterConnection()
        else -> readerChannel.send(
          ReaderSQLiteConnection(
            isCreated = true,
            lazy { connection },
          ),
        )
      }
    }
  }

  override suspend fun <R> setJournalMode(
    executeStatement: suspend (SQLiteConnection) -> R,
  ): R = journalModeMutex.withLock {
    closeAllReaderConnections()

    val writer = acquireWriterConnection()
    try {
      // really hope the result is a String...
      val queryResult = executeStatement(writer)
      val result = queryResult?.toString()

      (concurrencyModel as? MultipleReadersSingleWriter)?.let { previousModel ->
        val isWal = result.equals("wal", ignoreCase = true)
        concurrencyModel.close()
        concurrencyModel = previousModel.copy(isWal = isWal)
      }

      return queryResult
    } finally {
      populateReaderConnectionChannel()
      releaseWriterConnection()
    }
  }

  /**
   * Closes all connections in the pool.
   */
  override fun close() {
    runBlocking {
      writerMutex.withLock {
        writerConnection.close()
      }

      journalModeMutex.withLock {
        repeat(concurrencyModel.readerCount) {
          val reader = readerChannel.receive()
          if(reader.isCreated) reader.connection.value.close()
        }
      }
      readerChannel.close()
    }

    concurrencyModel.close()
  }

  private suspend fun closeAllReaderConnections() {
    repeat(concurrencyModel.readerCount) {
      val reader = readerChannel.receive()
      try {
        // only close connections that were already created
        if(reader.isCreated) {
          reader.connection.value.close()
        }
      } catch(_: Throwable) {
      }
    }
  }

  private fun populateReaderConnectionChannel() {
    repeat(concurrencyModel.readerCount) {
      readerChannel.trySend(
        ReaderSQLiteConnection(
          isCreated = false,
          connection = lazy {
            connectionFactory.createConnection(name)
          },
        ),
      )
    }
  }
}

internal class PassthroughConnectionPool(
  private val connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  private val configuration: AndroidxSqliteConfiguration,
) : ConnectionPool {
  private val name by lazy { nameProvider() }

  private val delegatedConnection: SQLiteConnection by lazy {
    connectionFactory.createConnection(name).withWriterConfiguration(configuration)
  }

  override suspend fun <R> runOnDispatcher(block: suspend () -> R) = block()

  override suspend fun acquireWriterConnection() = delegatedConnection

  override suspend fun releaseWriterConnection() {}

  override suspend fun acquireReaderConnection() = delegatedConnection

  override suspend fun releaseReaderConnection(connection: SQLiteConnection) {}

  override suspend fun <R> setJournalMode(
    executeStatement: suspend (SQLiteConnection) -> R,
  ): R {
    val isForeignKeyConstraintsEnabled =
      delegatedConnection
        .prepare("PRAGMA foreign_keys;")
        .apply { step() }
        .getBoolean(0)

    val queryResult = executeStatement(delegatedConnection)

    // PRAGMA journal_mode currently wipes out foreign_keys - https://issuetracker.google.com/issues/447613208
    val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
    delegatedConnection.execSQL("PRAGMA foreign_keys = $foreignKeys;")

    return queryResult
  }

  override fun close() {
    delegatedConnection.close()
  }
}

private fun SQLiteConnection.withWriterConfiguration(
  configuration: AndroidxSqliteConfiguration,
): SQLiteConnection = this.apply {
  // copy the configuration for thread safety
  configuration.copy().apply {
    execSQL("PRAGMA journal_mode = ${journalMode.value};")
    execSQL("PRAGMA synchronous = ${sync.value};")

    // this must to come after PRAGMA journal_mode while https://issuetracker.google.com/issues/447613208 is broken
    val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
    execSQL("PRAGMA foreign_keys = $foreignKeys;")
  }
}
