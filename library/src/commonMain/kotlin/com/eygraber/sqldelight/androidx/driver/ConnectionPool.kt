package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.cash.sqldelight.db.QueryResult
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.SingleReaderWriter
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

internal interface ConnectionPool : AutoCloseable {
  fun acquireWriterConnection(): SQLiteConnection
  fun releaseWriterConnection()
  fun acquireReaderConnection(): SQLiteConnection
  fun releaseReaderConnection(connection: SQLiteConnection)
  fun <R> setJournalMode(
    executeStatement: (SQLiteConnection) -> QueryResult.Value<R>,
  ): QueryResult.Value<R>
}

internal inline fun <R> ConnectionPool.withWriterConnection(
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

  private val journalModeLock = ReentrantLock()

  @Volatile
  private var concurrencyModel = when {
    isFileBased -> configuration.concurrencyModel
    else -> SingleReaderWriter
  }

  private val readerChannel = Channel<ReaderSQLiteConnection>(capacity = Channel.UNLIMITED)

  init {
    populateReaderConnectionChannel()
  }

  /**
   * Acquires the writer connection, blocking if it's currently in use.
   * @return The writer SQLiteConnection
   */
  override fun acquireWriterConnection() = runBlocking {
    writerMutex.lock()
    writerConnection
  }

  /**
   * Releases the writer connection (mutex unlocks automatically).
   */
  override fun releaseWriterConnection() {
    writerMutex.unlock()
  }

  /**
   * Acquires a reader connection, blocking if none are available.
   * @return A reader SQLiteConnection
   */
  override fun acquireReaderConnection() = journalModeLock.withLock {
    when(concurrencyModel.readerCount) {
      0 -> acquireWriterConnection()
      else -> runBlocking {
        readerChannel.receive().connection.value
      }
    }
  }

  /**
   * Releases a reader connection back to the pool.
   * @param connection The SQLiteConnection to release
   */
  override fun releaseReaderConnection(connection: SQLiteConnection) {
    when(concurrencyModel.readerCount) {
      0 -> releaseWriterConnection()
      else -> runBlocking {
        readerChannel.send(
          ReaderSQLiteConnection(
            isCreated = true,
            lazy { connection },
          ),
        )
      }
    }
  }

  override fun <R> setJournalMode(
    executeStatement: (SQLiteConnection) -> QueryResult.Value<R>,
  ): QueryResult.Value<R> = journalModeLock.withLock {
    closeAllReaderConnections()

    val writer = acquireWriterConnection()
    try {
      // really hope the result is a String...
      val queryResult = executeStatement(writer)
      val result = queryResult.value.toString()

      (concurrencyModel as? MultipleReadersSingleWriter)?.let { previousModel ->
        val isWal = result.equals("wal", ignoreCase = true)
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

      repeat(concurrencyModel.readerCount) {
        val reader = readerChannel.receive()
        if(reader.isCreated) reader.connection.value.close()
      }
      readerChannel.close()
    }
  }

  private fun closeAllReaderConnections() {
    val readerCount = concurrencyModel.readerCount
    if(readerCount > 0) {
      runBlocking {
        repeat(readerCount) {
          val reader = readerChannel.receive()
          try {
            // only apply the pragma to connections that were already created
            if(reader.isCreated) {
              reader.connection.value.close()
            }
          } catch(_: Throwable) {
          }
        }
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
  configuration: AndroidxSqliteConfiguration,
) : ConnectionPool {
  private val name by lazy { nameProvider() }

  private val delegatedConnection: SQLiteConnection by lazy {
    connectionFactory.createConnection(name).withWriterConfiguration(configuration)
  }

  override fun acquireWriterConnection() = delegatedConnection

  override fun releaseWriterConnection() {}

  override fun acquireReaderConnection() = delegatedConnection

  override fun releaseReaderConnection(connection: SQLiteConnection) {}

  override fun <R> setJournalMode(
    executeStatement: (SQLiteConnection) -> QueryResult.Value<R>,
  ): QueryResult.Value<R> {
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
