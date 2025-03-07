package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class AndroidxSqliteConcurrencyTest {
  private val schema = object : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
      driver.execute(
        0,
        """
              |CREATE TABLE test (
              |  id INTEGER PRIMARY KEY NOT NULL,
              |  value TEXT DEFAULT NULL
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

  private val dbName = "com.eygraber.sqldelight.androidx.driver.test.db"

  private fun setupDatabase(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    onCreate: SqlDriver.() -> Unit,
    onUpdate: SqlDriver.(Long, Long) -> Unit,
    onOpen: SqlDriver.() -> Unit,
    onConfigure: ConfigurableDatabase.() -> Unit = { setJournalMode(SqliteJournalMode.WAL) },
  ): SqlDriver = AndroidxSqliteDriver(
    createConnection = androidxSqliteTestCreateConnection(),
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
    clearNamedDb()
  }

  @Test
  fun `many concurrent transactions are handled in order`() = runTest {
    val driver = setupDatabase(
      schema = schema,
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
    )
    val transacter = object : TransacterImpl(driver) {}

    val jobs = mutableListOf<Job>()
    repeat(200) { a ->
      jobs += launch(IoDispatcher) {
        if(a.mod(2) == 0) {
          transacter.transaction {
            val lastId = driver.executeQuery(
              identifier = null,
              sql = "SELECT id FROM test ORDER BY id DESC LIMIT 1;",
              mapper = { cursor ->
                if(cursor.next().value) {
                  QueryResult.Value(cursor.getLong(0) ?: -1L)
                } else {
                  QueryResult.Value(-1L)
                }
              },
              parameters = 0,
              binders = null,
            ).value
            driver.execute(null, "INSERT INTO test(id) VALUES (${lastId + 1});", 0, null)
          }
        }
        else {
          driver.execute(null, "UPDATE test SET value = 'test' WHERE id = 0;", 0, null)
        }
      }
    }

    jobs.joinAll()

    val lastId = driver.executeQuery(
      identifier = null,
      sql = "SELECT id FROM test ORDER BY id DESC LIMIT 1;",
      mapper = { cursor ->
        if(cursor.next().value) {
          QueryResult.Value(cursor.getLong(0) ?: -1L)
        } else {
          QueryResult.Value(-1L)
        }
      },
      parameters = 0,
      binders = null,
    ).value

    assertEquals(99, lastId)
  }

  @Test
  fun `callbacks are only invoked once despite many concurrent transactions`() = runTest {
    var create = 0
    var update = 0
    var open = 0
    var configure = 0

    val driver = setupDatabase(
      schema = schema,
      onCreate = { create++ },
      onUpdate = { _, _ -> update++ },
      onOpen = { open++ },
      onConfigure = { configure++ },
    )
    val jobs = mutableListOf<Job>()
    repeat(100) {
      jobs += launch(IoDispatcher) {
        driver.execute(null, "PRAGMA journal_mode = WAL;", 0, null)
      }
    }

    jobs.joinAll()

    assertEquals(1, create)
    assertEquals(0, update)
    assertEquals(1, open)
    assertEquals(1, configure)
  }
}
