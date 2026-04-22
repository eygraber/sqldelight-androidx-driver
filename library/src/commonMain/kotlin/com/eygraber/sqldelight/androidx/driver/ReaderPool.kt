package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
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
    val isCreated: Boolean,
    val connection: Lazy<SQLiteConnection>,
  )

  private val channel = Channel<ReaderEntry>(capacity = Channel.UNLIMITED)

  private val swapMutex = Mutex()

  @Volatile
  private var capacity: Int = 0

  @Volatile
  private var swapFence: CompletableDeferred<Unit>? = null

  val currentCapacity: Int get() = capacity

  /**
   * Populates the channel with [newCapacity] fresh, unopened reader entries.
   * Any existing readers must have been drained first.
   */
  fun populate(newCapacity: Int) {
    capacity = newCapacity
    repeat(newCapacity) {
      channel.trySend(
        ReaderEntry(
          isCreated = false,
          connection = lazy { connectionFactory.createConnection(name()) },
        ),
      )
    }
  }

  /**
   * Acquires a reader. If the pool is empty, [onEmpty] is invoked (typically to try the writer
   * as a fallback); if that also returns null, suspends on the channel until a reader is
   * released or an in-progress swap completes.
   *
   * While a swap is running, acquires park on the swap fence and retry once it clears.
   */
  @Suppress("ReturnCount")
  suspend fun acquire(onEmpty: suspend () -> SQLiteConnection?): SQLiteConnection {
    while(true) {
      swapFence?.await()

      val tryReceived = channel.tryReceive().getOrNull()
      if(tryReceived != null) {
        // A swap could have started between our fence check and tryReceive — if so, this entry
        // belongs to the drain. Hand it back and park on the fence.
        val fence = swapFence ?: return tryReceived.connection.value
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

      val received = channel.receive()
      // Same race as the tryReceive path above: a swap may have set the fence while we were
      // parked on receive(), and the sender was a release feeding the drain.
      val fenceAfterReceive = swapFence ?: return received.connection.value
      channel.send(received)
      fenceAfterReceive.await()
    }
  }

  suspend fun release(connection: SQLiteConnection) {
    channel.send(
      ReaderEntry(
        isCreated = true,
        connection = lazy { connection },
      ),
    )
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
          val reader = channel.receive()
          if(reader.isCreated) {
            val connection = reader.connection.value
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
      if(reader.isCreated) {
        val connection = reader.connection.value
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
    channel.close()
    return drained
  }
}
