package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import kotlinx.coroutines.CompletableDeferred
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
 * Every [AndroidxSqliteConcurrencyModel] resolves to one connection here, in the same way that
 * non-file databases resolve to [AndroidxSqliteConcurrencyModel.SingleReaderWriter] on other
 * targets. The pool does not use the model's reader count or its dispatcher.
 *
 * Both `createDefaultConnectionPool` and `createPassthroughConnectionPool` resolve to this
 * implementation on web.
 */
internal class WebConnectionPool(
  private val connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  private val configuration: AndroidxSqliteConfiguration,
  private val onConnectionClosed: (SQLiteConnection) -> Unit = {},
  private val onClosed: () -> Unit = {},
) : ConnectionPool {
  private val name by lazy { nameProvider() }

  private val connectionMutex = Mutex()

  private var connection: SQLiteConnection? = null

  private var isClosed = false

  private var closeFinished = false

  private val closed = CompletableDeferred<Unit>()

  // Must only be called while holding connectionMutex — createConnection and the PRAGMAs
  // suspend, so an unguarded null-check would let two coroutines create two connections.
  private suspend fun acquire(): SQLiteConnection {
    if(isClosed) {
      closeConnectionLocked()
      error(POOL_CLOSED_MESSAGE)
    }
    return connection ?: connectionFactory
      .createConnection(name)
      .withWriterConfiguration(configuration)
      .also { connection = it }
  }

  private fun closeConnectionLocked() {
    val c = connection
    if(c != null) {
      connection = null
      try {
        c.close()
      }
      catch(_: Throwable) {}
      try {
        onConnectionClosed(c)
      }
      catch(_: Throwable) {}
    }
    if(isClosed && !closeFinished) {
      closeFinished = true
      try {
        onClosed()
      }
      finally {
        closed.complete(Unit)
      }
    }
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
    try {
      if(isClosed) closeConnectionLocked()
    }
    finally {
      connectionMutex.unlock()
    }
  }

  override suspend fun acquireReaderConnection(): SQLiteConnection = acquireWriterConnection()

  override suspend fun releaseReaderConnection(connection: SQLiteConnection) {
    releaseWriterConnection()
  }

  override suspend fun <R> setJournalMode(
    executeStatement: suspend (SQLiteConnection) -> R,
  ): R = connectionMutex.withLock {
    try {
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
    finally {
      if(isClosed) closeConnectionLocked()
    }
  }

  override suspend fun <R> withAllConnectionsClosed(block: suspend () -> R): R =
    connectionMutex.withLock {
      check(!isClosed) { POOL_CLOSED_MESSAGE }
      closeConnectionLocked()
      block()
    }

  override fun close() {
    if(isClosed) return
    isClosed = true
    if(connectionMutex.tryLock()) {
      try {
        closeConnectionLocked()
      }
      finally {
        connectionMutex.unlock()
      }
    }
  }

  override suspend fun awaitClosed() {
    closed.await()
  }
}
