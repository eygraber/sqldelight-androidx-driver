package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

abstract class AndroidxSqliteQueryTest {
  private val mapper = { cursor: SqlCursor ->
    TestData(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
    )
  }

  private lateinit var driver: SqlDriver

  private fun setupDatabase(
    schema: SqlSchema<QueryResult.Value<Unit>>,
  ): SqlDriver = AndroidxSqliteDriver(androidxSqliteTestDriver(), AndroidxSqliteDatabaseType.Memory, schema)

  @BeforeTest
  fun setup() {
    driver = setupDatabase(
      schema = object : SqlSchema<QueryResult.Value<Unit>> {
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
      },
    )
  }

  @AfterTest
  fun tearDown() {
    driver.close()
  }

  @Test
  fun executeAsOne() {
    val data1 = TestData(1, "val1")
    insertTestData(data1)

    assertEquals(data1, testDataQuery().executeAsOne())
  }

  @Test
  fun executeAsOneTwoTimes() {
    val data1 = TestData(1, "val1")
    insertTestData(data1)

    val query = testDataQuery()

    assertEquals(query.executeAsOne(), query.executeAsOne())
  }

  @Test
  fun executeAsOneThrowsNpeForNoRows() {
    try {
      testDataQuery().executeAsOne()
      throw AssertionError("Expected an IllegalStateException")
    }
    catch(_: NullPointerException) {
    }
  }

  @Test
  fun executeAsOneThrowsIllegalStateExceptionForManyRows() {
    try {
      insertTestData(TestData(1, "val1"))
      insertTestData(TestData(2, "val2"))

      testDataQuery().executeAsOne()
      throw AssertionError("Expected an IllegalStateException")
    }
    catch(_: IllegalStateException) {
    }
  }

  @Test
  fun executeAsOneOrNull() {
    val data1 = TestData(1, "val1")
    insertTestData(data1)

    val query = testDataQuery()
    assertEquals(data1, query.executeAsOneOrNull())
  }

  @Test
  fun executeAsOneOrNullReturnsNullForNoRows() {
    assertNull(testDataQuery().executeAsOneOrNull())
  }

  @Test
  fun executeAsOneOrNullThrowsIllegalStateExceptionForManyRows() {
    try {
      insertTestData(TestData(1, "val1"))
      insertTestData(TestData(2, "val2"))

      testDataQuery().executeAsOneOrNull()
      throw AssertionError("Expected an IllegalStateException")
    }
    catch(_: IllegalStateException) {
    }
  }

  @Test
  fun executeAsList() {
    val data1 = TestData(1, "val1")
    val data2 = TestData(2, "val2")

    insertTestData(data1)
    insertTestData(data2)

    assertEquals(listOf(data1, data2), testDataQuery().executeAsList())
  }

  @Test
  fun executeAsListForNoRows() {
    assertTrue(testDataQuery().executeAsList().isEmpty())
  }

  @Test
  fun notifyDataChangedNotifiesListeners() {
    var notifies = 0
    val query = testDataQuery()
    val listener = Query.Listener { notifies++ }

    query.addListener(listener)
    assertEquals(0, notifies)

    driver.notifyListeners("test")
    assertEquals(1, notifies)
  }

  @Test
  fun removeListenerActuallyRemovesListener() {
    var notifies = 0
    val query = testDataQuery()
    val listener = Query.Listener { notifies++ }

    query.addListener(listener)
    query.removeListener(listener)
    driver.notifyListeners("test")
    assertEquals(0, notifies)
  }

  private fun insertTestData(testData: TestData) {
    driver.execute(1, "INSERT INTO test VALUES (?, ?)", 2) {
      bindLong(0, testData.id)
      bindString(1, testData.value)
    }
  }

  private fun testDataQuery(): Query<TestData> = object : Query<TestData>(mapper) {
    override fun <R> execute(
      mapper: (SqlCursor) -> QueryResult<R>,
    ): QueryResult<R> = driver.executeQuery(
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

  private data class TestData(val id: Long, val value: String)
}
