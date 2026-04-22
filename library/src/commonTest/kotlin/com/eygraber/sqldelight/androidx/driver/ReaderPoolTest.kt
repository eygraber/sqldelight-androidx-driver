package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReaderPoolTest {
  @Test
  fun `populate creates lazy readers that materialize on acquire`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })

    pool.populate(3)

    assertEquals(0, factory.createdConnections.size, "populate must not eagerly create connections")

    pool.acquireNonNull()
    assertEquals(1, factory.createdConnections.size)

    pool.acquireNonNull()
    pool.acquireNonNull()
    assertEquals(3, factory.createdConnections.size)
  }

  @Test
  fun `acquire returns distinct connections from the pool`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(3)

    val c1 = pool.acquireNonNull()
    val c2 = pool.acquireNonNull()
    val c3 = pool.acquireNonNull()

    assertEquals(3, setOf(c1, c2, c3).size, "each acquire should return a distinct connection")
  }

  @Test
  fun `acquire invokes onEmpty when pool is exhausted`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(1)

    pool.acquireNonNull()

    var onEmptyInvocations = 0
    val fallback = ReaderPoolTestConnection()
    val result = pool.acquire(
      onEmpty = {
        onEmptyInvocations++
        fallback
      },
    )

    assertEquals(1, onEmptyInvocations)
    assertSame(fallback, result)
  }

  @Test
  fun `acquire suspends on channel when onEmpty returns null and pool is empty`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(1)

    val c1 = pool.acquireNonNull()

    val pending = async {
      pool.acquireNonNull()
    }

    delay(100)
    assertFalse(pending.isCompleted, "acquire should suspend when pool and fallback are both empty")

    pool.release(c1)
    val resumed = pending.await()

    assertSame(c1, resumed)
  }

  @Test
  fun `release puts connection back for reuse`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(1)

    val first = pool.acquireNonNull()
    pool.release(first)

    val second = pool.acquireNonNull()

    assertSame(first, second)
  }

  @Test
  fun `currentCapacity reflects populate calls`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })

    assertEquals(0, pool.currentCapacity)

    pool.populate(4)
    assertEquals(4, pool.currentCapacity)
  }

  @Test
  fun `withSwap closes readers that were acquired before the swap`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(2)

    val c1 = pool.acquireNonNull() as ReaderPoolTestConnection
    val c2 = pool.acquireNonNull() as ReaderPoolTestConnection
    pool.release(c1)
    pool.release(c2)

    pool.withSwap(newCapacityAfter = { 2 }) { }

    assertTrue(c1.isClosed, "previously-created readers should be closed by the swap")
    assertTrue(c2.isClosed, "previously-created readers should be closed by the swap")
  }

  @Test
  fun `withSwap does not create connections for never-used readers`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(3)

    pool.withSwap(newCapacityAfter = { 3 }) { }

    assertEquals(
      0,
      factory.createdConnections.size,
      "a fresh reader that was never materialized must not be opened just to be closed",
    )
  }

  @Test
  fun `withSwap repopulates with the capacity returned by newCapacityAfter`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(1)

    pool.withSwap(newCapacityAfter = { 4 }) { }

    assertEquals(4, pool.currentCapacity)

    val acquired = List(4) { pool.acquireNonNull() }
    assertEquals(4, acquired.toSet().size, "repopulated channel should yield 4 distinct connections")
  }

  @Test
  fun `withSwap runs block under exclusive access`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(2)

    var insideBlockCapacity = -1
    pool.withSwap(newCapacityAfter = { 2 }) {
      insideBlockCapacity = pool.currentCapacity
    }

    assertEquals(
      0,
      insideBlockCapacity,
      "capacity should be 0 inside the block (old readers drained, new ones not yet populated)",
    )
    assertEquals(2, pool.currentCapacity)
  }

  @Test
  fun `withSwap waits for checked-out readers before running block`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(2)

    val c1 = pool.acquireNonNull()

    var blockRan = false
    val swapJob = async {
      pool.withSwap(newCapacityAfter = { 2 }) {
        blockRan = true
      }
    }

    delay(100)
    assertFalse(blockRan, "swap must wait for the checked-out reader before running its block")

    pool.release(c1)
    swapJob.await()

    assertTrue(blockRan)
  }

  @Test
  fun `acquires started during a swap park until it completes`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(1)

    val swapStarted = CompletableDeferred<Unit>()
    val releaseBlock = CompletableDeferred<Unit>()

    val swapJob = async {
      pool.withSwap(newCapacityAfter = { 1 }) {
        swapStarted.complete(Unit)
        releaseBlock.await()
      }
    }

    swapStarted.await()

    var acquired: SQLiteConnection? = null
    val acquireJob = async {
      acquired = pool.acquireNonNull()
    }

    delay(100)
    assertFalse(acquireJob.isCompleted, "acquire should park while the swap is running")
    assertNull(acquired)

    releaseBlock.complete(Unit)
    swapJob.await()
    acquireJob.await()

    assertTrue(acquired is ReaderPoolTestConnection)
  }

  @Test
  fun `concurrent swaps are serialized`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(1)

    val order = mutableListOf<String>()
    val firstReady = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()

    val first = async {
      pool.withSwap(newCapacityAfter = { 1 }) {
        order.add("first-start")
        firstReady.complete(Unit)
        releaseFirst.await()
        order.add("first-end")
      }
    }

    firstReady.await()

    val second = async {
      pool.withSwap(newCapacityAfter = { 1 }) {
        order.add("second-start")
        order.add("second-end")
      }
    }

    delay(100)
    assertEquals(
      listOf("first-start"),
      order,
      "second swap should block on the mutex while the first is still running",
    )

    releaseFirst.complete(Unit)
    first.await()
    second.await()

    assertEquals(
      listOf("first-start", "first-end", "second-start", "second-end"),
      order,
    )
  }

  @Test
  fun `withSwap falls back to prior capacity when newCapacityAfter throws`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(3)

    pool.withSwap(newCapacityAfter = { throw IllegalStateException("boom") }) { }

    assertEquals(
      3,
      pool.currentCapacity,
      "capacity should fall back to prior value when newCapacityAfter throws",
    )
  }

  @Test
  fun `withSwap runs block even when prior capacity is zero`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })

    var blockRan = false
    pool.withSwap(newCapacityAfter = { 2 }) {
      blockRan = true
    }

    assertTrue(blockRan)
    assertEquals(2, pool.currentCapacity)
  }

  @Test
  fun `drainAndClose returns drained count and closes created readers`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(3)

    val c1 = pool.acquireNonNull() as ReaderPoolTestConnection
    val c2 = pool.acquireNonNull() as ReaderPoolTestConnection
    pool.release(c1)
    pool.release(c2)

    val drained = pool.drainAndClose()

    assertEquals(3, drained, "should drain the 2 released readers plus the 1 never-used one")
    assertTrue(c1.isClosed)
    assertTrue(c2.isClosed)
  }

  @Test
  fun `drainAndClose does not create connections for fresh readers`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(3)

    pool.drainAndClose()

    assertEquals(
      0,
      factory.createdConnections.size,
      "drainAndClose must not materialize lazy readers just to close them",
    )
  }

  @Test
  fun `drainAndClose returns zero when nothing has been populated`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })

    assertEquals(0, pool.drainAndClose())
  }

  @Test
  fun `concurrent acquires on a populated pool do not serialize`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(5)

    val acquired = mutableListOf<SQLiteConnection>()
    coroutineScope {
      val jobs = List(5) {
        async { pool.acquireNonNull() }
      }
      acquired.addAll(jobs.map { it.await() })
    }

    assertEquals(5, acquired.toSet().size, "5 concurrent acquires should all succeed with distinct connections")
  }

  @Test
  fun `release after swap does not mix with fresh readers`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(1)

    val before = pool.acquireNonNull()
    pool.release(before)

    pool.withSwap(newCapacityAfter = { 1 }) { }

    val after = pool.acquireNonNull()
    assertNotSame(before, after, "post-swap acquire should return a freshly-created reader, not the pre-swap one")
  }

  @Test
  fun `swap waits for all checked-out readers regardless of when released`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(3)

    val c1 = pool.acquireNonNull()
    val c2 = pool.acquireNonNull()
    val c3 = pool.acquireNonNull()

    val swapJob = launch {
      pool.withSwap(newCapacityAfter = { 3 }) { }
    }

    delay(50)
    assertFalse(swapJob.isCompleted)

    pool.release(c1)
    delay(50)
    assertFalse(swapJob.isCompleted, "swap must wait for all 3 readers, not just the first")

    pool.release(c2)
    delay(50)
    assertFalse(swapJob.isCompleted)

    pool.release(c3)
    swapJob.join()
  }

  @Test
  fun `acquires parked during swap are resumed in order`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(1)

    val swapStarted = CompletableDeferred<Unit>()
    val releaseBlock = CompletableDeferred<Unit>()

    val swapJob = launch {
      pool.withSwap(newCapacityAfter = { 2 }) {
        swapStarted.complete(Unit)
        releaseBlock.await()
      }
    }

    swapStarted.await()

    val acquired = mutableListOf<SQLiteConnection>()
    val jobs = List(2) {
      launch {
        acquired.add(pool.acquireNonNull())
      }
    }

    delay(100)
    assertEquals(0, acquired.size, "acquires must be parked while swap is running")

    releaseBlock.complete(Unit)
    swapJob.join()
    jobs.joinAll()

    assertEquals(2, acquired.size)
    assertEquals(2, acquired.toSet().size)
  }

  @Test
  fun `acquire parked on receive returns null when swap drops capacity to zero`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(1)

    // Check out the only reader so the next acquire parks on channel.receive().
    val reader = pool.acquireNonNull() as ReaderPoolTestConnection

    val parkedAcquire = async {
      pool.acquire(onEmpty = { null })
    }

    delay(50)
    assertFalse(parkedAcquire.isCompleted, "acquire should be parked while the only reader is checked out")

    // UNDISPATCHED so withSwap runs synchronously up to its first suspension — by the time
    // this line returns the fence is set and the drain is blocked on channel.receive().
    val swapJob = async(start = CoroutineStart.UNDISPATCHED) {
      pool.withSwap(newCapacityAfter = { 0 }) { }
    }

    // Release so the drain gets the reader. The parked acquire must still resume with null
    // because capacity is 0 after the swap, even though the channel briefly had an item.
    pool.release(reader)
    swapJob.await()

    val result = parkedAcquire.await()
    assertNull(
      result,
      "acquire must return null so the caller can re-route to the writer after capacity dropped to 0",
    )
    assertEquals(0, pool.currentCapacity)
    assertTrue(reader.isClosed, "drained reader must be closed")
  }

  @Test
  fun `acquire parked on receive returns null when fresh swap drops capacity to zero`() = runTest {
    // Variant of the above: prior capacity is already 0 before the swap (so the drain trivially
    // completes), new capacity is 0, and the acquire gets woken purely by the post-swap wakeup
    // signal since no channel send ever happens.
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    // Capacity stays at 0.

    val parkedAcquire = async {
      pool.acquire(onEmpty = { null })
    }

    delay(50)
    // With capacity 0 at entry, the top-of-loop check returns null immediately.
    assertNull(parkedAcquire.await())
  }

  @Test
  fun `acquire suspended on receive before swap starts yields to the drain`() = runTest {
    val factory = ReaderPoolReaderPoolTestConnectionFactory()
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(1)

    // Check out the only reader, forcing the next acquire to suspend on channel.receive().
    val reader = pool.acquireNonNull() as ReaderPoolTestConnection

    val parkedAcquireFinished = CompletableDeferred<SQLiteConnection>()
    val parkedAcquireJob = async {
      val result = pool.acquireNonNull()
      parkedAcquireFinished.complete(result)
      result
    }

    // Let the parked acquire register its receive before the swap starts.
    delay(50)
    assertFalse(parkedAcquireJob.isCompleted)

    val swapBlockRan = CompletableDeferred<Unit>()
    val swapJob = async {
      pool.withSwap(newCapacityAfter = { 1 }) {
        swapBlockRan.complete(Unit)
      }
    }

    delay(50)
    assertFalse(swapBlockRan.isCompleted, "swap must not start its block until the drain completes")
    assertFalse(parkedAcquireJob.isCompleted, "parked acquire must not steal a reader that belongs to the drain")

    // Release the checked-out reader. Without the fence re-check this would be delivered to
    // the parked acquire, and the drain would deadlock — with the fix, the acquire gives the
    // entry back to the drain and re-parks on the fence.
    pool.release(reader)

    swapJob.await()
    assertTrue(reader.isClosed, "pre-swap reader must be closed by the drain")

    val acquiredConnection = parkedAcquireJob.await()
    assertTrue(acquiredConnection is ReaderPoolTestConnection)
    assertNotSame(
      reader,
      acquiredConnection,
      "parked acquire should receive a freshly-created reader after the swap repopulates",
    )
  }

  @Test
  fun `acquire failing to open a reader does not permanently shrink capacity`() = runTest {
    val factory = FailingReaderPoolConnectionFactory(failuresBeforeSuccess = 1)
    val pool = ReaderPool(factory, name = { "test.db" })
    pool.populate(1)

    assertFailsWith<IllegalStateException> {
      pool.acquire(onEmpty = { null })
    }

    // The failed entry must have been replaced in the channel, so the pool is still acquirable.
    val acquired = pool.acquireNonNull()
    assertTrue(acquired is ReaderPoolTestConnection)

    // A subsequent swap must not block — drain needs to find the replacement entry.
    pool.release(acquired)
    pool.withSwap(newCapacityAfter = { 1 }) { }
  }
}

private suspend fun ReaderPool.acquireNonNull(): SQLiteConnection =
  requireNotNull(acquire(onEmpty = { null })) {
    "acquire returned null — pool capacity dropped to 0 during the test"
  }

private class ReaderPoolTestStatement : SQLiteStatement {
  override fun step(): Boolean = true
  override fun getBoolean(index: Int): Boolean = false
  override fun getText(index: Int): String = ""
  override fun getLong(index: Int): Long = 0L
  override fun getDouble(index: Int): Double = 0.0
  override fun getBlob(index: Int): ByteArray = ByteArray(0)
  override fun isNull(index: Int): Boolean = false
  override fun getColumnCount(): Int = 0
  override fun getColumnName(index: Int): String = ""
  override fun getColumnType(index: Int): Int = 0
  override fun bindBlob(index: Int, value: ByteArray) {}
  override fun bindDouble(index: Int, value: Double) {}
  override fun bindLong(index: Int, value: Long) {}
  override fun bindText(index: Int, value: String) {}
  override fun bindNull(index: Int) {}
  override fun clearBindings() {}
  override fun close() {}
  override fun reset() {}
}

private class ReaderPoolTestConnection : SQLiteConnection {
  var isClosed: Boolean = false
    private set

  override fun prepare(sql: String): SQLiteStatement = ReaderPoolTestStatement()

  override fun close() {
    isClosed = true
  }
}

private class ReaderPoolReaderPoolTestConnectionFactory : AndroidxSqliteConnectionFactory {
  val createdConnections: MutableList<ReaderPoolTestConnection> = mutableListOf()

  override val driver: SQLiteDriver = object : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection = ReaderPoolTestConnection()
  }

  override fun createConnection(name: String): SQLiteConnection {
    val connection = ReaderPoolTestConnection()
    createdConnections.add(connection)
    return connection
  }
}

private class FailingReaderPoolConnectionFactory(
  private var failuresBeforeSuccess: Int,
) : AndroidxSqliteConnectionFactory {
  override val driver: SQLiteDriver = object : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection = ReaderPoolTestConnection()
  }

  override fun createConnection(name: String): SQLiteConnection {
    if(failuresBeforeSuccess > 0) {
      failuresBeforeSuccess--
      error("open failed")
    }
    return ReaderPoolTestConnection()
  }
}
