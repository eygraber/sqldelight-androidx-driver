package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteException
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.db.use
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

abstract class AndroidxSqliteDriverTest {
  private lateinit var driver: SqlDriver
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
  private var transacter: Transacter? = null

  private fun setupDatabase(
    schema: SqlSchema<QueryResult.Value<Unit>>,
  ): SqlDriver = AndroidxSqliteDriver(androidxSqliteTestDriver(), AndroidxSqliteDatabaseType.Memory, schema)

  private fun useSingleItemCacheDriver(block: (AndroidxSqliteDriver) -> Unit) {
    AndroidxSqliteDriver(
      androidxSqliteTestDriver(),
      AndroidxSqliteDatabaseType.Memory,
      schema,
      AndroidxSqliteConfiguration(cacheSize = 1),
    ).use(block)
  }

  private fun changes(): Long? =
    // wrap in a transaction to ensure read happens on transaction thread/connection
    transacter?.transactionWithResult {
      val mapper: (SqlCursor) -> QueryResult<Long?> = { cursor ->
        cursor.next()
        QueryResult.Value(cursor.getLong(0))
      }
      driver.executeQuery(null, "SELECT changes()", mapper, 0).value
    }

  @BeforeTest
  fun setup() {
    driver = setupDatabase(schema = schema)
    transacter = object : TransacterImpl(driver) {}
  }

  @AfterTest
  fun tearDown() {
    transacter = null
    driver.close()
  }

  @Test
  fun insertCanRunMultipleTimes() {
    val insert = { binders: SqlPreparedStatement.() -> Unit ->
      driver.execute(2, "INSERT INTO test VALUES (?, ?);", 2, binders)
    }

    fun query(mapper: (SqlCursor) -> QueryResult<Unit>) {
      driver.executeQuery(3, "SELECT * FROM test", mapper, 0)
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
      QueryResult.Unit
    }

    assertEquals(1, changes())

    query { cursor ->
      assertTrue(cursor.next().value)
      assertEquals(1, cursor.getLong(0))
      assertEquals("Alec", cursor.getString(1))
      QueryResult.Unit
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
      QueryResult.Unit
    }

    driver.execute(5, "DELETE FROM test", 0)
    assertEquals(2, changes())

    query { cursor ->
      assertFalse(cursor.next().value)
      QueryResult.Unit
    }
  }

  @Test
  fun queryCanRunMultipleTimes() {
    val insert = { binders: SqlPreparedStatement.() -> Unit ->
      driver.execute(2, "INSERT INTO test VALUES (?, ?);", 2, binders)
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

    fun query(binders: SqlPreparedStatement.() -> Unit, mapper: (SqlCursor) -> QueryResult<Unit>) {
      driver.executeQuery(6, "SELECT * FROM test WHERE value = ?", mapper, 1, binders)
    }

    query(
      binders = {
        bindString(0, "Jake")
      },
      mapper = { cursor ->
        assertTrue(cursor.next().value)
        assertEquals(2, cursor.getLong(0))
        assertEquals("Jake", cursor.getString(1))
        QueryResult.Unit
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
        QueryResult.Unit
      },
    )
  }

  @Test
  fun sqlResultSetGettersReturnNullIfTheColumnValuesAreNULL() {
    val insert = { binders: SqlPreparedStatement.() -> Unit ->
      driver.execute(7, "INSERT INTO nullability_test VALUES (?, ?, ?, ?, ?);", 5, binders)
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
      QueryResult.Unit
    }
    driver.executeQuery(8, "SELECT * FROM nullability_test", mapper, 0)
  }

  @Test
  fun `cached statement can be reused`() {
    useSingleItemCacheDriver { driver ->
      lateinit var bindable: SqlPreparedStatement
      driver.executeQuery(2, "SELECT * FROM test", { QueryResult.Unit }, 0, { bindable = this })

      driver.executeQuery(
        2,
        "SELECT * FROM test",
        { QueryResult.Unit },
        0,
        {
          assertSame(bindable, this)
        },
      )
    }
  }

  @Test
  fun `cached statement is evicted and closed`() {
    useSingleItemCacheDriver { driver ->
      lateinit var bindable: SqlPreparedStatement
      driver.executeQuery(2, "SELECT * FROM test", { QueryResult.Unit }, 0, { bindable = this })

      driver.executeQuery(3, "SELECT * FROM test", { QueryResult.Unit }, 0)

      driver.executeQuery(
        2,
        "SELECT * FROM test",
        { QueryResult.Unit },
        0,
        {
          assertNotSame(bindable, this)
        },
      )
    }
  }

  @Test
  fun `uncached statement is closed`() {
    useSingleItemCacheDriver { driver ->
      lateinit var bindable: AndroidxStatement
      driver.execute(null, "SELECT * FROM test", 0) {
        bindable = this as AndroidxStatement
      }

      try {
        bindable.execute()
        throw AssertionError("Expected an IllegalStateException (attempt to re-open an already-closed object)")
      } catch(ignored: SQLiteException) {
      }
    }
  }
}
