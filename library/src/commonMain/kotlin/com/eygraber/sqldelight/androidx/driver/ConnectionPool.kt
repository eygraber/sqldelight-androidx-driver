package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ConnectionPool(
  private val createConnection: (String) -> SQLiteConnection,
  private val name: String,
  private val isFileBased: Boolean,
  private val configuration: AndroidxSqliteConfiguration,
) {
  private val writerConnection: SQLiteConnection by lazy {
    createConnection(name).withConfiguration()
  }
  private val writerMutex = Mutex()

  private val maxReaderConnectionsCount = when {
    isFileBased -> configuration.readerConnectionsCount
    else -> 0
  }

  private val readerChannel = Channel<Lazy<SQLiteConnection>>(capacity = maxReaderConnectionsCount)

  init {
    repeat(maxReaderConnectionsCount) {
      readerChannel.trySend(
        lazy {
          createConnection(name).withConfiguration()
        },
      )
    }
  }

  /**
   * Acquires the writer connection, blocking if it's currently in use.
   * @return The writer SQLiteConnection
   */
  fun acquireWriterConnection() = runBlocking {
    writerMutex.lock()
    writerConnection
  }

  /**
   * Releases the writer connection (mutex unlocks automatically).
   */
  fun releaseWriterConnection() {
    writerMutex.unlock()
  }

  /**
   * Acquires a reader connection, blocking if none are available.
   * @return A reader SQLiteConnection
   */
  fun acquireReaderConnection() = when(maxReaderConnectionsCount) {
    0 -> acquireWriterConnection()
    else -> runBlocking {
      readerChannel.receive().value
    }
  }

  /**
   * Releases a reader connection back to the pool.
   * @param connection The SQLiteConnection to release
   */
  fun releaseReaderConnection(connection: SQLiteConnection) = when(maxReaderConnectionsCount) {
    0 -> releaseWriterConnection()
    else -> runBlocking {
      readerChannel.send(lazy { connection })
    }
  }

  fun setForeignKeyConstraintsEnabled(isForeignKeyConstraintsEnabled: Boolean) {
    configuration.isForeignKeyConstraintsEnabled = isForeignKeyConstraintsEnabled
    val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
    runPragmaOnAllConnections("PRAGMA foreign_keys = $foreignKeys;")
  }

  fun setJournalMode(journalMode: SqliteJournalMode) {
    configuration.journalMode = journalMode
    runPragmaOnAllConnections("PRAGMA journal_mode = ${configuration.journalMode.value};")
  }

  fun setSync(sync: SqliteSync) {
    configuration.sync = sync
    runPragmaOnAllConnections("PRAGMA synchronous = ${configuration.sync.value};")
  }

  private fun runPragmaOnAllConnections(sql: String) {
    val writer = acquireWriterConnection()
    try {
      writer.writePragma(sql)
    } finally {
      releaseWriterConnection()
    }

    if(maxReaderConnectionsCount > 0) {
      runBlocking {
        repeat(maxReaderConnectionsCount) {
          val reader = readerChannel.receive()
          try {
            reader.value.writePragma(sql)
          } finally {
            releaseReaderConnection(reader.value)
          }
        }
      }
    }
  }

  private fun SQLiteConnection.withConfiguration(): SQLiteConnection = this.apply {
    val foreignKeys = if(configuration.isForeignKeyConstraintsEnabled) "ON" else "OFF"
    writePragma("PRAGMA foreign_keys = $foreignKeys;")
    writePragma("PRAGMA journal_mode = ${configuration.journalMode.value};")
    writePragma("PRAGMA synchronous = ${configuration.sync.value};")
  }

  /**
   * Closes all connections in the pool.
   */
  fun close() {
    runBlocking {
      writerMutex.withLock {
        writerConnection.close()
      }
      repeat(maxReaderConnectionsCount) {
        val reader = readerChannel.receive()
        if(reader.isInitialized()) reader.value.close()
      }
      readerChannel.close()
    }
  }
}

private fun SQLiteConnection.writePragma(sql: String) {
  prepare(sql).use(SQLiteStatement::step)
}
