package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class AndroidxSqliteCallbackTest {
  private val schema = object : SqlSchema<QueryResult.AsyncValue<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {
      driver.execute(
        0,
        """
              |CREATE TABLE test (
              |  id INTEGER PRIMARY KEY,
              |  value TEXT
              |);
        """.trimMargin(),
        0,
      ).await()
      driver.execute(
        1,
        """
              |CREATE TABLE nullability_test (
              |  id INTEGER PRIMARY KEY,
              |  integer_value INTEGER,
              |  text_value TEXT,
              |  blob_value BLOB,
              |  real_value REAL
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

  private val schemaWithUpdate = object : SqlSchema<QueryResult.AsyncValue<Unit>> {
    override val version: Long = 2

    override fun create(driver: SqlDriver): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {
      driver.execute(
        0,
        """
              |CREATE TABLE test (
              |  id INTEGER PRIMARY KEY,
              |  value TEXT
              |);
        """.trimMargin(),
        0,
      ).await()
      driver.execute(
        1,
        """
              |CREATE TABLE nullability_test (
              |  id INTEGER PRIMARY KEY,
              |  integer_value INTEGER,
              |  text_value TEXT,
              |  blob_value BLOB,
              |  real_value REAL
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
    ): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {
      if(newVersion == 2L) {
        driver.execute(
          0,
          """
              |CREATE TABLE test2 (
              |  id INTEGER PRIMARY KEY,
              |  value TEXT
              |);
          """.trimMargin(),
          0,
        ).await()
      }
    }
  }

  private inline fun withDatabase(
    schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
    dbName: String,
    noinline onConfigure: AndroidxSqliteConfigurableDriver.() -> Unit,
    noinline onCreate: SqlDriver.() -> Unit,
    noinline onUpdate: SqlDriver.(Long, Long) -> Unit,
    noinline onOpen: SqlDriver.() -> Unit,
    deleteDbBeforeRun: Boolean = true,
    deleteDbAfterRun: Boolean = true,
    test: SqlDriver.() -> Unit,
  ) {
    val fullDbName = "${this::class.qualifiedName}.$dbName.db"

    if(deleteDbBeforeRun) {
      deleteFile(fullDbName)
      deleteFile("$fullDbName-shm")
      deleteFile("$fullDbName-wal")
    }

    val result = runCatching {
      AndroidxSqliteDriver(
        driver = androidxSqliteTestDriver(),
        databaseType = AndroidxSqliteDatabaseType.File(fullDbName),
        schema = schema,
        onConfigure = onConfigure,
        onCreate = onCreate,
        onUpdate = onUpdate,
        onOpen = onOpen,
      ).test()
    }

    if(deleteDbAfterRun || result.isFailure) {
      deleteFile(fullDbName)
      deleteFile("$fullDbName-shm")
      deleteFile("$fullDbName-wal")
    }

    if(result.isFailure) result.getOrThrow()
  }

  @Test
  fun `create and open callbacks are invoked once when opening a new database`() = runTest {
    var configure = 0
    var create = 0
    var update = 0
    var open = 0

    withDatabase(
      schema = schema,
      dbName = Random.nextULong().toHexString(),
      onConfigure = { configure++ },
      onCreate = { create++ },
      onUpdate = { _, _ -> update++ },
      onOpen = { open++ },
    ) {
      assertEquals(0, configure)
      assertEquals(0, create)
      assertEquals(0, update)
      assertEquals(0, open)

      execute(null, "PRAGMA user_version", 0).await()

      assertEquals(1, configure)
      assertEquals(1, create)
      assertEquals(0, update)
      assertEquals(1, open)
    }
  }

  @Test
  fun `create is invoked once and open is invoked twice when opening a new database closing it and then opening it again`() =
    runTest {
      var configure = 0
      var create = 0
      var update = 0
      var open = 0

      val dbName = Random.nextULong().toHexString()

      withDatabase(
        schema = schema,
        dbName = dbName,
        onConfigure = { configure++ },
        onCreate = { create++ },
        onUpdate = { _, _ -> update++ },
        onOpen = { open++ },
        deleteDbAfterRun = false,
      ) {
        assertEquals(0, configure)
        assertEquals(0, create)
        assertEquals(0, update)
        assertEquals(0, open)

        execute(null, "PRAGMA user_version", 0).await()

        assertEquals(1, configure)
        assertEquals(1, create)
        assertEquals(0, update)
        assertEquals(1, open)

        close()
      }

      withDatabase(
        schema = schema,
        dbName = dbName,
        onConfigure = { configure++ },
        onCreate = { create++ },
        onUpdate = { _, _ -> update++ },
        onOpen = { open++ },
        deleteDbBeforeRun = false,
      ) {
        assertEquals(1, configure)
        assertEquals(1, create)
        assertEquals(0, update)
        assertEquals(1, open)

        execute(null, "PRAGMA user_version", 0).await()

        assertEquals(2, configure)
        assertEquals(1, create)
        assertEquals(0, update)
        assertEquals(2, open)
      }
    }

  @Test
  fun `create is invoked once and open is invoked twice and update is invoked once when opening a new database closing it and then opening it again with a new version`() =
    runTest {
      var configure = 0
      var create = 0
      var update = 0
      var open = 0

      val dbName = Random.nextULong().toHexString()

      withDatabase(
        schema = schema,
        dbName = dbName,
        onConfigure = { configure++ },
        onCreate = { create++ },
        onUpdate = { _, _ -> update++ },
        onOpen = { open++ },
        deleteDbAfterRun = false,
      ) {
        assertEquals(0, configure)
        assertEquals(0, create)
        assertEquals(0, update)
        assertEquals(0, open)

        execute(null, "PRAGMA user_version", 0).await()

        assertEquals(1, configure)
        assertEquals(1, create)
        assertEquals(0, update)
        assertEquals(1, open)

        close()
      }

      var fromVersion = -1L
      var toVersion = -1L
      withDatabase(
        schema = schemaWithUpdate,
        dbName = dbName,
        onConfigure = { configure++ },
        onCreate = { create++ },
        onUpdate = { from, to ->
          fromVersion = from
          toVersion = to
          update++
        },
        onOpen = { open++ },
        deleteDbBeforeRun = false,
      ) {
        assertEquals(1, configure)
        assertEquals(1, create)
        assertEquals(0, update)
        assertEquals(-1, fromVersion)
        assertEquals(-1, toVersion)
        assertEquals(1, open)

        execute(null, "PRAGMA user_version", 0).await()

        assertEquals(2, configure)
        assertEquals(1, create)
        assertEquals(1, update)
        assertEquals(1, fromVersion)
        assertEquals(2, toVersion)
        assertEquals(2, open)
      }
    }
}
