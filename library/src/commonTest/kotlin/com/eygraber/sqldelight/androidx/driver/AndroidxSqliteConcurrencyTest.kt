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
import kotlin.random.Random
import kotlin.random.nextULong
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

  private inline fun withDatabase(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    dbName: String,
    noinline onCreate: SqlDriver.() -> Unit,
    noinline onUpdate: SqlDriver.(Long, Long) -> Unit,
    noinline onOpen: SqlDriver.() -> Unit,
    noinline onConfigure: AndroidxSqliteConfigurableDriver.() -> Unit = { setJournalMode(SqliteJournalMode.WAL) },
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
        createConnection = androidxSqliteTestCreateConnection(),
        databaseType = createDatabaseType(fullDbName),
        schema = schema,
        onConfigure = onConfigure,
        onCreate = onCreate,
        onUpdate = onUpdate,
        onOpen = onOpen,
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
  fun `many concurrent transactions are handled in order`() = runTest {
    withDatabase(
      schema = schema,
      dbName = Random.nextULong().toHexString(),
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
    ) {
      val transacter = object : TransacterImpl(this) {}

      val jobs = mutableListOf<Job>()
      repeat(200) { a ->
        jobs += launch(IoDispatcher) {
          if(a.mod(2) == 0) {
            transacter.transaction {
              val lastId = executeQuery(
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
              execute(null, "INSERT INTO test(id) VALUES (${lastId + 1});", 0, null)
            }
          } else {
            execute(null, "UPDATE test SET value = 'test' WHERE id = 0;", 0, null)
          }
        }
      }

      jobs.joinAll()

      val lastId = executeQuery(
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
  }

  @Test
  fun `callbacks are only invoked once despite many concurrent transactions`() = runTest {
    var create = 0
    var update = 0
    var open = 0
    var configure = 0

    withDatabase(
      schema = schema,
      dbName = Random.nextULong().toHexString(),
      onCreate = { create++ },
      onUpdate = { _, _ -> update++ },
      onOpen = { open++ },
      onConfigure = { configure++ },
    ) {
      val jobs = mutableListOf<Job>()
      repeat(100) {
        jobs += launch(IoDispatcher) {
          execute(null, "PRAGMA journal_mode = WAL;", 0, null)
        }
      }

      jobs.joinAll()

      assertEquals(1, create)
      assertEquals(0, update)
      assertEquals(1, open)
      assertEquals(1, configure)
    }
  }
}
