package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class AndroidxSqliteCallbackTest {
  private val schema = object : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
      driver.execute(
        0,
        """
              |CREATE TABLE test (
              |  id INTEGER PRIMARY KEY,
              |  value TEXT
              |);
        """.trimMargin(),
        0,
      )
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
      )
      return QueryResult.Unit
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
      vararg callbacks: AfterVersion,
    ) = QueryResult.Unit
  }

  private val schemaWithUpdate = object : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 2

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
      driver.execute(
        0,
        """
              |CREATE TABLE test (
              |  id INTEGER PRIMARY KEY,
              |  value TEXT
              |);
        """.trimMargin(),
        0,
      )
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
      )
      return QueryResult.Unit
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
      vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
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
        )
      }
      return QueryResult.Unit
    }
  }

  private val dbName = "com.eygraber.sqldelight.androidx.driver.test.db"

  private fun setupDatabase(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    onConfigure: ConfigurableDatabase.() -> Unit,
    onCreate: SqlDriver.() -> Unit,
    onUpdate: SqlDriver.(Long, Long) -> Unit,
    onOpen: SqlDriver.() -> Unit,
  ): SqlDriver = AndroidxSqliteDriver(
    driver = androidxSqliteTestDriver(),
    databaseType = AndroidxSqliteDatabaseType.File(dbName),
    schema = schema,
    onConfigure = onConfigure,
    onCreate = onCreate,
    onUpdate = onUpdate,
    onOpen = onOpen,
  )

  @BeforeTest
  fun clearNamedDb() {
    deleteFile(dbName)
    deleteFile("$dbName-shm")
    deleteFile("$dbName-wal")
  }

  @AfterTest
  fun clearNamedDbPostTests() {
    deleteFile(dbName)
    deleteFile("$dbName-shm")
    deleteFile("$dbName-wal")
  }

  @Test
  fun `create and open callbacks are invoked once when opening a new database`() {
    var configure = 0
    var create = 0
    var update = 0
    var open = 0

    val driver = setupDatabase(
      schema = schema,
      onConfigure = { configure++ },
      onCreate = { create++ },
      onUpdate = { _, _ -> update++ },
      onOpen = { open++ },
    )

    assertEquals(0, configure)
    assertEquals(0, create)
    assertEquals(0, update)
    assertEquals(0, open)

    driver.execute(null, "PRAGMA user_version", 0)

    assertEquals(1, configure)
    assertEquals(1, create)
    assertEquals(0, update)
    assertEquals(1, open)
  }

  @Test
  fun `create is invoked once and open is invoked twice when opening a new database closing it and then opening it again`() {
    var configure = 0
    var create = 0
    var update = 0
    var open = 0

    var driver = setupDatabase(
      schema = schema,
      onConfigure = { configure++ },
      onCreate = { create++ },
      onUpdate = { _, _ -> update++ },
      onOpen = { open++ },
    )

    assertEquals(0, configure)
    assertEquals(0, create)
    assertEquals(0, update)
    assertEquals(0, open)

    driver.execute(null, "PRAGMA user_version", 0)

    assertEquals(1, configure)
    assertEquals(1, create)
    assertEquals(0, update)
    assertEquals(1, open)

    driver.close()

    driver = setupDatabase(
      schema = schema,
      onConfigure = { configure++ },
      onCreate = { create++ },
      onUpdate = { _, _ -> update++ },
      onOpen = { open++ },
    )

    assertEquals(1, configure)
    assertEquals(1, create)
    assertEquals(0, update)
    assertEquals(1, open)

    driver.execute(null, "PRAGMA user_version", 0)

    assertEquals(2, configure)
    assertEquals(1, create)
    assertEquals(0, update)
    assertEquals(2, open)
  }

  @Test
  fun `create is invoked once and open is invoked twice and update is invoked once when opening a new database closing it and then opening it again with a new version`() {
    var configure = 0
    var create = 0
    var update = 0
    var open = 0

    var driver = setupDatabase(
      schema = schema,
      onConfigure = { configure++ },
      onCreate = { create++ },
      onUpdate = { _, _ -> update++ },
      onOpen = { open++ },
    )

    assertEquals(0, configure)
    assertEquals(0, create)
    assertEquals(0, update)
    assertEquals(0, open)

    driver.execute(null, "PRAGMA user_version", 0)

    assertEquals(1, configure)
    assertEquals(1, create)
    assertEquals(0, update)
    assertEquals(1, open)

    driver.close()

    var fromVersion = -1L
    var toVersion = -1L
    driver = setupDatabase(
      schema = schemaWithUpdate,
      onConfigure = { configure++ },
      onCreate = { create++ },
      onUpdate = { from, to ->
        fromVersion = from
        toVersion = to
        update++
      },
      onOpen = { open++ },
    )

    assertEquals(1, configure)
    assertEquals(1, create)
    assertEquals(0, update)
    assertEquals(-1, fromVersion)
    assertEquals(-1, toVersion)
    assertEquals(1, open)

    driver.execute(null, "PRAGMA user_version", 0)

    assertEquals(2, configure)
    assertEquals(1, create)
    assertEquals(1, update)
    assertEquals(1, fromVersion)
    assertEquals(2, toVersion)
    assertEquals(2, open)
  }
}
