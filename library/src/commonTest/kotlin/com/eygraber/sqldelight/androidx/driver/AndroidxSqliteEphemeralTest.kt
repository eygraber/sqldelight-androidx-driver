package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Test for SQLite ephemeral database configurations
 * */
abstract class AndroidxSqliteEphemeralTest {
  private enum class Type {
    IN_MEMORY,
    NAMED,
    TEMPORARY,
  }

  private val schema = object : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
      driver.execute(
        null,
        """
          CREATE TABLE test (
            id INTEGER NOT NULL PRIMARY KEY,
            value TEXT NOT NULL
           );
        """.trimIndent(),
        0,
      )
      return QueryResult.Unit
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
      vararg callbacks: AfterVersion,
    ) = QueryResult.Unit // No-op.
  }

  private val mapper = { cursor: SqlCursor ->
    TestData(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
    )
  }

  private inline fun withDatabase(
    type: Type,
    dbName: String? = null,
    deleteDbBeforeRun: Boolean = true,
    deleteDbAfterRun: Boolean = true,
    test: SqlDriver.() -> Unit,
  ) {
    val fullDbName = when(dbName) {
      null -> null
      else -> "${this::class.qualifiedName}.$dbName.db"
    }

    if(fullDbName != null && deleteDbBeforeRun) {
      deleteFile(fullDbName)
      deleteFile("$fullDbName-shm")
      deleteFile("$fullDbName-wal")
    }

    val result = runCatching {
      when(type) {
        Type.IN_MEMORY -> AndroidxSqliteDriver(androidxSqliteTestDriver(), AndroidxSqliteDatabaseType.Memory, schema)
        Type.NAMED -> AndroidxSqliteDriver(
          androidxSqliteTestDriver(),
          AndroidxSqliteDatabaseType.File(requireNotNull(fullDbName)),
          schema,
        )

        Type.TEMPORARY -> AndroidxSqliteDriver(androidxSqliteTestDriver(), AndroidxSqliteDatabaseType.Temporary, schema)
      }.test()
    }

    if(fullDbName != null && (deleteDbAfterRun || result.isFailure)) {
      deleteFile(fullDbName)
      deleteFile("$fullDbName-shm")
      deleteFile("$fullDbName-wal")
    }

    if(result.isFailure) result.getOrThrow()
  }

  @Test
  fun inMemoryCreatesIndependentDatabase() {
    val data1 = TestData(1, "val1")
    withDatabase(Type.IN_MEMORY) {
      val driver1 = this
      driver1.insertTestData(data1)
      assertEquals(data1, driver1.testDataQuery().executeAsOne())

      withDatabase(Type.IN_MEMORY) {
        val driver2 = this
        assertNull(driver2.testDataQuery().executeAsOneOrNull())
        driver1.close()
        driver2.close()
      }
    }
  }

  @Test
  fun temporaryCreatesIndependentDatabase() {
    val data1 = TestData(1, "val1")
    withDatabase(Type.TEMPORARY) {
      val driver1 = this
      driver1.insertTestData(data1)
      assertEquals(data1, driver1.testDataQuery().executeAsOne())

      withDatabase(Type.TEMPORARY) {
        val driver2 = this
        assertNull(driver2.testDataQuery().executeAsOneOrNull())
        driver1.close()
        driver2.close()
      }
    }
  }

  @Test
  fun namedCreatesSharedDatabase() {
    val dbName = Random.nextULong().toHexString()

    val data1 = TestData(1, "val1")
    withDatabase(
      type = Type.NAMED,
      dbName = dbName,
    ) {
      val driver1 = this

      driver1.insertTestData(data1)
      assertEquals(data1, driver1.testDataQuery().executeAsOne())

      withDatabase(
        type = Type.NAMED,
        dbName = dbName,
        deleteDbBeforeRun = false,
      ) {
        val driver2 = this

        assertEquals(data1, driver2.testDataQuery().executeAsOne())
        driver1.close()
        assertEquals(data1, driver2.testDataQuery().executeAsOne())
        driver2.close()

        withDatabase(
          type = Type.NAMED,
          dbName = dbName,
          deleteDbBeforeRun = false,
        ) {
          val driver3 = this

          assertEquals(data1, driver3.testDataQuery().executeAsOne())
          driver3.close()
        }
      }
    }
  }

  private fun SqlDriver.insertTestData(testData: TestData) {
    execute(1, "INSERT INTO test VALUES (?, ?)", 2) {
      bindLong(0, testData.id)
      bindString(1, testData.value)
    }
  }

  private fun SqlDriver.testDataQuery(): Query<TestData> = object : Query<TestData>(mapper) {
    override fun <R> execute(
      mapper: (SqlCursor) -> QueryResult<R>,
    ): QueryResult<R> = executeQuery(0, "SELECT * FROM test", mapper, 0, null)

    override fun addListener(listener: Listener) {
      addListener("test", listener = listener)
    }

    override fun removeListener(listener: Listener) {
      removeListener("test", listener = listener)
    }
  }

  private data class TestData(val id: Long, val value: String)
}
