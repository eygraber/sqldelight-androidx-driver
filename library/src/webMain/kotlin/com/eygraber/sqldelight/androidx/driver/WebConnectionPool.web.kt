package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step

/**
 * Single-connection pool used on JS/wasmJs. Web targets are single-threaded, so the
 * Mutex/Channel/swap-fence machinery in [AndroidxDriverConnectionPool] would only be wasted
 * coordination — readers, writers, and dispatchers all serialize naturally.
 *
 * Both `createDefaultConnectionPool` and `createPassthroughConnectionPool` resolve to this
 * implementation on web.
 */
internal class WebConnectionPool(
  private val connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  private val configuration: AndroidxSqliteConfiguration,
) : ConnectionPool {
  private val name by lazy { nameProvider() }

  private var connection: SQLiteConnection? = null

  private suspend fun acquire(): SQLiteConnection =
    connection ?: connectionFactory.createConnection(name).also {
      try {
        configuration.apply {
          it.executeSQL("PRAGMA journal_mode = ${journalMode.value};")
          it.executeSQL("PRAGMA synchronous = ${sync.value};")
          val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
          it.executeSQL("PRAGMA foreign_keys = $foreignKeys;")
        }
      }
      catch(t: Throwable) {
        try {
          it.close()
        }
        catch(closeFailure: Throwable) {
          t.addSuppressed(closeFailure)
        }
        throw t
      }
      connection = it
    }

  override suspend fun <R> runOnDispatcher(block: suspend () -> R): R = block()

  override suspend fun acquireWriterConnection(): SQLiteConnection = acquire()

  override suspend fun releaseWriterConnection() {}

  override suspend fun acquireReaderConnection(): SQLiteConnection = acquire()

  override suspend fun releaseReaderConnection(connection: SQLiteConnection) {}

  override suspend fun <R> setJournalMode(
    executeStatement: suspend (SQLiteConnection) -> R,
  ): R {
    val c = acquire()
    val isForeignKeyConstraintsEnabled =
      c.prepare("PRAGMA foreign_keys;").use { statement ->
        statement.step()
        statement.getBoolean(0)
      }

    val queryResult = executeStatement(c)

    // PRAGMA journal_mode currently wipes out foreign_keys - https://issuetracker.google.com/issues/447613208
    val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
    c.executeSQL("PRAGMA foreign_keys = $foreignKeys;")

    return queryResult
  }

  override fun close() {
    connection?.close()
    connection = null
  }
}
