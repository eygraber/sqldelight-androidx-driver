package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-connection pool used on JS/wasmJs. Web targets are single-threaded, but not
 * interleaving-free: every statement suspends across a web worker round trip, so two coroutines
 * can interleave at any suspension point. All connection use is serialized through
 * [connectionMutex] — otherwise concurrent transactions would both issue BEGIN on the single
 * shared connection ("cannot start a transaction within a transaction"), and statements outside
 * a transaction could run inside someone else's open transaction.
 *
 * Readers share the writer's lock because there is only one connection — this mirrors the
 * `readerCount == 0` behavior of [AndroidxDriverConnectionPool].
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

  private val connectionMutex = Mutex()

  private var connection: SQLiteConnection? = null

  // Must only be called while holding connectionMutex — createConnection and the PRAGMAs
  // suspend, so an unguarded null-check would let two coroutines create two connections.
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

  override suspend fun acquireWriterConnection(): SQLiteConnection {
    connectionMutex.lock()
    return try {
      acquire()
    }
    catch(t: Throwable) {
      // If opening the connection or its PRAGMAs fail, release the mutex so future
      // acquires aren't blocked forever.
      connectionMutex.unlock()
      throw t
    }
  }

  override suspend fun releaseWriterConnection() {
    connectionMutex.unlock()
  }

  override suspend fun acquireReaderConnection(): SQLiteConnection = acquireWriterConnection()

  override suspend fun releaseReaderConnection(connection: SQLiteConnection) {
    connectionMutex.unlock()
  }

  override suspend fun <R> setJournalMode(
    executeStatement: suspend (SQLiteConnection) -> R,
  ): R = connectionMutex.withLock {
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

    queryResult
  }

  override fun close() {
    connection?.close()
    connection = null
  }
}
