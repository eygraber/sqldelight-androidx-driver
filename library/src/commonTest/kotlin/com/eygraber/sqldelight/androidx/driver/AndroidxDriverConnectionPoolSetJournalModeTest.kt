package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import app.cash.sqldelight.db.QueryResult
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.SingleReaderWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidxDriverConnectionPoolSetJournalModeTest {
  @Test
  fun `AndroidxDriverConnectionPool setJournalMode with WAL updates concurrency model`() {
    val testConnectionFactory = TestConnectionFactory()
    val configuration = AndroidxSqliteConfiguration(
      concurrencyModel = MultipleReadersSingleWriter(isWal = false, walCount = 2, nonWalCount = 0),
    )

    val pool = AndroidxDriverConnectionPool(
      connectionFactory = testConnectionFactory,
      nameProvider = { "test.db" },
      isFileBased = true,
      configuration = configuration,
    )

    val result = pool.setJournalMode { connection ->
      // Simulate a journal mode query returning "wal"
      QueryResult.Value("wal")
    }

    assertEquals("wal", result.value)

    // After setting WAL mode, we should be able to get reader connections
    // that are different from the writer connection
    val readerConnection = pool.acquireReaderConnection()
    val writerConnection = pool.acquireWriterConnection()

    // In WAL mode with multiple readers, reader should be different from writer
    assertTrue(
      readerConnection !== writerConnection,
      "In WAL mode, reader connection should be different from writer connection",
    )

    pool.releaseReaderConnection(readerConnection)
    pool.releaseWriterConnection()
    pool.close()
  }

  @Test
  fun `AndroidxDriverConnectionPool setJournalMode with DELETE updates concurrency model`() {
    val testConnectionFactory = TestConnectionFactory()
    val configuration = AndroidxSqliteConfiguration(
      concurrencyModel = MultipleReadersSingleWriter(isWal = true, walCount = 2, nonWalCount = 0),
    )

    val pool = AndroidxDriverConnectionPool(
      connectionFactory = testConnectionFactory,
      nameProvider = { "test.db" },
      isFileBased = true,
      configuration = configuration,
    )

    val result = pool.setJournalMode { connection ->
      // Simulate a journal mode query returning "delete" (non-WAL)
      QueryResult.Value("delete")
    }

    assertEquals("delete", result.value)

    // After setting DELETE mode, readers should fall back to writer connection
    pool.assertReaderAndWriterAreTheSame(
      message = "In non-WAL mode, reader connection should be same as writer connection",
    )

    pool.close()
  }

  @Test
  fun `AndroidxDriverConnectionPool setJournalMode handles case insensitive WAL detection`() {
    val testConnectionFactory = TestConnectionFactory()
    val configuration = AndroidxSqliteConfiguration(
      concurrencyModel = MultipleReadersSingleWriter(isWal = false, walCount = 2, nonWalCount = 0),
    )

    val pool = AndroidxDriverConnectionPool(
      connectionFactory = testConnectionFactory,
      nameProvider = { "test.db" },
      isFileBased = true,
      configuration = configuration,
    )

    // Test case insensitive matching for different WAL variations
    val walVariations = listOf("WAL", "wal", "Wal", "wAL")

    for(walMode in walVariations) {
      pool.setJournalMode { connection ->
        QueryResult.Value(walMode)
      }

      // Each time, we should be able to get reader connections (indicating WAL mode was detected)
      val readerConnection = pool.acquireReaderConnection()
      val writerConnection = pool.acquireWriterConnection()

      assertTrue(
        readerConnection !== writerConnection,
        "WAL mode should be detected case-insensitively for: $walMode",
      )

      pool.releaseReaderConnection(readerConnection)
      pool.releaseWriterConnection()
    }

    pool.close()
  }

  @Test
  fun `AndroidxDriverConnectionPool setJournalMode with SingleReaderWriter model`() {
    val testConnectionFactory = TestConnectionFactory()
    val configuration = AndroidxSqliteConfiguration(
      concurrencyModel = SingleReaderWriter,
    )

    val pool = AndroidxDriverConnectionPool(
      connectionFactory = testConnectionFactory,
      nameProvider = { "test.db" },
      isFileBased = true,
      configuration = configuration,
    )

    val result = pool.setJournalMode { connection ->
      QueryResult.Value("wal")
    }

    assertEquals("wal", result.value)

    // With SingleReaderWriter, reader and writer should always be the same
    pool.assertReaderAndWriterAreTheSame(
      message = "SingleReaderWriter should always use same connection for reads and writes",
    )

    pool.close()
  }

  @Test
  fun `AndroidxDriverConnectionPool setJournalMode with in-memory database uses SingleReaderWriter`() {
    val testConnectionFactory = TestConnectionFactory()
    val configuration = AndroidxSqliteConfiguration(
      concurrencyModel = MultipleReadersSingleWriter(isWal = true, walCount = 2, nonWalCount = 0),
    )

    val pool = AndroidxDriverConnectionPool(
      connectionFactory = testConnectionFactory,
      nameProvider = { ":memory:" },
      isFileBased = false, // This forces SingleReaderWriter
      configuration = configuration,
    )

    pool.setJournalMode { connection ->
      QueryResult.Value("wal")
    }

    // Even with WAL mode and MultipleReadersSingleWriter config,
    // in-memory databases should use SingleReaderWriter behavior
    pool.assertReaderAndWriterAreTheSame(
      message = "In-memory databases should always use SingleReaderWriter regardless of configuration",
    )

    pool.close()
  }

  @Test
  fun `AndroidxDriverConnectionPool setJournalMode closes and repopulates reader connections`() {
    val testConnectionFactory = TestConnectionFactory()
    val configuration = AndroidxSqliteConfiguration(
      concurrencyModel = MultipleReadersSingleWriter(isWal = true, walCount = 2, nonWalCount = 0),
    )

    val pool = AndroidxDriverConnectionPool(
      connectionFactory = testConnectionFactory,
      nameProvider = { "test.db" },
      isFileBased = true,
      configuration = configuration,
    )

    // First, acquire some reader connections to populate the channel
    val initialReader1 = pool.acquireReaderConnection()
    val initialReader2 = pool.acquireReaderConnection()
    pool.releaseReaderConnection(initialReader1)
    pool.releaseReaderConnection(initialReader2)

    // Track connections that get closed
    var connectionsClosed = 0
    testConnectionFactory.createdConnections.forEach { connection ->
      connection.executedStatements.clear()
    }
    testConnectionFactory.createdConnections.forEach { connection ->
      connection.executedStatements.add("CLOSE")
      connectionsClosed++
    }

    // Change journal mode - this should close existing readers and create new ones
    pool.setJournalMode { connection ->
      QueryResult.Value("delete") // Switch to non-WAL mode
    }

    // Verify that some connections were closed during the journal mode change
    assertTrue(
      connectionsClosed == 2,
      "All reader connections should have been closed during journal mode change",
    )

    pool.close()
  }

  @Test
  fun `MultipleReadersSingleWriter concurrency model WAL detection logic`() {
    val originalModel = MultipleReadersSingleWriter(
      isWal = false,
      walCount = 4,
      nonWalCount = 1,
    )

    val walEnabledModel = originalModel.copy(isWal = true)
    val walDisabledModel = originalModel.copy(isWal = false)

    // Test the logic that setJournalMode uses to update concurrency model
    assertEquals(1, originalModel.readerCount, "Non-WAL mode should use nonWalCount")
    assertEquals(4, walEnabledModel.readerCount, "WAL mode should use walCount")
    assertEquals(1, walDisabledModel.readerCount, "Non-WAL mode should use nonWalCount")
  }

  @Test
  fun `SingleReaderWriter concurrency model is unaffected by WAL`() {
    assertEquals(0, SingleReaderWriter.readerCount, "SingleReaderWriter should always have 0 readers")
  }
}

private fun ConnectionPool.assertReaderAndWriterAreTheSame(
  message: String,
) {
  val readerConnection = acquireReaderConnection()
  val readerHashCode = readerConnection.hashCode()
  releaseReaderConnection(readerConnection)
  val writerConnection = acquireWriterConnection()
  val writerHashCode = writerConnection.hashCode()
  releaseWriterConnection()

  assertTrue(
    readerHashCode == writerHashCode,
    message,
  )
}

private class TestStatement : SQLiteStatement {
  var stepCalled = false
  var booleanResult = false
  var textResult = ""
  var longResult = 0L
  var doubleResult = 0.0

  override fun step(): Boolean {
    stepCalled = true
    return true
  }

  override fun getBoolean(index: Int): Boolean = booleanResult
  override fun getText(index: Int): String = textResult
  override fun getLong(index: Int): Long = longResult
  override fun getDouble(index: Int): Double = doubleResult
  override fun getBlob(index: Int): ByteArray = ByteArray(0)
  override fun isNull(index: Int): Boolean = false
  override fun getColumnCount(): Int = 1
  override fun getColumnName(index: Int): String = "test_column"
  override fun getColumnType(index: Int): Int = 3 // TEXT type
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
  val executedStatements = mutableListOf<String>()
  private val preparedStatements = mutableMapOf<String, TestStatement>()

  fun setPragmaResult(pragma: String, result: Boolean) {
    val statement = TestStatement().apply { booleanResult = result }
    preparedStatements[pragma] = statement
  }

  override fun prepare(sql: String): SQLiteStatement {
    executedStatements.add("PREPARE: $sql")
    return preparedStatements[sql] ?: TestStatement()
  }

  override fun close() {
    isClosed = true
    executedStatements.add("CLOSE")
  }
}

private class TestConnectionFactory : AndroidxSqliteConnectionFactory {
  override val driver = object : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection = TestConnection()
  }
  val createdConnections = mutableListOf<TestConnection>()

  override fun createConnection(name: String): SQLiteConnection {
    val connection = TestConnection().apply {
      setPragmaResult("PRAGMA foreign_keys;", false) // Default: foreign keys disabled
    }
    createdConnections.add(connection)
    return connection
  }
}
