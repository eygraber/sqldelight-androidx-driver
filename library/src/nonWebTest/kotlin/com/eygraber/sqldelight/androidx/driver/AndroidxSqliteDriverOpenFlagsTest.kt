package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.SuspendingTransacter
import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

abstract class AndroidxSqliteDriverOpenFlagsTest {
  private lateinit var driver: SqlDriver
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
  private var transacter: SuspendingTransacter? = null

  private fun setupDatabase(
    schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
  ): SqlDriver = AndroidxSqliteDriver(androidxSqliteTestConnectionFactory(), AndroidxSqliteDatabaseType.Memory, schema)

  private suspend fun changes(): Long? =
    // wrap in a transaction to ensure read happens on transaction thread/connection
    transacter?.transactionWithResult {
      val mapper: (SqlCursor) -> QueryResult<Long?> = { cursor ->
        cursor.next()
        QueryResult.Value(cursor.getLong(0))
      }
      driver.executeQuery(
        identifier = null,
        sql = "SELECT changes()",
        mapper = mapper,
        parameters = 0,
      ).await()
    }

  @BeforeTest
  fun setup() {
    driver = setupDatabase(schema = schema)
    transacter = object : SuspendingTransacterImpl(driver), SuspendingTransacter {}
  }

  @AfterTest
  fun tearDown() {
    transacter = null
    driver.close()
  }

  @Test
  fun insertCanRunMultipleTimes() = runTest {
    suspend fun insert(binders: SqlPreparedStatement.() -> Unit) {
      driver.execute(
        identifier = 2,
        sql = "INSERT INTO test VALUES (?, ?);",
        parameters = 2,
        binders = binders,
      ).await()
    }

    suspend fun query(mapper: (SqlCursor) -> QueryResult<Unit>) {
      driver.executeQuery(
        identifier = 3,
        sql = "SELECT * FROM test",
        mapper = mapper,
        parameters = 0,
      ).await()
    }

    query { cursor ->
      assertFalse(cursor.next().value)
      QueryResult.Unit
    }

    insert {
      bindLong(0, 1)
      bindString(1, "Alec")
    }

    query { cursor ->
      assertTrue(cursor.next().value)
      assertFalse(cursor.next().value)
      QueryResult.AsyncValue {}
    }

    assertEquals(1, changes())

    query { cursor ->
      assertTrue(cursor.next().value)
      assertEquals(1, cursor.getLong(0))
      assertEquals("Alec", cursor.getString(1))
      QueryResult.AsyncValue {}
    }

    insert {
      bindLong(0, 2)
      bindString(1, "Jake")
    }
    assertEquals(1, changes())

    query { cursor ->
      assertTrue(cursor.next().value)
      assertEquals(1, cursor.getLong(0))
      assertEquals("Alec", cursor.getString(1))
      assertTrue(cursor.next().value)
      assertEquals(2, cursor.getLong(0))
      assertEquals("Jake", cursor.getString(1))
      QueryResult.AsyncValue {}
    }

    driver.execute(5, "DELETE FROM test", 0).await()
    assertEquals(2, changes())

    query { cursor ->
      assertFalse(cursor.next().value)
      QueryResult.AsyncValue {}
    }
  }

  @Test
  fun queryCanRunMultipleTimes() = runTest {
    suspend fun insert(binders: SqlPreparedStatement.() -> Unit) {
      driver.execute(
        identifier = 2,
        sql = "INSERT INTO test VALUES (?, ?);",
        parameters = 2,
        binders = binders,
      ).await()
    }

    insert {
      bindLong(0, 1)
      bindString(1, "Alec")
    }
    assertEquals(1, changes())
    insert {
      bindLong(0, 2)
      bindString(1, "Jake")
    }
    assertEquals(1, changes())

    suspend fun query(binders: SqlPreparedStatement.() -> Unit, mapper: (SqlCursor) -> QueryResult<Unit>) {
      driver.executeQuery(
        identifier = 6,
        sql = "SELECT * FROM test WHERE value = ?",
        mapper = mapper,
        parameters = 1,
        binders = binders,
      ).await()
    }

    query(
      binders = {
        bindString(0, "Jake")
      },
      mapper = { cursor ->
        assertTrue(cursor.next().value)
        assertEquals(2, cursor.getLong(0))
        assertEquals("Jake", cursor.getString(1))
        QueryResult.AsyncValue {}
      },
    )

    // Second time running the query is fine
    query(
      binders = {
        bindString(0, "Jake")
      },
      mapper = { cursor ->
        assertTrue(cursor.next().value)
        assertEquals(2, cursor.getLong(0))
        assertEquals("Jake", cursor.getString(1))
        QueryResult.AsyncValue {}
      },
    )
  }

  @Test
  fun sqlResultSetGettersReturnNullIfTheColumnValuesAreNULL() = runTest {
    suspend fun insert(binders: SqlPreparedStatement.() -> Unit) {
      driver.execute(
        identifier = 7,
        sql = "INSERT INTO nullability_test VALUES (?, ?, ?, ?, ?);",
        parameters = 5,
        binders = binders,
      ).await()
    }

    insert {
      bindLong(0, 1)
      bindLong(1, null)
      bindString(2, null)
      bindBytes(3, null)
      bindDouble(4, null)
    }
    assertEquals(1, changes())

    val mapper: (SqlCursor) -> QueryResult<Unit> = { cursor ->
      assertTrue(cursor.next().value)
      assertEquals(1, cursor.getLong(0))
      assertNull(cursor.getLong(1))
      assertNull(cursor.getString(2))
      assertNull(cursor.getBytes(3))
      assertNull(cursor.getDouble(4))
      QueryResult.AsyncValue {}
    }

    driver.executeQuery(
      identifier = 8,
      sql = "SELECT * FROM nullability_test",
      mapper = mapper,
      parameters = 0,
    ).await()
  }
}
