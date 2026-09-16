package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReaders
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.SingleReaderWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SuspendingConnectionFactoryTest {
  @Test
  fun `writer acquire completes when the factory suspends on the pool dispatcher`() =
    runTest(timeout = 15.seconds) {
      val dispatcher = IoDispatcher.limitedParallelism(1)
      val factory = DispatchingConnectionFactory(dispatcher)
      val pool = AndroidxDriverConnectionPool(
        connectionFactory = factory,
        nameProvider = { "test.db" },
        isFileBased = true,
        configuration = AndroidxSqliteConfiguration(
          concurrencyModel = SingleReaderWriter(dispatcherProvider = { _, _ -> dispatcher }),
        ),
      )

      val first = pool.runOnDispatcher { pool.acquireWriterConnection() }
      pool.releaseWriterConnection()
      val second = pool.runOnDispatcher { pool.acquireWriterConnection() }
      pool.releaseWriterConnection()

      assertSame(first, second)
      assertEquals(1, factory.created)
      pool.close()
    }

  @Test
  fun `reader acquire completes when the factory suspends on the pool dispatcher`() =
    runTest(timeout = 15.seconds) {
      val dispatcher = IoDispatcher.limitedParallelism(1)
      val factory = DispatchingConnectionFactory(dispatcher)
      val pool = AndroidxDriverConnectionPool(
        connectionFactory = factory,
        nameProvider = { "test.db" },
        isFileBased = true,
        configuration = AndroidxSqliteConfiguration(
          concurrencyModel = MultipleReaders(
            readerCount = 2,
            dispatcherProvider = { _, _ -> dispatcher },
          ),
        ),
      )

      pool.runOnDispatcher {
        val readers = coroutineScope {
          List(2) { async { pool.acquireReaderConnection() } }.awaitAll()
        }
        assertNotSame(readers[0], readers[1])
        readers.forEach { pool.releaseReaderConnection(it) }
      }

      assertEquals(2, factory.created)
      pool.close()
    }

  @Test
  fun `passthrough acquire completes when the factory suspends on the caller dispatcher`() =
    runTest(timeout = 15.seconds) {
      val dispatcher = IoDispatcher.limitedParallelism(1)
      val factory = DispatchingConnectionFactory(dispatcher, FakeDriver(hasConnectionPool = true))
      val pool = PassthroughConnectionPool(
        connectionFactory = factory,
        nameProvider = { "test.db" },
        configuration = AndroidxSqliteConfiguration(),
      )

      val (writer, reader) = withContext(dispatcher) {
        pool.acquireWriterConnection() to pool.acquireReaderConnection()
      }

      assertSame(writer, reader)
      assertEquals(1, factory.created)
      pool.close()
    }

  @Test
  fun `writer slot stays empty after a failed createConnection`() = runTest {
    val factory = FailOnceConnectionFactory()
    val pool = AndroidxDriverConnectionPool(
      connectionFactory = factory,
      nameProvider = { "test.db" },
      isFileBased = true,
      configuration = AndroidxSqliteConfiguration(),
    )

    assertFailsWith<IllegalStateException> {
      pool.acquireWriterConnection()
    }

    val writer = pool.acquireWriterConnection()
    pool.releaseWriterConnection()

    assertEquals(2, factory.attempts)
    assertSame(writer, pool.acquireWriterConnection())
    pool.releaseWriterConnection()
    pool.close()
  }

  @Test
  fun `passthrough slot stays empty after a failed createConnection`() = runTest {
    val factory = FailOnceConnectionFactory(hasConnectionPool = true)
    val pool = PassthroughConnectionPool(
      connectionFactory = factory,
      nameProvider = { "test.db" },
      configuration = AndroidxSqliteConfiguration(),
    )

    assertFailsWith<IllegalStateException> {
      pool.acquireWriterConnection()
    }

    val connection = pool.acquireWriterConnection()

    assertEquals(2, factory.attempts)
    assertSame(connection, pool.acquireReaderConnection())
    pool.close()
  }

  private class DispatchingConnectionFactory(
    private val dispatcher: CoroutineDispatcher,
    override val driver: SQLiteDriver = FakeDriver(hasConnectionPool = false),
  ) : AndroidxSqliteConnectionFactory {
    var created = 0

    override suspend fun createConnection(name: String): SQLiteConnection =
      withContext(dispatcher) {
        created++
        driver.open(name)
      }
  }

  private class FailOnceConnectionFactory(
    hasConnectionPool: Boolean = false,
  ) : AndroidxSqliteConnectionFactory {
    var attempts = 0

    override val driver: SQLiteDriver = FakeDriver(hasConnectionPool)

    override suspend fun createConnection(name: String): SQLiteConnection {
      attempts++
      if(attempts == 1) error("boom")
      return driver.open(name)
    }
  }

  private class FakeDriver(
    override val hasConnectionPool: Boolean,
  ) : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection = FakeConnection()
  }

  private class FakeConnection : SQLiteConnection {
    override fun prepare(sql: String): SQLiteStatement = FakeStatement()
    override fun close() {}
  }

  private class FakeStatement : SQLiteStatement {
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
}
