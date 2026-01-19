package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.SingleReaderWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidxDriverConnectionPoolTest {
  @Test
  fun `acquireWriterConnection blocks until connection is released`() = runTest {
    val connectionFactory = TestConnectionFactory()
    val pool = AndroidxDriverConnectionPool(
      connectionFactory = connectionFactory,
      nameProvider = { "test.db" },
      isFileBased = true,
      configuration = AndroidxSqliteConfiguration(),
    )

    // Acquire the writer connection in first coroutine
    val firstConnection = pool.acquireWriterConnection()

    var secondConnectionAcquired = false
    var secondConnection: SQLiteConnection? = null

    // Launch second coroutine that tries to acquire writer connection
    val job = launch {
      secondConnection = pool.acquireWriterConnection()
      secondConnectionAcquired = true
    }

    // Give the second coroutine time to try to acquire
    delay(100)

    // Second connection should not have been acquired yet
    assertFalse(secondConnectionAcquired, "Second connection should be blocked")

    // Release first connection
    pool.releaseWriterConnection()

    // Wait for second coroutine to complete
    job.join()

    // Now second connection should be acquired
    assertTrue(secondConnectionAcquired, "Second connection should be acquired after release")
    assertSame(firstConnection, secondConnection, "Should get the same writer connection")

    pool.releaseWriterConnection()
    pool.close()
  }

  @Test
  fun `multiple reader connections can be acquired simultaneously in WAL mode`() = runTest {
    val connectionFactory = TestConnectionFactory()
    val pool = AndroidxDriverConnectionPool(
      connectionFactory = connectionFactory,
      nameProvider = { "test.db" },
      isFileBased = true,
      configuration = AndroidxSqliteConfiguration(
        concurrencyModel = MultipleReadersSingleWriter(isWal = true, walCount = 3),
      ),
    )

    val connections = mutableListOf<SQLiteConnection>()

    // Acquire multiple reader connections simultaneously
    coroutineScope {
      val jobs = List(3) {
        async {
          pool.acquireReaderConnection()
        }
      }

      connections.addAll(jobs.map { it.await() })
    }

    // All connections should be different
    assertEquals(3, connections.size)
    assertEquals(3, connections.toSet().size, "All reader connections should be different")

    // Release all connections
    connections.forEach { pool.releaseReaderConnection(it) }
    pool.close()
  }

  @Test
  fun `SingleReaderWriter uses same connection for reads and writes`() = runTest {
    val connectionFactory = TestConnectionFactory()
    val pool = AndroidxDriverConnectionPool(
      connectionFactory = connectionFactory,
      nameProvider = { "test.db" },
      isFileBased = true,
      configuration = AndroidxSqliteConfiguration(
        concurrencyModel = SingleReaderWriter(),
      ),
    )

    val writerConnection = pool.acquireWriterConnection()
    // Release the writer connection first to avoid deadlock
    pool.releaseWriterConnection()

    val readerConnection = pool.acquireReaderConnection()

    // Should be the same connection instance
    assertSame(writerConnection, readerConnection, "SingleReaderWriter should use same connection for reads and writes")

    pool.releaseReaderConnection(readerConnection)
    pool.close()
  }

  @Test
  fun `SingleReaderWriter enforces sequential access - no concurrent reads and writes`() = runTest {
    val connectionFactory = TestConnectionFactory()
    val pool = AndroidxDriverConnectionPool(
      connectionFactory = connectionFactory,
      nameProvider = { "test.db" },
      isFileBased = true,
      configuration = AndroidxSqliteConfiguration(
        concurrencyModel = SingleReaderWriter(),
      ),
    )

    // Acquire writer connection
    val writerConnection = pool.acquireWriterConnection()

    var readerBlocked = true
    var readerConnection: SQLiteConnection? = null

    // Try to acquire reader connection - should block since writer is held
    val readerJob = launch {
      readerConnection = pool.acquireReaderConnection()
      readerBlocked = false
    }

    // Give the reader attempt time to try
    delay(100)

    // Reader should still be blocked
    assertTrue(readerBlocked, "Reader should be blocked while writer is held")

    // Release writer
    pool.releaseWriterConnection()

    // Wait for reader to complete
    readerJob.join()

    // Reader should now have succeeded and gotten the same connection
    assertFalse(readerBlocked, "Reader should succeed after writer is released")
    assertSame(writerConnection, readerConnection, "Should get same connection instance")

    readerConnection?.let { pool.releaseReaderConnection(it) }
    pool.close()
  }

  @Test
  fun `reader connections block when pool is exhausted and resume when connection is released`() = runTest {
    val connectionFactory = TestConnectionFactory()
    val pool = AndroidxDriverConnectionPool(
      connectionFactory = connectionFactory,
      nameProvider = { "test.db" },
      isFileBased = true,
      configuration = AndroidxSqliteConfiguration(
        concurrencyModel = MultipleReadersSingleWriter(isWal = true, walCount = 2),
      ),
    )

    // Acquire the writer connection since reads will fall back to it
    pool.acquireWriterConnection()

    // Acquire all available reader connections
    val connection1 = pool.acquireReaderConnection()
    val connection2 = pool.acquireReaderConnection()

    var blockedConnectionAcquired = false
    var blockedConnection: SQLiteConnection? = null

    // Try to acquire a third connection - should block since pool is exhausted
    val blockedJob = launch {
      blockedConnection = pool.acquireReaderConnection()
      blockedConnectionAcquired = true
    }

    // Give the blocked coroutine time to try
    delay(100)

    // Third connection should not have been acquired yet
    assertFalse(blockedConnectionAcquired, "Third connection should be blocked when pool is exhausted")

    // Release one connection to make room
    pool.releaseReaderConnection(connection1)

    // Wait for the blocked coroutine to complete
    blockedJob.join()

    // Now the blocked connection should have been acquired
    assertTrue(blockedConnectionAcquired, "Blocked connection should be acquired after one is released")
    blockedConnection?.let { connection ->
      // The newly acquired connection should be one of the connections we saw before
      assertTrue(
        connection === connection1 || connection === connection2,
        "Released connection should be reused",
      )
    }

    // Clean up remaining connections
    pool.releaseReaderConnection(connection2)
    blockedConnection?.let { pool.releaseReaderConnection(it) }
    pool.releaseWriterConnection()
    pool.close()
  }

  private class TestStatement : SQLiteStatement {
    override fun step(): Boolean = true
    override fun getBoolean(index: Int): Boolean = false
    override fun getText(index: Int): String = ""
    override fun getLong(index: Int): Long = 0L
    override fun getDouble(index: Int): Double = 0.0
    override fun getBlob(index: Int): ByteArray = ByteArray(0)
    override fun isNull(index: Int): Boolean = false
    override fun getColumnCount(): Int = 1
    override fun getColumnName(index: Int): String = "test_column"
    override fun getColumnType(index: Int): Int = 3
    override fun bindBlob(index: Int, value: ByteArray) {}
    override fun bindDouble(index: Int, value: Double) {}
    override fun bindLong(index: Int, value: Long) {}
    override fun bindText(index: Int, value: String) {}
    override fun bindNull(index: Int) {}
    override fun clearBindings() {}
    override fun close() {}
    override fun reset() {}
  }

  private class TestConnection : SQLiteConnection {
    var isClosed = false

    override fun prepare(sql: String): SQLiteStatement = TestStatement()

    override fun close() {
      isClosed = true
    }
  }

  private class TestConnectionFactory : AndroidxSqliteConnectionFactory {
    override val driver = object : SQLiteDriver {
      override fun open(fileName: String): SQLiteConnection = TestConnection()
    }

    override fun createConnection(name: String): SQLiteConnection = TestConnection()
  }
}
