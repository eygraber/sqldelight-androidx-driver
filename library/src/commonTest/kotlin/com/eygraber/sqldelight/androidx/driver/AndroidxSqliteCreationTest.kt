package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

abstract class AndroidxSqliteCreationTest {
  private fun getSchema(
    additionalCreationSteps: suspend SqlDriver.() -> Unit = {},
  ) = object : SqlSchema<QueryResult.AsyncValue<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {
      driver.execute(
        null,
        """
        |CREATE TABLE user (
        |  id INTEGER PRIMARY KEY NOT NULL,
        |  name TEXT NOT NULL
        |);
        """.trimMargin(),
        0,
      ).await()

      driver.execute(
        null,
        """
        |CREATE TABLE post (
        |  id INTEGER PRIMARY KEY NOT NULL,
        |  userId INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE
        |);
        """.trimMargin(),
        0,
      ).await()

      driver.execute(
        null,
        """
        |INSERT INTO user VALUES(1, 'bob'), (2, 'alice');
        """.trimMargin(),
        0,
      ).await()

      driver.execute(
        null,
        """
        |INSERT INTO post VALUES(1, 1), (2, 1), (3, 2), (4, 2);
        """.trimMargin(),
        0,
      ).await()

      driver.execute(
        null,
        """
        |CREATE TABLE newUser (
        |  id INTEGER PRIMARY KEY NOT NULL,
        |  name TEXT NOT NULL DEFAULT 'No name given'
        |);
        """.trimMargin(),
        0,
      ).await()

      driver.execute(
        null,
        """
        |INSERT INTO newUser(id, name) SELECT id, name FROM user;
        """.trimMargin(),
        0,
      ).await()

      driver.execute(
        null,
        """
        |DROP TABLE user;
        """.trimMargin(),
        0,
      ).await()

      driver.execute(
        null,
        """
        |ALTER TABLE newUser RENAME TO user;
        """.trimMargin(),
        0,
      ).await()

      driver.additionalCreationSteps()
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
      vararg callbacks: AfterVersion,
    ) = QueryResult.AsyncValue {}
  }

  private inline fun withDatabase(
    schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
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
        connectionFactory = androidxSqliteTestConnectionFactory(),
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
  fun `creations don't cause ON DELETE CASCADE to trigger`() = runTest {
    val schema = getSchema()
    val dbName = Random.nextULong().toHexString()

    withDatabase(
      schema = schema,
      dbName = dbName,
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
      onConfigure = {},
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
      ).await()

      assertEquals(4, result)
    }
  }

  @Test
  fun `foreign keys are disabled during creation`() = runTest {
    val schema = getSchema {
      assertTrue {
        executeQuery(
          identifier = null,
          sql = "PRAGMA foreign_keys;",
          mapper = { cursor ->
            QueryResult.Value(
              when {
                cursor.next().value -> cursor.getLong(0)
                else -> 0L
              },
            )
          },
          parameters = 0,
        ).await() == 0L
      }
    }

    val dbName = Random.nextULong().toHexString()

    withDatabase(
      schema = schema,
      dbName = dbName,
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
      onConfigure = {},
    ) {
      execute(null, "PRAGMA user_version;", 0, null).await()
    }
  }

  @Test
  fun `foreign keys are re-enabled after successful creation`() = runTest {
    val schema = getSchema()
    val dbName = Random.nextULong().toHexString()

    withDatabase(
      schema = schema,
      dbName = dbName,
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
      onConfigure = {},
    ) {
      assertTrue {
        executeQuery(
          identifier = null,
          sql = "PRAGMA foreign_keys;",
          mapper = { cursor ->
            QueryResult.Value(
              when {
                cursor.next().value -> cursor.getLong(0)
                else -> 0L
              },
            )
          },
          parameters = 0,
        ).await() == 1L
      }
    }
  }

  @Test
  fun `foreign key constraint violations during creation fail after the creation`() = runTest {
    val configuration = AndroidxSqliteConfiguration(
      isForeignKeyConstraintsEnabled = true,
      isForeignKeyConstraintsCheckedAfterCreateOrUpdate = true,
    )

    val schema = getSchema {
      execute(
        null,
        """
        |DELETE FROM user WHERE id = 1;
        """.trimMargin(),
        0,
      ).await()
    }
    val dbName = Random.nextULong().toHexString()

    val exception = assertFailsWith<AndroidxSqliteDriver.ForeignKeyConstraintCheckException> {
      withDatabase(
        schema = schema,
        dbName = dbName,
        onCreate = {},
        onUpdate = { _, _ -> },
        onOpen = {},
        onConfigure = {},
        configuration = configuration,
      ) {
        execute(null, "PRAGMA user_version;", 0, null).await()
      }
    }

    assertEquals(
      expected = exception.message,
      actual = """
        |The following foreign key constraints are violated:
        |
        |ForeignKeyConstraintViolation:
        |  Constraint index: 0
        |  Referencing table: post
        |  Referencing rowId: 1
        |  Referenced table: user
        |
        |ForeignKeyConstraintViolation:
        |  Constraint index: 0
        |  Referencing table: post
        |  Referencing rowId: 2
        |  Referenced table: user
      """.trimMargin(),
    )

    assertContentEquals(
      expected = listOf(
        AndroidxSqliteDriver.ForeignKeyConstraintViolation(
          referencingTable = "post",
          referencingRowId = 1,
          referencedTable = "user",
          referencingConstraintIndex = 0,
        ),
        AndroidxSqliteDriver.ForeignKeyConstraintViolation(
          referencingTable = "post",
          referencingRowId = 2,
          referencedTable = "user",
          referencingConstraintIndex = 0,
        ),
      ),
      actual = exception.violations,
    )
  }

  @Test
  fun `foreign key constraint violations during creation respects the default max amount of reported violations`() =
    runTest {
      val configuration = AndroidxSqliteConfiguration(
        isForeignKeyConstraintsEnabled = true,
        isForeignKeyConstraintsCheckedAfterCreateOrUpdate = true,
      )

      val schema = getSchema {
        val insertedValues = buildString {
          repeat(configuration.maxMigrationForeignKeyConstraintViolationsToReport + 1) {
            append("($it, 1),")
          }
        }.removeSuffix(",")

        // remove the values inserted previously in the schema creation for a cleaner test
        execute(
          null,
          "DELETE FROM post",
          0,
        ).await()

        execute(
          null,
          "INSERT INTO post VALUES $insertedValues",
          0,
        ).await()

        execute(
          null,
          """
        |DELETE FROM user WHERE id = 1;
          """.trimMargin(),
          0,
        ).await()
      }
      val dbName = Random.nextULong().toHexString()

      val messageViolations = List(5) { id ->
        """
      |ForeignKeyConstraintViolation:
      |  Constraint index: 0
      |  Referencing table: post
      |  Referencing rowId: $id
      |  Referenced table: user
        """.trimMargin()
      }.joinToString(separator = "\n\n")

      val exception = assertFailsWith<AndroidxSqliteDriver.ForeignKeyConstraintCheckException> {
        withDatabase(
          schema = schema,
          dbName = dbName,
          onCreate = {},
          onUpdate = { _, _ -> },
          onOpen = {},
          onConfigure = {},
          configuration = configuration,
        ) {
          execute(null, "PRAGMA user_version;", 0, null).await()
        }
      }

      val expectedNotShown = configuration.maxMigrationForeignKeyConstraintViolationsToReport - 5

      assertEquals(
        expected = exception.message,
        actual = """
               |The following foreign key constraints are violated ($expectedNotShown not shown):
               |
               |$messageViolations
        """.trimMargin(),
      )

      assertContentEquals(
        expected = List(configuration.maxMigrationForeignKeyConstraintViolationsToReport) { id ->
          AndroidxSqliteDriver.ForeignKeyConstraintViolation(
            referencingTable = "post",
            referencingRowId = id,
            referencedTable = "user",
            referencingConstraintIndex = 0,
          )
        },
        actual = exception.violations,
      )
    }

  @Test
  fun `foreign key constraint violations during creation respects the max amount of reported violations`() = runTest {
    val configuration = AndroidxSqliteConfiguration(
      isForeignKeyConstraintsEnabled = true,
      isForeignKeyConstraintsCheckedAfterCreateOrUpdate = true,
      maxMigrationForeignKeyConstraintViolationsToReport = 1,
    )

    val schema = getSchema {
      execute(
        null,
        """
        |DELETE FROM user WHERE id = 1;
        """.trimMargin(),
        0,
      ).await()
    }
    val dbName = Random.nextULong().toHexString()

    val exception = assertFailsWith<AndroidxSqliteDriver.ForeignKeyConstraintCheckException> {
      withDatabase(
        schema = schema,
        dbName = dbName,
        onCreate = {},
        onUpdate = { _, _ -> },
        onOpen = {},
        onConfigure = {},
        configuration = configuration,
      ) {
        execute(null, "PRAGMA user_version;", 0, null).await()
      }
    }

    assertEquals(
      expected = exception.message,
      actual = """
        |The following foreign key constraints are violated:
        |
        |ForeignKeyConstraintViolation:
        |  Constraint index: 0
        |  Referencing table: post
        |  Referencing rowId: 1
        |  Referenced table: user
      """.trimMargin(),
    )

    assertContentEquals(
      expected = listOf(
        AndroidxSqliteDriver.ForeignKeyConstraintViolation(
          referencingTable = "post",
          referencingRowId = 1,
          referencedTable = "user",
          referencingConstraintIndex = 0,
        ),
      ),
      actual = exception.violations,
    )
  }

  @Test
  fun `foreign key constraint violations during creation don't fail after the migration if the flag is false`() =
    runTest {
      val schema = getSchema {
        execute(
          null,
          """
        |DELETE FROM user WHERE id = 1;
          """.trimMargin(),
          0,
        ).await()
      }
      val dbName = Random.nextULong().toHexString()

      // doesn't fail
      withDatabase(
        schema = schema,
        dbName = dbName,
        onCreate = {},
        onUpdate = { _, _ -> },
        onOpen = {},
        onConfigure = {},
        configuration = AndroidxSqliteConfiguration(
          isForeignKeyConstraintsEnabled = true,
          isForeignKeyConstraintsCheckedAfterCreateOrUpdate = false,
        ),
      ) {
        execute(null, "PRAGMA user_version;", 0, null).await()
      }
    }

  @Test
  fun `exceptions thrown during creation are propagated to the caller`() = runTest {
    val schema = getSchema {
      throw RuntimeException("Test")
    }
    val dbName = Random.nextULong().toHexString()

    withDatabase(
      schema = schema,
      dbName = dbName,
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
      onConfigure = {},
      configuration = AndroidxSqliteConfiguration(
        isForeignKeyConstraintsEnabled = true,
        isForeignKeyConstraintsCheckedAfterCreateOrUpdate = false,
      ),
    ) {
      val message = assertFailsWith<RuntimeException> {
        execute(null, "PRAGMA user_version;", 0, null).await()
      }.message

      assertEquals("Test", message)
    }
  }

  @Test
  fun `future queries throw a propagated exception after an exception is thrown during creation`() = runTest {
    val schema = getSchema {
      throw RuntimeException("Test")
    }
    val dbName = Random.nextULong().toHexString()

    withDatabase(
      schema = schema,
      dbName = dbName,
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
      onConfigure = {},
      configuration = AndroidxSqliteConfiguration(
        isForeignKeyConstraintsEnabled = true,
        isForeignKeyConstraintsCheckedAfterCreateOrUpdate = false,
      ),
    ) {
      assertFailsWith<RuntimeException> {
        execute(null, "PRAGMA user_version;", 0, null).await()
      }

      assertFailsWith<RuntimeException> {
        executeQuery(
          identifier = null,
          sql = "PRAGMA foreign_keys;",
          mapper = { QueryResult.Unit },
          parameters = 0,
        ).await()
      }

      assertFailsWith<RuntimeException> {
        execute(
          identifier = null,
          sql = "PRAGMA foreign_keys = OFF;",
          parameters = 0,
        ).await()
      }
    }
  }
}
