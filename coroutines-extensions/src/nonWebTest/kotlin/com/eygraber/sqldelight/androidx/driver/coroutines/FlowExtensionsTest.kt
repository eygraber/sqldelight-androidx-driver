package com.eygraber.sqldelight.androidx.driver.coroutines

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

abstract class FlowExtensionsTest {
  private lateinit var driver: SqlDriver

  @BeforeTest
  fun setup() {
    driver = AndroidxSqliteDriver(
      driver = testSQLiteDriver(),
      databaseType = AndroidxSqliteDatabaseType.Memory,
      schema = object : SqlSchema<QueryResult.AsyncValue<Unit>> {
        override val version: Long = 1

        override fun create(driver: SqlDriver): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {
          driver.execute(
            null,
            """
            CREATE TABLE test (
              id INTEGER NOT NULL PRIMARY KEY,
              value TEXT
            );
            """.trimIndent(),
            0,
          ).await()
        }

        override fun migrate(
          driver: SqlDriver,
          oldVersion: Long,
          newVersion: Long,
          vararg callbacks: AfterVersion,
        ) = QueryResult.AsyncValue {}
      },
    )
  }

  @AfterTest
  fun tearDown() {
    driver.close()
  }

  @Test
  fun `asFlow emits the initial query immediately`() = runTest {
    val query = testQuery()
    val emitted = query.asFlow().first()
    assertEquals(query, emitted)
  }

  @Test
  fun `mapToOne emits the single row`() = runTest {
    insertRow(1, "only")

    val value = testQuery().asFlow().mapToOne().first()
    assertEquals(TestRow(1, "only"), value)
  }

  @Test
  fun `mapToOneOrDefault emits default when no rows`() = runTest {
    val value = testQuery().asFlow().mapToOneOrDefault(TestRow(-1, "default")).first()
    assertEquals(TestRow(-1, "default"), value)
  }

  @Test
  fun `mapToOneOrDefault emits row when present`() = runTest {
    insertRow(7, "seven")

    val value = testQuery().asFlow().mapToOneOrDefault(TestRow(-1, "default")).first()
    assertEquals(TestRow(7, "seven"), value)
  }

  @Test
  fun `mapToOneOrNull emits null when no rows`() = runTest {
    val value = testQuery().asFlow().mapToOneOrNull().first()
    assertNull(value)
  }

  @Test
  fun `mapToOneOrNull emits row when present`() = runTest {
    insertRow(4, "four")

    val value = testQuery().asFlow().mapToOneOrNull().first()
    assertEquals(TestRow(4, "four"), value)
  }

  @Test
  fun `mapToOneNotNull emits row when present`() = runTest {
    insertRow(9, "nine")

    val value = testQuery().asFlow().mapToOneNotNull().first()
    assertEquals(TestRow(9, "nine"), value)
  }

  @Test
  fun `mapToList emits all rows`() = runTest {
    insertRow(1, "a")
    insertRow(2, "b")
    insertRow(3, "c")

    val rows = testQuery().asFlow().mapToList().first()
    assertEquals(
      listOf(TestRow(1, "a"), TestRow(2, "b"), TestRow(3, "c")),
      rows,
    )
  }

  @Test
  fun `mapToList emits an empty list when no rows`() = runTest {
    val rows = testQuery().asFlow().mapToList().first()
    assertEquals(emptyList(), rows)
  }

  private suspend fun insertRow(id: Long, value: String?) {
    driver.execute(null, "INSERT INTO test VALUES (?, ?)", 2) {
      bindLong(0, id)
      bindString(1, value)
    }.await()
  }

  private data class TestRow(val id: Long, val value: String?)

  private fun testQuery(): Query<TestRow> = object : Query<TestRow>(
    { cursor ->
      TestRow(
        requireNotNull(cursor.getLong(0)),
        cursor.getString(1),
      )
    },
  ) {
    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
      driver.executeQuery(
        identifier = 0,
        sql = "SELECT * FROM test",
        mapper = mapper,
        parameters = 0,
        binders = null,
      )

    override fun addListener(listener: Listener) {
      driver.addListener("test", listener = listener)
    }

    override fun removeListener(listener: Listener) {
      driver.removeListener("test", listener = listener)
    }
  }
}
