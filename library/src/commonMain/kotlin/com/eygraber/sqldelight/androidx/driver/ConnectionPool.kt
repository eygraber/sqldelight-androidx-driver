package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlinx.atomicfu.atomic
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
  isFileBased: Boolean,
  configuration: AndroidxSqliteConfiguration,
) : ConnectionPool {
  private data class ReaderSQLiteConnection(
    val isCreated: Boolean,
    val connection: Lazy<SQLiteConnection>,
  )

  private var configuration by atomic(configuration)

  private val name by lazy { nameProvider() }

  private val writerConnection: SQLiteConnection by lazy {
    createConnection(name).withConfiguration(configuration)
  }

  private val writerMutex = Mutex()

  private val maxReaderConnectionsCount = when {
    isFileBased -> configuration.readerConnectionsCount
    else -> 0
  }

  private val readerChannel = Channel<ReaderSQLiteConnection>(capacity = maxReaderConnectionsCount)

  init {
    repeat(maxReaderConnectionsCount) {
      readerChannel.trySend(
        ReaderSQLiteConnection(
          isCreated = false,
          lazy {
            createConnection(name).withConfiguration(configuration)
          },
        ),
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
      readerChannel.receive().connection.value
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
        readerChannel.send(
          ReaderSQLiteConnection(
            isCreated = true,
            lazy { connection },
          ),
        )
      }
    }
  }

  override fun setForeignKeyConstraintsEnabled(isForeignKeyConstraintsEnabled: Boolean) {
    configuration = configuration.copy(
      isForeignKeyConstraintsEnabled = isForeignKeyConstraintsEnabled,
    )

    val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
    runPragmaOnAllConnections("PRAGMA foreign_keys = $foreignKeys;")
  }

  override fun setJournalMode(journalMode: SqliteJournalMode) {
    configuration = configuration.copy(
      journalMode = journalMode,
    )

    runPragmaOnAllConnections("PRAGMA journal_mode = ${configuration.journalMode.value};")
  }

  override fun setSync(sync: SqliteSync) {
    configuration = configuration.copy(
      sync = sync,
    )

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
            // only apply the pragma to connections that were already created
            if(reader.isCreated) {
              reader.connection.value.writePragma(sql)
            }
          } finally {
            readerChannel.send(reader)
          }
        }
      }
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
      repeat(maxReaderConnectionsCount) {
        val reader = readerChannel.receive()
        if(reader.isCreated) reader.connection.value.close()
      }
      readerChannel.close()
    }
  }
}

internal class PassthroughConnectionPool(
  private val createConnection: (String) -> SQLiteConnection,
  nameProvider: () -> String,
  configuration: AndroidxSqliteConfiguration,
) : ConnectionPool {
  private var configuration by atomic(configuration)

  private val name by lazy { nameProvider() }

  private val delegatedConnection: SQLiteConnection by lazy {
    createConnection(name).withConfiguration(configuration)
  }

  override fun acquireWriterConnection() = delegatedConnection

  override fun releaseWriterConnection() {}

  override fun acquireReaderConnection() = delegatedConnection

  override fun releaseReaderConnection(connection: SQLiteConnection) {}

  override fun setForeignKeyConstraintsEnabled(isForeignKeyConstraintsEnabled: Boolean) {
    configuration = configuration.copy(
      isForeignKeyConstraintsEnabled = isForeignKeyConstraintsEnabled,
    )

    val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
    delegatedConnection.writePragma("PRAGMA foreign_keys = $foreignKeys;")
  }

  override fun setJournalMode(journalMode: SqliteJournalMode) {
    configuration = configuration.copy(
      journalMode = journalMode,
    )

    delegatedConnection.writePragma("PRAGMA journal_mode = ${configuration.journalMode.value};")
  }

  override fun setSync(sync: SqliteSync) {
    configuration = configuration.copy(
      sync = sync,
    )

    delegatedConnection.writePragma("PRAGMA synchronous = ${configuration.sync.value};")
  }

  override fun close() {
    delegatedConnection.close()
  }
}

private fun SQLiteConnection.withConfiguration(
  configuration: AndroidxSqliteConfiguration,
): SQLiteConnection = this.apply {
  // copy the configuration for thread safety
  configuration.copy().apply {
    val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
    writePragma("PRAGMA foreign_keys = $foreignKeys;")
    writePragma("PRAGMA journal_mode = ${journalMode.value};")
    writePragma("PRAGMA synchronous = ${sync.value};")
  }
}

private fun SQLiteConnection.writePragma(sql: String) {
  prepare(sql).use(SQLiteStatement::step)
}
