package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class AndroidxSqliteWebDriverTest {
  private val schema = object : SqlSchema<QueryResult.AsyncValue<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {
      driver.execute(
        null,
        """
        |CREATE TABLE test (
        |  id INTEGER NOT NULL PRIMARY KEY,
        |  value TEXT
        |);
        """.trimMargin(),
        0,
      ).await()
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
      vararg callbacks: AfterVersion,
    ) = QueryResult.AsyncValue {}
  }

  @AfterTest
  fun cleanup() {
    closeAndroidxSqliteTestDriver()
  }

  private fun newDbName(): String = "AndroidxSqliteWebDriverTest-${Random.nextULong().toHexString()}.db"

  @Test
  fun memoryDriverExecutesAndQueries() = runTest {
    val driver = AndroidxSqliteDriver(
      driver = androidxSqliteTestDriver(),
      databaseType = AndroidxSqliteDatabaseType.Memory,
      schema = schema,
    )

    driver.execute(null, "INSERT INTO test VALUES (1, 'one')", 0).await()
    driver.execute(null, "INSERT INTO test VALUES (2, 'two')", 0).await()

    val rows = driver.executeQuery(
      identifier = null,
      sql = "SELECT id, value FROM test ORDER BY id ASC",
      mapper = { cursor ->
        QueryResult.AsyncValue {
          val collected = mutableListOf<Pair<Long, String?>>()
          while(cursor.next().await()) {
            collected += requireNotNull(cursor.getLong(0)) to cursor.getString(1)
          }
          collected.toList()
        }
      },
      parameters = 0,
    ).await()

    assertEquals(listOf(1L to "one", 2L to "two"), rows)

    driver.close()
  }

  @Test
  fun opfsDriverPersistsFileAcrossOpens() = runTest {
    val dbName = newDbName()

    AndroidxSqliteDriver(
      driver = androidxSqliteTestDriver(),
      databaseType = AndroidxSqliteDatabaseType.File(dbName),
      schema = schema,
    ).run {
      execute(null, "INSERT INTO test VALUES (1, 'persist')", 0).await()
      close()
    }

    AndroidxSqliteDriver(
      driver = androidxSqliteTestDriver(),
      databaseType = AndroidxSqliteDatabaseType.File(dbName),
      schema = schema,
    ).run {
      val value = executeQuery(
        identifier = null,
        sql = "SELECT value FROM test WHERE id = 1",
        mapper = { cursor: SqlCursor ->
          QueryResult.AsyncValue {
            if(cursor.next().await()) cursor.getString(0) else null
          }
        },
        parameters = 0,
      ).await()

      assertEquals("persist", value)
      close()
    }
  }

  // Regression test for https://github.com/eygraber/sqldelight-androidx-driver/issues/195 —
  // every web statement suspends across a worker round trip, so concurrent transactions
  // interleave; without the WebConnectionPool mutex both issue BEGIN on the single connection
  // ("cannot start a transaction within a transaction").
  @Test
  fun concurrentTransactionsDoNotInterleave() = runTest {
    val driver = AndroidxSqliteDriver(
      driver = androidxSqliteTestDriver(),
      databaseType = AndroidxSqliteDatabaseType.Memory,
      schema = schema,
    )

    val transacter = object : SuspendingTransacterImpl(driver) {}

    val inserts = 10
    coroutineScope {
      repeat(inserts) { i ->
        launch {
          transacter.transaction {
            driver.execute(null, "INSERT INTO test VALUES ($i, 'committed')", 0).await()
          }
        }
        launch {
          transacter.transaction {
            driver.execute(null, "INSERT INTO test VALUES (${i + 1000}, 'rolled-back')", 0).await()
            rollback()
          }
        }
      }
    }

    val committedAndTotal = driver.executeQuery(
      identifier = null,
      sql = "SELECT COUNT(CASE WHEN value = 'committed' THEN 1 END), COUNT(*) FROM test",
      mapper = { cursor ->
        QueryResult.AsyncValue {
          cursor.next().await()
          requireNotNull(cursor.getLong(0)) to requireNotNull(cursor.getLong(1))
        }
      },
      parameters = 0,
    ).await()

    assertEquals(inserts.toLong() to inserts.toLong(), committedAndTotal)

    driver.close()
  }

  @Test
  fun multipleReadersSingleWriterResolvesToOneConnection() = runTest {
    val driver = AndroidxSqliteDriver(
      driver = androidxSqliteTestDriver(),
      databaseType = AndroidxSqliteDatabaseType.File(newDbName()),
      schema = schema,
      configuration = AndroidxSqliteConfiguration(
        concurrencyModel = AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter(
          isWal = true,
          walCount = 3,
        ),
      ),
    )

    val transacter = object : SuspendingTransacterImpl(driver) {}

    val inserts = 10
    coroutineScope {
      repeat(inserts) { i ->
        launch {
          transacter.transaction {
            driver.execute(null, "INSERT INTO test VALUES ($i, 'committed')", 0).await()
          }
        }
        launch {
          driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM test",
            mapper = { cursor ->
              QueryResult.AsyncValue {
                cursor.next().await()
                requireNotNull(cursor.getLong(0))
              }
            },
            parameters = 0,
          ).await()
        }
      }
    }

    val count = driver.executeQuery(
      identifier = null,
      sql = "SELECT COUNT(*) FROM test",
      mapper = { cursor ->
        QueryResult.AsyncValue {
          cursor.next().await()
          requireNotNull(cursor.getLong(0))
        }
      },
      parameters = 0,
    ).await()

    assertEquals(inserts.toLong(), count)

    driver.close()
  }

  @Test
  fun cpuCacheHitOptimizedProviderIsNotAvailable() {
    val failure = assertFailsWith<UnsupportedOperationException> {
      AndroidxSqliteConcurrencyModel.CpuCacheHitOptimizedProvider
    }

    assertTrue(failure.message.orEmpty().contains("not available on web"))
  }

  @Test
  fun setJournalModeAppliesThePragma() = runTest {
    val driver = AndroidxSqliteDriver(
      driver = androidxSqliteTestDriver(),
      databaseType = AndroidxSqliteDatabaseType.File(newDbName()),
      schema = schema,
    )

    driver.execute(null, "PRAGMA journal_mode = TRUNCATE", 0).await()

    val journalMode = driver.executeQuery(
      identifier = null,
      sql = "PRAGMA journal_mode",
      mapper = { cursor ->
        QueryResult.AsyncValue {
          cursor.next().await()
          cursor.getString(0)
        }
      },
      parameters = 0,
    ).await()

    assertEquals("truncate", journalMode)

    driver.close()
  }

  @Test
  fun rolledBackTransactionDiscardsChanges() = runTest {
    val driver = AndroidxSqliteDriver(
      driver = androidxSqliteTestDriver(),
      databaseType = AndroidxSqliteDatabaseType.Memory,
      schema = schema,
    )

    val transacter = object : SuspendingTransacterImpl(driver) {}

    transacter.transaction {
      driver.execute(null, "INSERT INTO test VALUES (1, 'rolled-back')", 0).await()
      rollback()
    }

    val value = driver.executeQuery(
      identifier = null,
      sql = "SELECT value FROM test WHERE id = 1",
      mapper = { cursor ->
        QueryResult.AsyncValue {
          if(cursor.next().await()) cursor.getString(0) else null
        }
      },
      parameters = 0,
    ).await()

    assertNull(value)

    driver.close()
  }

  @Test
  fun openingAnInvalidFileNameFailsPromptly() = runTest {
    val sqliteDriver = androidxSqliteTestDriver()
    val warmUp = AndroidxSqliteDriver(
      driver = sqliteDriver,
      databaseType = AndroidxSqliteDatabaseType.Memory,
      schema = schema,
    )
    warmUp.execute(null, "SELECT 1", 0).await()

    val invalid = AndroidxSqliteDriver(
      driver = sqliteDriver,
      databaseType = AndroidxSqliteDatabaseType.File("x".repeat(600) + ".db"),
      schema = schema,
    )

    val (failure, elapsed) = withContext(Dispatchers.Default) {
      val mark = TimeSource.Monotonic.markNow()
      val failure = runCatching { invalid.execute(null, "SELECT 1", 0).await() }.exceptionOrNull()
      failure to mark.elapsedNow()
    }

    assertNotNull(failure)
    assertTrue(elapsed < 300.milliseconds, "open failed after $elapsed")

    warmUp.close()
    invalid.close()
  }
}
