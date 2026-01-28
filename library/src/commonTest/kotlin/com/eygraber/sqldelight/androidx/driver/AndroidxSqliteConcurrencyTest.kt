package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.Test
import kotlin.test.assertEquals

private const val CONCURRENCY: Int = 500

abstract class AndroidxSqliteConcurrencyTest {
  private val schema = object : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
      driver.execute(
        null,
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

  private val schemaForInitialSynchronization = object : SqlSchema<QueryResult.Value<Unit>> {
    override var version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
      driver.execute(
        null,
        """
        |CREATE TABLE test (
        |  id INTEGER PRIMARY KEY NOT NULL,
        |  value TEXT DEFAULT NULL
        |);
        """.trimMargin(),
        0,
      )

      // add an artificial delay to ensure other threads hit the sync point
      runBlocking {
        delay(500)
      }

      driver.execute(
        null,
        """
        |INSERT INTO test(id, value) VALUES (0, 'initial');
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
      // add an artificial delay to ensure other threads hit the sync point
      runBlocking {
        delay(500)
      }

      driver.execute(
        null,
        """
        |INSERT INTO test(id, value) VALUES (1, 'migrated');
        """.trimMargin(),
        0,
      )

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
      concurrencyModel = MultipleReadersSingleWriter(
        isWal = true,
        walCount = CONCURRENCY - 1,
      ),
    ),
    test: SqlDriver.() -> Unit,
  ) {
    val fullDbName = "${this::class.qualifiedName.orEmpty()}.$dbName.db"

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
      repeat(CONCURRENCY * 2) { a ->
        jobs += launch(IoDispatcher) {
          if(a.mod(2) == 0) {
            transacter.transaction {
              val lastId = executeQuery(
                identifier = null,
                sql = "SELECT id FROM test ORDER BY id DESC LIMIT 1;",
                mapper = { cursor ->
                  if(cursor.next().value) {
                    QueryResult.Value(cursor.getLong(0) ?: -1L)
                  }
                  else {
                    QueryResult.Value(-1L)
                  }
                },
                parameters = 0,
                binders = null,
              ).value
              execute(
                identifier = null,
                sql = "INSERT INTO test(id) VALUES (${lastId + 1});",
                parameters = 0,
                binders = null,
              )
            }
          }
          else {
            execute(
              identifier = null,
              sql = "UPDATE test SET value = 'test' WHERE id = 0;",
              parameters = 0,
              binders = null,
            )
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
          }
          else {
            QueryResult.Value(-1L)
          }
        },
        parameters = 0,
        binders = null,
      ).value

      assertEquals(CONCURRENCY - 1L, lastId)
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
      repeat(CONCURRENCY) {
        jobs += launch(IoDispatcher) {
          executeQuery(
            identifier = null,
            sql = "PRAGMA user_version;",
            mapper = { QueryResult.Unit },
            parameters = 0,
            binders = null,
          )
        }
      }

      jobs.joinAll()

      assertEquals(1, create)
      assertEquals(0, update)
      assertEquals(1, open)
      assertEquals(1, configure)
    }
  }

  @Test
  fun `multiple read connections wait until creation is complete`() = runTest {
    withDatabase(
      schema = schemaForInitialSynchronization,
      dbName = Random.nextULong().toHexString(),
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
      onConfigure = {},
    ) {
      List(CONCURRENCY) {
        launch(IoDispatcher) {
          val result = executeQuery(
            identifier = null,
            sql = "SELECT value FROM test WHERE id = 0",
            mapper = { cursor ->
              if(cursor.next().value) {
                QueryResult.Value(cursor.getString(0))
              }
              else {
                QueryResult.Value(null)
              }
            },
            parameters = 0,
          )

          assertEquals("initial", result.value)
        }
      }.joinAll()
    }
  }

  @Test
  fun `multiple read connections wait until migration is complete`() = runTest {
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
      launch(IoDispatcher) {
        execute(identifier = null, sql = "PRAGMA user_version;", parameters = 0, binders = null)
      }.join()
    }

    schemaForInitialSynchronization.version++
    withDatabase(
      schema = schemaForInitialSynchronization,
      dbName = dbName,
      onCreate = {},
      onUpdate = { _, _ -> },
      onOpen = {},
      onConfigure = {},
      deleteDbBeforeRun = false,
    ) {
      List(CONCURRENCY) {
        launch(IoDispatcher) {
          val result = executeQuery(
            identifier = null,
            sql = "SELECT value FROM test WHERE id = 1",
            mapper = { cursor ->
              if(cursor.next().value) {
                QueryResult.Value(cursor.getString(0))
              }
              else {
                QueryResult.Value(null)
              }
            },
            parameters = 0,
          )

          assertEquals("migrated", result.value)
        }
      }.joinAll()
    }
  }
}
