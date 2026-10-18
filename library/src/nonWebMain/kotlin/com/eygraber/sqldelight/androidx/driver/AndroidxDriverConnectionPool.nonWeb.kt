package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReaders
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.SingleReaderWriter
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

internal class AndroidxDriverConnectionPool(
  private val connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  private val isFileBased: Boolean,
  private val configuration: AndroidxSqliteConfiguration,
  private val onConnectionClosed: (SQLiteConnection) -> Unit = {},
  private val onClosed: () -> Unit = {},
) : ConnectionPool {
  private val name by lazy { nameProvider() }

  private val writerMutex = Mutex()

  @Volatile
  private var writerConnection: SQLiteConnection? = null

  @Volatile
  private var isClosed = false

  // Every acquire increments before it checks isClosed, and close() sets isClosed before it reads
  // the count, so one of them always observes the other. A locked writerMutex implies a count > 0.
  private val outstanding = atomic(0)

  private val closeFinished = atomic(false)

  private val closed = CompletableDeferred<Unit>()

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

  private suspend fun writer(): SQLiteConnection =
    writerConnection ?: connectionFactory
      .createConnection(name)
      .withWriterConfiguration(configuration)
      .also { writerConnection = it }

  override suspend fun <R> runOnDispatcher(block: suspend () -> R) =
    when(currentCoroutineContext()[TransactionElement]) {
      null -> {
        check(!isClosed) { POOL_CLOSED_MESSAGE }
        withContext(concurrencyModel.dispatcher) {
          block()
        }
      }

      else -> block()
    }

  private inline fun <R> counted(block: () -> R): R {
    outstanding.incrementAndGet()
    var handedOff = false
    try {
      val result = block()
      handedOff = true
      return result
    }
    finally {
      if(!handedOff) {
        outstanding.decrementAndGet()
        finishCloseIfReady()
      }
    }
  }

  override suspend fun acquireWriterConnection(): SQLiteConnection = counted { lockWriter() }

  private suspend fun lockWriter(): SQLiteConnection {
    writerMutex.lock()
    var handedOff = false
    try {
      val connection = writerLocked()
      handedOff = true
      return connection
    }
    finally {
      // If opening the writer or running its PRAGMAs throws, we must release the mutex so
      // future acquires aren't blocked forever.
      if(!handedOff) writerMutex.unlock()
    }
  }

  private suspend fun writerLocked(): SQLiteConnection {
    if(isClosed) {
      closeWriterLocked()
      error(POOL_CLOSED_MESSAGE)
    }
    return writer()
  }

  private fun closeWriterLocked() {
    val connection = writerConnection ?: return
    writerConnection = null
    try {
      connection.close()
    }
    catch(_: Throwable) {}
    try {
      onConnectionClosed(connection)
    }
    catch(_: Throwable) {}
  }

  override suspend fun releaseWriterConnection() {
    try {
      if(isClosed) closeWriterLocked()
    }
    finally {
      writerMutex.unlock()
      outstanding.decrementAndGet()
      finishCloseIfReady()
    }
  }

  // The last release usually runs on one of the dispatcher's own threads, and closing a native
  // MultiWorkerDispatcher from inside one of its workers never returns.
  @OptIn(DelicateCoroutinesApi::class)
  @Suppress("InjectDispatcher")
  private fun finishCloseIfReady() {
    if(!isClosed || outstanding.value != 0) return
    if(!closeFinished.compareAndSet(expect = false, update = true)) return
    try {
      onClosed()
    }
    finally {
      closed.complete(Unit)
      GlobalScope.launch(Dispatchers.Default) {
        concurrencyModel.close()
      }
    }
  }

  /**
   * Tries to acquire the writer within [timeoutMs], returning it or null on timeout.
   *
   * Uses non-suspending tryLock() polling rather than a suspending lock() wrapped in
   * withTimeoutOrNull. A suspending acquire can complete (lock held) at the same instant the
   * timeout fires, and withTimeoutOrNull's prompt cancellation would then discard the result while
   * the lock stays held — leaking the writer forever. With tryLock() there is no window where the
   * lock is acquired but the connection is thrown away.
   */
  private suspend fun tryAcquireWriterConnection(timeoutMs: Long = 50): SQLiteConnection? {
    var locked = false
    var handedOff = false
    try {
      withTimeoutOrNull(timeoutMs) {
        while(!writerMutex.tryLock()) delay(1)
        locked = true
      }
      if(!locked) return null
      val connection = writerLocked()
      handedOff = true
      return connection
    }
    finally {
      // If the lock was taken but we're leaving without handing the connection to the caller —
      // cancellation after tryLock() succeeded, or opening the writer throwing — release it so
      // the writer isn't leaked.
      if(locked && !handedOff) writerMutex.unlock()
    }
  }

  override suspend fun acquireReaderConnection(): SQLiteConnection = counted {
    check(!isClosed) { POOL_CLOSED_MESSAGE }
    try {
      acquireReaderOrWriter()
    }
    catch(_: ClosedReceiveChannelException) {
      error(POOL_CLOSED_MESSAGE)
    }
  }

  private suspend fun acquireReaderOrWriter(): SQLiteConnection {
    while(true) {
      if(concurrencyModel.readerCount == 0) return lockWriter()
      // acquire() returns null when capacity dropped to 0 mid-wait (e.g. WAL → non-WAL swap).
      // Loop so we re-check concurrencyModel and route to the writer.
      val reader = readerPool.acquire(
        onEmpty = { tryAcquireWriterConnection() },
      )
      if(reader != null) return reader
    }
  }

  override suspend fun releaseReaderConnection(connection: SQLiteConnection) {
    when {
      concurrencyModel.readerCount == 0 || connection === writerConnection -> releaseWriterConnection()
      else -> {
        readerPool.release(connection)
        outstanding.decrementAndGet()
        finishCloseIfReady()
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

  override suspend fun <R> withAllConnectionsClosed(block: suspend () -> R): R =
    readerPool.withSwap(
      newCapacityAfter = { concurrencyModel.readerCount },
    ) {
      writerMutex.withLock {
        check(!isClosed) { POOL_CLOSED_MESSAGE }
        closeWriterLocked()
        block()
      }
    }

  override fun close() {
    if(isClosed) return
    isClosed = true
    if(writerMutex.tryLock()) {
      try {
        closeWriterLocked()
      }
      finally {
        writerMutex.unlock()
      }
    }
    readerPool.drainAndClose()
    finishCloseIfReady()
  }

  override suspend fun awaitClosed() {
    closed.await()
  }
}
