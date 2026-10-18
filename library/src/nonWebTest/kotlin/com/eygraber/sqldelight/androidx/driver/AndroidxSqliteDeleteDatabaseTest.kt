package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

abstract class AndroidxSqliteDeleteDatabaseTest {
  private val schema = object : SqlSchema<QueryResult.AsyncValue<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {
      driver.execute(null, "CREATE TABLE test (id INTEGER PRIMARY KEY, value TEXT)", 0).await()
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
      vararg callbacks: AfterVersion,
    ) = QueryResult.AsyncValue {}
  }

  private val dbName = "${this::class.simpleName.orEmpty()}.${Random.nextULong().toHexString()}.db"

  private var create = 0
  private var open = 0

  private val driver = AndroidxSqliteDriver(
    driver = androidxSqliteTestDriver(),
    databaseType = AndroidxSqliteDatabaseType.File(dbName),
    schema = schema,
    onCreate = { create++ },
    onOpen = { open++ },
  )

  @AfterTest
  fun tearDown() {
    driver.close()
    closeAndroidxSqliteTestDriver()
    deleteDatabaseFiles(dbName)
  }

  @Test
  fun `deleteDatabase removes the files and the next query creates the schema again`() = runTest {
    driver.execute(null, "INSERT INTO test VALUES (1, 'a')", 0).await()
    assertTrue(fileExists(dbName))
    assertTrue(fileExists("$dbName-wal"))
    assertEquals(1, create)
    assertEquals(1, open)

    driver.deleteDatabase()

    assertFalse(fileExists(dbName))
    assertFalse(fileExists("$dbName-wal"))
    assertFalse(fileExists("$dbName-shm"))

    assertEquals(0L, count())
    assertEquals(2, create)
    assertEquals(2, open)
    assertTrue(fileExists(dbName))
  }

  @Test
  fun `deleteDatabase before the first interaction`() = runTest {
    driver.deleteDatabase()

    assertEquals(0L, count())
    assertEquals(1, create)
    assertEquals(1, open)
  }

  @Test
  fun `deleteDatabase waits for a transaction in progress`() = runTest {
    val transacter = object : SuspendingTransacterImpl(driver) {}
    driver.execute(null, "INSERT INTO test VALUES (1, 'a')", 0).await()

    val deletion = async { driver.deleteDatabase() }

    transacter.transaction {
      delay(200)
      assertFalse(deletion.isCompleted, "deleteDatabase should wait for the transaction")
      driver.execute(null, "INSERT INTO test VALUES (2, 'b')", 0).await()
    }

    deletion.await()
    assertEquals(0L, count())
  }

  @Test
  fun `deleteDatabase inside a transaction throws`() = runTest {
    val transacter = object : SuspendingTransacterImpl(driver) {}

    transacter.transaction {
      assertFailsWith<IllegalStateException> { driver.deleteDatabase() }
    }
  }

  private suspend fun count(): Long = driver.executeQuery(
    identifier = null,
    sql = "SELECT count(*) FROM test",
    mapper = { cursor ->
      QueryResult.AsyncValue {
        cursor.next().await()
        requireNotNull(cursor.getLong(0))
      }
    },
    parameters = 0,
  ).await()
}
