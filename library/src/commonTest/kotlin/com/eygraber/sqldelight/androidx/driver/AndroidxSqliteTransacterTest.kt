package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

abstract class AndroidxSqliteTransacterTest {
  private lateinit var transacter: SuspendingTransacterImpl
  private lateinit var driver: SqlDriver

  @Suppress("VisibleForTests")
  private fun setupDatabase(
    schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
    connectionPool: ConnectionPool? = null,
  ): SqlDriver = AndroidxSqliteDriver(
    connectionFactory = androidxSqliteTestConnectionFactory(),
    databaseType = AndroidxSqliteDatabaseType.Memory,
    schema = schema,
    overridingConnectionPool = connectionPool,
  )

  @BeforeTest
  fun setup() {
    val driver = setupDatabase(
      object : SqlSchema<QueryResult.AsyncValue<Unit>> {
        override val version = 1L
        override fun create(driver: SqlDriver): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {}
        override fun migrate(
          driver: SqlDriver,
          oldVersion: Long,
          newVersion: Long,
          vararg callbacks: AfterVersion,
        ): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {}
      },
    )
    transacter = object : SuspendingTransacterImpl(driver) {}
    this.driver = driver
  }

  @AfterTest
  fun teardown() {
    driver.close()
  }

  @Test
  fun ifBeginningANonEnclosedTransactionFails_furtherTransactionsAreNotBlockedFromBeginning() = runTest {
    this@AndroidxSqliteTransacterTest.driver.close()

    val driver = setupDatabase(
      object : SqlSchema<QueryResult.AsyncValue<Unit>> {
        override val version = 1L
        override fun create(driver: SqlDriver): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {}
        override fun migrate(
          driver: SqlDriver,
          oldVersion: Long,
          newVersion: Long,
          vararg callbacks: AfterVersion,
        ): QueryResult.AsyncValue<Unit> = QueryResult.AsyncValue {}
      },
      connectionPool = FirstTransactionsFailConnectionPool(),
    )
    val transacter = object : SuspendingTransacterImpl(driver) {}
    this@AndroidxSqliteTransacterTest.driver = driver
    assertFails {
      transacter.transaction(noEnclosing = true) {}
    }
    assertNull(driver.currentTransaction())
    transacter.transaction(noEnclosing = true) {}
  }

  @Test
  fun afterCommitRunsAfterTransactionCommits() = runTest {
    var counter = 0
    transacter.transaction {
      afterCommit { counter++ }
      assertEquals(0, counter)
    }

    assertEquals(1, counter)
  }

  @Test
  fun afterCommitDoesNotRunAfterTransactionRollbacks() = runTest {
    var counter = 0
    transacter.transaction {
      afterCommit { counter++ }
      assertEquals(0, counter)
      rollback()
    }

    assertEquals(0, counter)
  }

  @Test
  fun afterCommitRunsAfterEnclosingTransactionCommits() = runTest {
    var counter = 0
    transacter.transaction {
      afterCommit { counter++ }
      assertEquals(0, counter)

      transacter.transaction {
        afterCommit { counter++ }
        assertEquals(0, counter)
      }

      assertEquals(0, counter)
    }

    assertEquals(2, counter)
  }

  @Test
  fun afterCommitDoesNotRunInNestedTransactionWhenEnclosingRollsBack() = runTest {
    var counter = 0
    transacter.transaction {
      afterCommit { counter++ }
      assertEquals(0, counter)

      transacter.transaction {
        afterCommit { counter++ }
      }

      rollback()
    }

    assertEquals(0, counter)
  }

  @Test
  fun afterCommitDoesNotRunInNestedTransactionWhenNestedRollsBack() = runTest {
    var counter = 0
    transacter.transaction {
      afterCommit { counter++ }
      assertEquals(0, counter)

      transacter.transaction {
        afterCommit { counter++ }
        rollback()
      }

      throw AssertionError()
    }

    assertEquals(0, counter)
  }

  @Test
  fun afterRollbackNoOpsIfTheTransactionNeverRollsBack() = runTest {
    var counter = 0
    transacter.transaction {
      afterRollback { counter++ }
    }

    assertEquals(0, counter)
  }

  @Test
  fun afterRollbackRunsAfterARollbackOccurs() = runTest {
    var counter = 0
    transacter.transaction {
      afterRollback { counter++ }
      rollback()
    }

    assertEquals(1, counter)
  }

  @Test
  fun afterRollbackRunsAfterAnInnerTransactionRollsBack() = runTest {
    var counter = 0
    transacter.transaction {
      afterRollback { counter++ }
      transacter.transaction {
        rollback()
      }
      throw AssertionError()
    }

    assertEquals(1, counter)
  }

  @Test
  fun afterRollbackRunsInAnInnerTransactionWhenTheOuterTransactionRollsBack() = runTest {
    var counter = 0
    transacter.transaction {
      transacter.transaction {
        afterRollback { counter++ }
      }
      rollback()
    }

    assertEquals(1, counter)
  }

  @Test
  fun transactionsCloseThemselvesOutProperly() = runTest {
    var counter = 0
    transacter.transaction {
      afterCommit { counter++ }
    }

    transacter.transaction {
      afterCommit { counter++ }
    }

    assertEquals(2, counter)
  }

  @Test
  fun settingNoEnclosingFailsIfThereIsACurrentlyRunningTransaction() = runTest {
    transacter.transaction(noEnclosing = true) {
      assertFailsWith<IllegalStateException> {
        transacter.transaction(noEnclosing = true) {
          throw AssertionError()
        }
      }
    }
  }

  @Test
  fun anExceptionThrownInPostRollbackFunctionIsCombinedWithTheExceptionInTheMainBody() = runTest {
    class ExceptionA : RuntimeException()
    class ExceptionB : RuntimeException()

    val t = assertFailsWith<Throwable> {
      transacter.transaction {
        afterRollback {
          throw ExceptionA()
        }
        throw ExceptionB()
      }
    }
    assertTrue("Exception thrown in body not in message($t)") { t.toString().contains("ExceptionA") }
    assertTrue("Exception thrown in rollback not in message($t)") { t.toString().contains("ExceptionB") }
  }

  @Test
  fun weCanReturnAValueFromATransaction() = runTest {
    val result: String = transacter.transactionWithResult { "sup" }

    assertEquals(result, "sup")
  }

  @Test
  fun weCanRollbackWithValueFromATransaction() = runTest {
    val result: String = transacter.transactionWithResult {
      rollback("rollback")

      @Suppress("UNREACHABLE_CODE")
      "sup"
    }

    assertEquals(result, "rollback")
  }

  @Test
  fun `detect the afterRollback call has escaped the original transaction thread in transaction`() = runTest {
    assertChecksThreadConfinement(
      transacter = transacter,
      scope = { transaction(false, it) },
      block = { afterRollback {} },
    )
  }

  @Test
  fun `detect the afterCommit call has escaped the original transaction thread in transaction`() = runTest {
    assertChecksThreadConfinement(
      transacter = transacter,
      scope = { transaction(false, it) },
      block = { afterCommit {} },
    )
  }

  @Test
  fun `detect the rollback call has escaped the original transaction thread in transaction`() = runTest {
    assertChecksThreadConfinement(
      transacter = transacter,
      scope = { transaction(false, it) },
      block = { rollback() },
    )
  }

  @Test
  fun `detect the afterRollback call has escaped the original transaction thread in transactionWithReturn`() = runTest {
    assertChecksThreadConfinement(
      transacter = transacter,
      scope = { transactionWithResult(false, it) },
      block = { afterRollback {} },
    )
  }

  @Test
  fun `detect the afterCommit call has escaped the original transaction thread in transactionWithReturn`() = runTest {
    assertChecksThreadConfinement(
      transacter = transacter,
      scope = { transactionWithResult(false, it) },
      block = { afterCommit {} },
    )
  }

  @Test
  fun `detect the rollback call has escaped the original transaction thread in transactionWithReturn`() = runTest {
    assertChecksThreadConfinement(
      transacter = transacter,
      scope = { transactionWithResult(false, it) },
      block = { rollback(Unit) },
    )
  }
}

private class FirstTransactionsFailConnectionPool : ConnectionPool {
  private val firstTransactionFailConnection = object : SQLiteConnection {
    private var isFirstBeginTransaction = true

    private val connection = androidxSqliteTestDriver().open(":memory:")

    override fun close() {
      connection.close()
    }

    override fun prepare(sql: String) =
      if(sql == "BEGIN IMMEDIATE" && isFirstBeginTransaction) {
        isFirstBeginTransaction = false
        error("Throwing an error")
      } else {
        connection.prepare(sql)
      }
  }

  override fun close() {
    firstTransactionFailConnection.close()
  }

  override suspend fun acquireWriterConnection() = firstTransactionFailConnection
  override suspend fun releaseWriterConnection() {}
  override suspend fun acquireReaderConnection() = firstTransactionFailConnection
  override suspend fun releaseReaderConnection(connection: SQLiteConnection) {}
  override suspend fun <R> setJournalMode(
    executeStatement: suspend (SQLiteConnection) -> R,
  ): R = error("Don't use")
}
