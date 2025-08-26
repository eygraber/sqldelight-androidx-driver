package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public interface ConnectionPool : AutoCloseable {
  public fun acquireWriterConnection(): SQLiteConnection
  public fun releaseWriterConnection()
  public fun acquireReaderConnection(): SQLiteConnection
  public fun releaseReaderConnection(connection: SQLiteConnection)
  public fun setForeignKeyConstraintsEnabled(isForeignKeyConstraintsEnabled: Boolean)
  public fun setJournalMode(journalMode: SqliteJournalMode)
  public fun setSync(sync: SqliteSync)
}

internal class AndroidxDriverConnectionPool(
  private val createConnection: (String) -> SQLiteConnection,
  nameProvider: () -> String,
  private val isFileBased: Boolean,
  private val configuration: AndroidxSqliteConfiguration,
) : ConnectionPool {
  private val name by lazy { nameProvider() }

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
  override fun acquireReaderConnection() = when(maxReaderConnectionsCount) {
    0 -> acquireWriterConnection()
    else -> runBlocking {
      readerChannel.receive().value
    }
  }

  /**
   * Releases a reader connection back to the pool.
   * @param connection The SQLiteConnection to release
   */
  override fun releaseReaderConnection(connection: SQLiteConnection) {
    when(maxReaderConnectionsCount) {
      0 -> releaseWriterConnection()
      else -> runBlocking {
        readerChannel.send(lazy { connection })
      }
    }
  }

  override fun setForeignKeyConstraintsEnabled(isForeignKeyConstraintsEnabled: Boolean) {
    configuration.isForeignKeyConstraintsEnabled = isForeignKeyConstraintsEnabled
    val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
    runPragmaOnAllConnections("PRAGMA foreign_keys = $foreignKeys;")
  }

  override fun setJournalMode(journalMode: SqliteJournalMode) {
    configuration.journalMode = journalMode
    runPragmaOnAllConnections("PRAGMA journal_mode = ${configuration.journalMode.value};")
  }

  override fun setSync(sync: SqliteSync) {
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
  override fun close() {
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
