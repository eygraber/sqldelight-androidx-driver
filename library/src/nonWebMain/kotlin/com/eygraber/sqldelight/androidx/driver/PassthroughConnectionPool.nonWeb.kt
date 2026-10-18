package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

internal class PassthroughConnectionPool(
  private val connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  private val configuration: AndroidxSqliteConfiguration,
  private val onConnectionClosed: (SQLiteConnection) -> Unit = {},
  private val onClosed: () -> Unit = {},
) : ConnectionPool {
  private val name by lazy { nameProvider() }

  private val connectionMutex = Mutex()

  @Volatile
  private var delegatedConnection: SQLiteConnection? = null

  @Volatile
  private var isClosed = false

  // Acquires increment under exclusiveMutex before they check isClosed; close() and
  // withAllConnectionsClosed read the count after they set their flag, so one side always
  // observes the other. A locked connectionMutex implies a count > 0.
  private val outstanding = atomic(0)

  private val closeFinished = atomic(false)

  private val closed = CompletableDeferred<Unit>()

  private val exclusiveMutex = Mutex()

  @Volatile
  private var idle: CompletableDeferred<Unit>? = null

  private suspend fun delegatedConnection(): SQLiteConnection =
    delegatedConnection ?: connectionMutex.withLock {
      check(!isClosed) { POOL_CLOSED_MESSAGE }
      delegatedConnection ?: connectionFactory
        .createConnection(name)
        .withWriterConfiguration(configuration)
        .also { delegatedConnection = it }
    }

  private suspend fun acquire(): SQLiteConnection {
    exclusiveMutex.withLock {
      outstanding.incrementAndGet()
    }
    var handedOff = false
    try {
      check(!isClosed) { POOL_CLOSED_MESSAGE }
      val connection = delegatedConnection()
      handedOff = true
      return connection
    }
    finally {
      if(!handedOff) {
        withContext(NonCancellable) {
          release()
        }
      }
    }
  }

  private suspend fun release() {
    if(outstanding.decrementAndGet() == 0) {
      idle?.complete(Unit)
      if(isClosed) {
        connectionMutex.withLock {
          closeDelegatedLocked()
        }
        finishCloseIfReady()
      }
    }
  }

  private fun closeDelegatedLocked() {
    val connection = delegatedConnection ?: return
    delegatedConnection = null
    try {
      connection.close()
    }
    catch(_: Throwable) {}
    try {
      onConnectionClosed(connection)
    }
    catch(_: Throwable) {}
  }

  private fun finishCloseIfReady() {
    if(!isClosed || outstanding.value != 0) return
    if(!closeFinished.compareAndSet(expect = false, update = true)) return
    try {
      onClosed()
    }
    finally {
      closed.complete(Unit)
    }
  }

  override suspend fun <R> runOnDispatcher(block: suspend () -> R) = block()

  override suspend fun acquireWriterConnection() = acquire()

  override suspend fun releaseWriterConnection() {
    release()
  }

  override suspend fun acquireReaderConnection() = acquire()

  override suspend fun releaseReaderConnection(connection: SQLiteConnection) {
    release()
  }

  override suspend fun <R> setJournalMode(
    executeStatement: suspend (SQLiteConnection) -> R,
  ): R {
    val connection = acquire()
    try {
      val isForeignKeyConstraintsEnabled =
        connection.prepare("PRAGMA foreign_keys;").use { statement ->
          statement.step()
          statement.getBoolean(0)
        }

      val queryResult = executeStatement(connection)

      // PRAGMA journal_mode currently wipes out foreign_keys - https://issuetracker.google.com/issues/447613208
      val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
      connection.executeSQL("PRAGMA foreign_keys = $foreignKeys;")

      return queryResult
    }
    finally {
      withContext(NonCancellable) {
        release()
      }
    }
  }

  override suspend fun <R> withAllConnectionsClosed(block: suspend () -> R): R =
    exclusiveMutex.withLock {
      check(!isClosed) { POOL_CLOSED_MESSAGE }
      val waitForIdle = CompletableDeferred<Unit>()
      idle = waitForIdle
      try {
        if(outstanding.value != 0) waitForIdle.await()
        connectionMutex.withLock {
          closeDelegatedLocked()
        }
        block()
      }
      finally {
        idle = null
      }
    }

  override fun close() {
    if(isClosed) return
    isClosed = true
    if(outstanding.value == 0 && connectionMutex.tryLock()) {
      try {
        closeDelegatedLocked()
      }
      finally {
        connectionMutex.unlock()
      }
    }
    finishCloseIfReady()
  }

  override suspend fun awaitClosed() {
    closed.await()
  }
}
