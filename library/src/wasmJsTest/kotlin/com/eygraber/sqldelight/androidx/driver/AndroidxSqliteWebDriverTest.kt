package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

  private val createdFiles = mutableSetOf<String>()

  @AfterTest
  fun cleanup() = runTest {
    terminateTestWorkers()
    createdFiles.toList().forEach { deleteOpfsFile(it) }
    createdFiles.clear()
  }

  private fun newDbName(): String {
    val name = "AndroidxSqliteWebDriverTest-${Random.nextULong().toHexString()}.db"
    createdFiles += name
    return name
  }

  @Test
  fun memoryDriverExecutesAndQueries() = runTest {
    val driver = AndroidxSqliteDriver(
      driver = webTestSqliteDriver(),
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
      driver = webTestSqliteDriver(),
      databaseType = AndroidxSqliteDatabaseType.File(dbName),
      schema = schema,
    ).run {
      execute(null, "INSERT INTO test VALUES (1, 'persist')", 0).await()
      close()
    }

    AndroidxSqliteDriver(
      driver = webTestSqliteDriver(),
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

  @Test
  fun rolledBackTransactionDiscardsChanges() = runTest {
    val driver = AndroidxSqliteDriver(
      driver = webTestSqliteDriver(),
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
}
