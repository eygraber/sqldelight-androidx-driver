package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Manages the lazy reader connections for [AndroidxDriverConnectionPool].
 *
 * Concurrent acquires don't serialize through a mutex — coordination with [withSwap] uses a
 * fence completable that parks new acquires only while a swap is in progress. When no swap is
 * running, acquires go straight to the channel.
 */
internal class ReaderPool(
  private val connectionFactory: AndroidxSqliteConnectionFactory,
  private val name: () -> String,
  private val onConnectionClosed: (SQLiteConnection) -> Unit = {},
) {
  private data class ReaderEntry(
    val connection: SQLiteConnection?,
  )

  // onUndeliveredElement: a receiver cancelled at the same instant a send resumes it (prompt
  // cancellation) never sees the entry; without this handler the entry would be lost and the pool
  // would permanently shrink, eventually hanging withSwap's drain and close(). Re-send it —
  // trySend on an UNLIMITED channel only fails if the pool was closed, in which case the
  // connection just needs to be closed like drainAndClose would have.
  private val channel: Channel<ReaderEntry> = Channel(
    capacity = Channel.UNLIMITED,
    onUndeliveredElement = { entry ->
      if(channel.trySend(entry).isFailure) {
        entry.connection?.let(::closeQuietly)
      }
    },
  )

  private val swapMutex = Mutex()

  @Volatile
  private var capacity: Int = 0

  @Volatile
  private var swapFence: CompletableDeferred<Unit>? = null

  // Wakes acquires that are parked on channel.receive() when a swap completes. Needed for the
  // case where capacity drops to 0 (e.g. WAL → non-WAL with nonWalCount = 0): no future sends
  // will ever arrive, so without this signal the parked receive would hang forever.
  @Volatile
  private var postSwapWakeup: CompletableDeferred<Unit> = CompletableDeferred()

  val currentCapacity: Int get() = capacity

  /**
   * Populates the channel with [newCapacity] fresh, unopened reader entries.
   * Any existing readers must have been drained first.
   */
  fun populate(newCapacity: Int) {
    capacity = newCapacity
    repeat(newCapacity) {
      channel.trySend(ReaderEntry(connection = null))
    }
  }

  /**
   * Acquires a reader. If the pool is empty, [onEmpty] is invoked (typically to try the writer
   * as a fallback); if that also returns null, suspends on the channel until a reader is
   * released or an in-progress swap completes.
   *
   * While a swap is running, acquires park on the swap fence and retry once it clears.
   *
   * Returns null if the pool's capacity became 0 while this acquire was running, so the caller
   * should re-check [AndroidxSqliteConcurrencyModel.readerCount] and route to the writer.
   */
  @Suppress("ReturnCount")
  suspend fun acquire(onEmpty: suspend () -> SQLiteConnection?): SQLiteConnection? {
    while(true) {
      swapFence?.await()

      if(capacity == 0) return null

      val tryReceived = channel.tryReceive().getOrNull()
      if(tryReceived != null) {
        // A swap could have started between our fence check and tryReceive — if so, this entry
        // belongs to the drain. Hand it back and park on the fence.
        val fence = swapFence ?: return materializeOrReturn(tryReceived)
        channel.send(tryReceived)
        fence.await()
        continue
      }

      val fallback = onEmpty()
      if(fallback != null) return fallback

      val fenceBeforeReceive = swapFence
      if(fenceBeforeReceive != null) {
        fenceBeforeReceive.await()
        continue
      }

      // Race channel.receive() against the post-swap wakeup so a swap that drops capacity to 0
      // mid-wait doesn't leave us parked forever. Capture the wakeup before selecting — if a
      // swap starts and completes after this line, it'll fire the captured one.
      val wakeup = postSwapWakeup
      val received = select {
        channel.onReceive { it }
        wakeup.onAwait { null }
      }
      if(received == null) continue

      // Same race as the tryReceive path above: a swap may have set the fence while we were
      // parked on receive(), and the sender was a release feeding the drain.
      val fenceAfterReceive = swapFence ?: return materializeOrReturn(received)
      channel.send(received)
      fenceAfterReceive.await()
    }
  }

  /**
   * Opens a received entry's connection when it has none yet. If creation fails, a fresh unopened
   * entry is put back into the channel so a future acquire can retry — without this, a transient
   * open failure would permanently shrink capacity and a later swap's drain would block waiting
   * for a slot that never comes back.
   */
  private suspend fun materializeOrReturn(entry: ReaderEntry): SQLiteConnection =
    entry.connection ?: createReader()

  private suspend fun createReader(): SQLiteConnection {
    var created = false
    try {
      val connection = connectionFactory.createConnection(name())
      created = true
      return connection
    }
    finally {
      if(!created) channel.trySend(ReaderEntry(connection = null))
    }
  }

  suspend fun release(connection: SQLiteConnection) {
    channel.send(ReaderEntry(connection = connection))
  }

  /**
   * Runs [block] under exclusive access to the pool:
   *  1. Fences off new acquires.
   *  2. Drains and closes every reader currently in the channel, waiting for checked-out
   *     readers to be released back.
   *  3. Runs [block].
   *  4. Repopulates the channel using the capacity returned by [newCapacityAfter].
   *  5. Wakes parked acquirers.
   */
  suspend fun <R> withSwap(
    newCapacityAfter: () -> Int,
    block: suspend () -> R,
  ): R = swapMutex.withLock {
    val fence = CompletableDeferred<Unit>()
    swapFence = fence

    val priorCapacity = capacity
    try {
      // The drain must complete as a unit — if cancellation interrupted it partway, the finally
      // below would populate fresh readers on top of un-drained old ones and future acquires
      // could hand out stale, pre-swap connections.
      withContext(NonCancellable) {
        repeat(priorCapacity) {
          channel.receive().connection?.let(::closeQuietly)
        }
        capacity = 0
      }

      return block()
    }
    finally {
      // The body below is non-suspending today, but wrap in NonCancellable so future cleanup
      // steps that suspend won't be interrupted when the parent coroutine was already canceled.
      withContext(NonCancellable) {
        val nextCapacity = runCatching { newCapacityAfter() }.getOrDefault(priorCapacity)
        populate(nextCapacity)
        swapFence = null
        // Roll the wakeup so acquires parked on channel.receive() can re-check capacity and
        // bail out if it dropped to 0.
        val wakeup = postSwapWakeup
        postSwapWakeup = CompletableDeferred()
        wakeup.complete(Unit)
        fence.complete(Unit)
      }
    }
  }

  /**
   * Drains any readers currently in the channel, closes the channel, and returns the number
   * drained. Does not wait for checked-out readers — callers should enforce that rule at the
   * caller level.
   */
  fun drainAndClose(): Int {
    var drained = 0
    while(true) {
      val reader = channel.tryReceive().getOrNull() ?: break
      drained++
      reader.connection?.let(::closeQuietly)
    }
    channel.close()
    return drained
  }

  private fun closeQuietly(connection: SQLiteConnection) {
    try {
      connection.close()
    }
    catch(_: Throwable) {}
    try {
      onConnectionClosed(connection)
    }
    catch(_: Throwable) {}
  }
}
