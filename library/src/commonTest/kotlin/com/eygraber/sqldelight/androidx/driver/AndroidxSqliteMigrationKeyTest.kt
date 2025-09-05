package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

abstract class AndroidxSqliteMigrationKeyTest {
  private fun getSchema(
    additionalMigrationSteps: SqlDriver.() -> Unit = {},
  ) = object : SqlSchema<QueryResult.Value<Unit>> {
    override var version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
      driver.execute(
        null,
        """
        |CREATE TABLE user (
        |  id INTEGER PRIMARY KEY NOT NULL,
        |  name TEXT NOT NULL
        |);
        """.trimMargin(),
        0,
      )

      driver.execute(
        null,
        """
        |CREATE TABLE post (
        |  id INTEGER PRIMARY KEY NOT NULL,
        |  userId INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE
        |);
        """.trimMargin(),
        0,
      )

      driver.execute(
        null,
        """
        |INSERT INTO user VALUES(1, 'bob'), (2, 'alice');
        """.trimMargin(),
        0,
      )

      driver.execute(
        null,
        """
        |INSERT INTO post VALUES(1, 1), (2, 1), (3, 2), (4, 2);
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
      driver.execute(
        null,
        """
        |CREATE TABLE newUser (
        |  id INTEGER PRIMARY KEY NOT NULL,
        |  name TEXT NOT NULL DEFAULT 'No name given'
        |);
        """.trimMargin(),
        0,
      )

      driver.execute(
        null,
        """
        |INSERT INTO newUser(id, name) SELECT id, name FROM user;
        """.trimMargin(),
        0,
      )

      driver.execute(
        null,
        """
        |DROP TABLE user;
        """.trimMargin(),
        0,
      )

      driver.execute(
        null,
        """
        |ALTER TABLE newUser RENAME TO user;
        """.trimMargin(),
        0,
      )

      driver.additionalMigrationSteps()

      return QueryResult.Unit
    }
  }

  private inline fun withDatabase(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    dbName: String,
    noinline onCreate: SqlDriver.() -> Unit,
    noinline onUpdate: SqlDriver.(Long, Long) -> Unit,
    noinline onOpen: SqlDriver.() -> Unit,
    noinline onConfigure: AndroidxSqliteConfigurableDriver.() -> Unit = { setJournalMode(SqliteJournalMode.WAL) },
    deleteDbBeforeRun: Boolean = true,
    deleteDbAfterRun: Boolean = true,
    configuration: AndroidxSqliteConfiguration = AndroidxSqliteConfiguration(
      isForeignKeyConstraintsEnabled = true,
    ),
    migrateEmptySchema: Boolean = false,
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
        createConnection = androidxSqliteTestCreateConnection(),
        databaseType = createDatabaseType(fullDbName),
        schema = schema,
        configuration = configuration,
        onConfigure = onConfigure,
        onCreate = onCreate,
        onUpdate = onUpdate,
        onOpen = onOpen,
        migrateEmptySchema = migrateEmptySchema,
      ).apply {
        test()
        close()
      }
    }

    if(deleteDbAfterRun || result.isFailure) {
      deleteFile(fullDbName)
      deleteFile("$fullDbName-shm")
      deleteFile("$fullDbName-wal")
    }

    if(result.isFailure) result.getOrThrow()
  }

  protected open fun createDatabaseType(fullDbName: String): AndroidxSqliteDatabaseType =
    AndroidxSqliteDatabaseType.File(fullDbName)

  @Test
  fun `migrations don't cause ON DELETE CASCADE to trigger`() {
    val schema = getSchema()
    val dbName = Random.nextULong().toHexString()

    // trigger creation
    withDatabase(
      schema = schema,
      dbName = dbName,
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
      onConfigure = {},
      deleteDbAfterRun = false,
    ) {
      val result = executeQuery(
        identifier = null,
        sql = "SELECT COUNT(*) FROM post",
        mapper = { cursor ->
          if(cursor.next().value) {
            QueryResult.Value(cursor.getLong(0))
          } else {
            QueryResult.Value(null)
          }
        },
        parameters = 0,
      )

      assertEquals(4, result.value)
    }

    schema.version++
    withDatabase(
      schema = schema,
      dbName = dbName,
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
      onConfigure = {},
      deleteDbBeforeRun = false,
    ) {
      val result = executeQuery(
        identifier = null,
        sql = "SELECT COUNT(*) FROM post",
        mapper = { cursor ->
          if(cursor.next().value) {
            QueryResult.Value(cursor.getLong(0))
          } else {
            QueryResult.Value(null)
          }
        },
        parameters = 0,
      )

      assertEquals(4, result.value)
    }
  }

  @Test
  fun `foreign key constraint violations during migrations fail after the migration`() {
    val schema = getSchema {
      execute(
        null,
        """
        |DELETE FROM user WHERE id = 1;
        """.trimMargin(),
        0,
      )
    }
    val dbName = Random.nextULong().toHexString()

    // trigger creation
    withDatabase(
      schema = schema,
      dbName = dbName,
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
      onConfigure = {},
      deleteDbAfterRun = false,
    ) {
      execute(null, "PRAGMA user_version;", 0, null)
    }

    schema.version++

    assertFailsWith<AndroidxSqliteDriver.ForeignKeyConstraintCheckException>(
      message = """
        |The following foreign key constraints are violated:
        |
        |Constraint index: 0 
        |Referencing table: post
        |Referencing rowId: 1
        |Referenced table: user
        |
        |Constraint index: 0 
        |Referencing table: post
        |Referencing rowId: 2
        |Referenced table: user
      """.trimMargin(),
    ) {
      withDatabase(
        schema = schema,
        dbName = dbName,
        onCreate = {},
        onUpdate = { _, _ -> },
        onOpen = {},
        onConfigure = {},
        deleteDbBeforeRun = false,
      ) {
        execute(null, "PRAGMA user_version;", 0, null)
      }
    }
  }

  @Test
  fun `foreign key constraint violations during migrations don't fail after the migration if the flag is false`() {
    val schema = getSchema {
      execute(
        null,
        """
        |DELETE FROM user WHERE id = 1;
        """.trimMargin(),
        0,
      )
    }
    val dbName = Random.nextULong().toHexString()

    // trigger creation
    withDatabase(
      schema = schema,
      dbName = dbName,
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
      onConfigure = {},
      deleteDbAfterRun = false,
    ) {
      execute(null, "PRAGMA user_version;", 0, null)
    }

    schema.version++

    // doesn't fail
    withDatabase(
      schema = schema,
      dbName = dbName,
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
      onConfigure = {},
      deleteDbBeforeRun = false,
      configuration = AndroidxSqliteConfiguration(
        isForeignKeyConstraintsEnabled = true,
        isForeignKeyConstraintsCheckedAfterCreateOrUpdate = false,
      ),
    ) {
      execute(null, "PRAGMA user_version;", 0, null)
    }
  }

  @Test
  fun `create is run if migrateEmptySchema is false`() {
    val schema = object : SqlSchema<QueryResult.Value<Unit>> {
      override var version: Long = 1

      override fun create(driver: SqlDriver) = QueryResult.Unit

      override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
      ) = QueryResult.Unit
    }

    val dbName = Random.nextULong().toHexString()

    var create = 0
    var update = 0

    withDatabase(
      schema = schema,
      dbName = dbName,
      onCreate = { create++ },
      onUpdate = { _, _ -> update++ },
      onOpen = {},
      onConfigure = {},
      migrateEmptySchema = false,
    ) {
      execute(null, "PRAGMA user_version;", 0, null)
    }

    assertEquals(1, create)
    assertEquals(0, update)
  }

  @Test
  fun `create is not run if migrateEmptySchema is true`() {
    val schema = object : SqlSchema<QueryResult.Value<Unit>> {
      override var version: Long = 1

      override fun create(driver: SqlDriver) = QueryResult.Unit

      override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
      ) = QueryResult.Unit
    }

    val dbName = Random.nextULong().toHexString()

    var create = 0
    var update = 0

    withDatabase(
      schema = schema,
      dbName = dbName,
      onCreate = { create++ },
      onUpdate = { _, _ -> update++ },
      onOpen = {},
      onConfigure = {},
      migrateEmptySchema = true,
    ) {
      execute(null, "PRAGMA user_version;", 0, null)
    }

    assertEquals(0, create)
    assertEquals(1, update)
  }
}
