package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

abstract class AndroidxSqliteTransacterTest {
  private lateinit var transacter: TransacterImpl
  private lateinit var driver: SqlDriver

  private fun setupDatabase(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    connectionPool: ConnectionPool? = null,
  ): SqlDriver = AndroidxSqliteDriver(
    driver = androidxSqliteTestDriver(),
    databaseType = AndroidxSqliteDatabaseType.Memory,
    schema = schema,
    connectionPool = connectionPool,
  )

  @BeforeTest
  fun setup() {
    val driver = setupDatabase(
      object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 1L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit
        override fun migrate(
          driver: SqlDriver,
          oldVersion: Long,
          newVersion: Long,
          vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
      },
    )
    transacter = object : TransacterImpl(driver) {}
    this.driver = driver
  }

  @AfterTest
  fun teardown() {
    driver.close()
  }

  @Test
  fun ifBeginningANonEnclosedTransactionFails_furtherTransactionsAreNotBlockedFromBeginning() {
    this.driver.close()

    val driver = setupDatabase(
      object : SqlSchema<QueryResult.Value<Unit>> {
        override val version = 1L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit
        override fun migrate(
          driver: SqlDriver,
          oldVersion: Long,
          newVersion: Long,
          vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
      },
      connectionPool = FirstTransactionsFailConnectionPool(),
    )
    val transacter = object : TransacterImpl(driver) {}
    this.driver = driver
    assertFails {
      transacter.transaction(noEnclosing = true) {}
    }
    assertNull(driver.currentTransaction())
    transacter.transaction(noEnclosing = true) {}
  }

  @Test
  fun afterCommitRunsAfterTransactionCommits() {
    var counter = 0
    transacter.transaction {
      afterCommit { counter++ }
      assertEquals(0, counter)
    }

    assertEquals(1, counter)
  }

  @Test
  fun afterCommitDoesNotRunAfterTransactionRollbacks() {
    var counter = 0
    transacter.transaction {
      afterCommit { counter++ }
      assertEquals(0, counter)
      rollback()
    }

    assertEquals(0, counter)
  }

  @Test
  fun afterCommitRunsAfterEnclosingTransactionCommits() {
    var counter = 0
    transacter.transaction {
      afterCommit { counter++ }
      assertEquals(0, counter)

      transaction {
        afterCommit { counter++ }
        assertEquals(0, counter)
      }

      assertEquals(0, counter)
    }

    assertEquals(2, counter)
  }

  @Test
  fun afterCommitDoesNotRunInNestedTransactionWhenEnclosingRollsBack() {
    var counter = 0
    transacter.transaction {
      afterCommit { counter++ }
      assertEquals(0, counter)

      transaction {
        afterCommit { counter++ }
      }

      rollback()
    }

    assertEquals(0, counter)
  }

  @Test
  fun afterCommitDoesNotRunInNestedTransactionWhenNestedRollsBack() {
    var counter = 0
    transacter.transaction {
      afterCommit { counter++ }
      assertEquals(0, counter)

      transaction {
        afterCommit { counter++ }
        rollback()
      }

      throw AssertionError()
    }

    assertEquals(0, counter)
  }

  @Test
  fun afterRollbackNoOpsIfTheTransactionNeverRollsBack() {
    var counter = 0
    transacter.transaction {
      afterRollback { counter++ }
    }

    assertEquals(0, counter)
  }

  @Test
  fun afterRollbackRunsAfterARollbackOccurs() {
    var counter = 0
    transacter.transaction {
      afterRollback { counter++ }
      rollback()
    }

    assertEquals(1, counter)
  }

  @Test
  fun afterRollbackRunsAfterAnInnerTransactionRollsBack() {
    var counter = 0
    transacter.transaction {
      afterRollback { counter++ }
      transaction {
        rollback()
      }
      throw AssertionError()
    }

    assertEquals(1, counter)
  }

  @Test
  fun afterRollbackRunsInAnInnerTransactionWhenTheOuterTransactionRollsBack() {
    var counter = 0
    transacter.transaction {
      transaction {
        afterRollback { counter++ }
      }
      rollback()
    }

    assertEquals(1, counter)
  }

  @Test
  fun transactionsCloseThemselvesOutProperly() {
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
  fun settingNoEnclosingFailsIfThereIsACurrentlyRunningTransaction() {
    transacter.transaction(noEnclosing = true) {
      assertFailsWith<IllegalStateException> {
        transacter.transaction(noEnclosing = true) {
          throw AssertionError()
        }
      }
    }
  }

  @Test
  fun anExceptionThrownInPostRollbackFunctionIsCombinedWithTheExceptionInTheMainBody() {
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
  fun weCanReturnAValueFromATransaction() {
    val result: String = transacter.transactionWithResult { "sup" }

    assertEquals(result, "sup")
  }

  @Test
  fun weCanRollbackWithValueFromATransaction() {
    val result: String = transacter.transactionWithResult {
      rollback("rollback")

      @Suppress("UNREACHABLE_CODE")
      "sup"
    }

    assertEquals(result, "rollback")
  }

  @Test
  fun `detect the afterRollback call has escaped the original transaction thread in transaction`() {
    assertChecksThreadConfinement(
      transacter = transacter,
      scope = { transaction(false, it) },
      block = { afterRollback {} },
    )
  }

  @Test
  fun `detect the afterCommit call has escaped the original transaction thread in transaction`() {
    assertChecksThreadConfinement(
      transacter = transacter,
      scope = { transaction(false, it) },
      block = { afterCommit {} },
    )
  }

  @Test
  fun `detect the rollback call has escaped the original transaction thread in transaction`() {
    assertChecksThreadConfinement(
      transacter = transacter,
      scope = { transaction(false, it) },
      block = { rollback() },
    )
  }

  @Test
  fun `detect the afterRollback call has escaped the original transaction thread in transactionWithReturn`() {
    assertChecksThreadConfinement(
      transacter = transacter,
      scope = { transactionWithResult(false, it) },
      block = { afterRollback {} },
    )
  }

  @Test
  fun `detect the afterCommit call has escaped the original transaction thread in transactionWithReturn`() {
    assertChecksThreadConfinement(
      transacter = transacter,
      scope = { transactionWithResult(false, it) },
      block = { afterCommit {} },
    )
  }

  @Test
  fun `detect the rollback call has escaped the original transaction thread in transactionWithReturn`() {
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
      }
      else {
        connection.prepare(sql)
      }
  }

  override fun close() {
    firstTransactionFailConnection.close()
  }
  override fun acquireWriterConnection() = firstTransactionFailConnection
  override fun releaseWriterConnection() {}
  override fun acquireReaderConnection() = firstTransactionFailConnection
  override fun releaseReaderConnection(connection: SQLiteConnection) {}
  override fun setForeignKeyConstraintsEnabled(isForeignKeyConstraintsEnabled: Boolean) {}
  override fun setJournalMode(journalMode: SqliteJournalMode) {}
  override fun setSync(sync: SqliteSync) {}
}
