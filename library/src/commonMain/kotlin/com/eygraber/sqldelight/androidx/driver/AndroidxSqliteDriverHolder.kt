package com.eygraber.sqldelight.androidx.driver

import androidx.collection.LruCache
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidxSqliteDriverHolder(
  private val connectionPool: ConnectionPool,
  private val statementCache: MutableMap<SQLiteConnection, LruCache<Int, AndroidxStatement>>,
  private val statementCacheLock: ReentrantLock,
  private val statementCacheSize: Int,
  private val transactions: TransactionsThreadLocal,
  private val schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
  private val isForeignKeyConstraintsEnabled: Boolean,
  private val isForeignKeyConstraintsCheckedAfterCreateOrUpdate: Boolean,
  private val maxMigrationForeignKeyConstraintViolationsToReport: Int,
  private val migrateEmptySchema: Boolean = false,
  private val onConfigure: AndroidxSqliteConfigurableDriver.() -> Unit = {},
  private val onCreate: SqlDriver.() -> Unit = {},
  private val onUpdate: SqlDriver.(Long, Long) -> Unit = { _, _ -> },
  private val onOpen: SqlDriver.() -> Unit = {},
  private val migrationCallbacks: Array<out AfterVersion>,
) {
  private val executingDriver by lazy {
    AndroidxSqliteExecutingDriver(
      connectionPool = connectionPool,
      isStatementCacheSkipped = statementCacheSize == 0,
      statementCache = statementCache,
      statementCacheLock = statementCacheLock,
      statementCacheSize = statementCacheSize,
      transactions = transactions,
    )
  }

  private val createOrMigrateMutex = Mutex()

  private var isFirstInteraction = true

  fun currentTransaction() = executingDriver.currentTransaction()

  suspend inline fun <R> ensureSchemaIsReady(block: AndroidxSqliteExecutingDriver.() -> R): R {
    if(isFirstInteraction) {
      createOrMigrateMutex.withLock {
        if(isFirstInteraction) {
          val executingDriver = AndroidxSqliteExecutingDriver(
            connectionPool = connectionPool,
            isStatementCacheSkipped = true,
            statementCache = mutableMapOf(),
            statementCacheLock = statementCacheLock,
            statementCacheSize = 0,
            transactions = transactions,
          )

          AndroidxSqliteConfigurableDriver(executingDriver).onConfigure()

          val currentVersion = connectionPool.withWriterConnection {
            prepare("PRAGMA user_version").use { getVersion ->
              when {
                getVersion.step() -> getVersion.getLong(0)
                else -> 0
              }
            }
          }

          val isCreate = currentVersion == 0L && !migrateEmptySchema
          if(isCreate || currentVersion < schema.version) {
            val transacter = object : SuspendingTransacterImpl(executingDriver) {}

            connectionPool.withForeignKeysDisabled(
              isForeignKeyConstraintsEnabled = isForeignKeyConstraintsEnabled,
            ) {
              transacter.transaction {
                when {
                  isCreate -> schema.create(executingDriver).await()
                  else -> schema.migrate(executingDriver, currentVersion, schema.version, *migrationCallbacks).await()
                }

                val transactionConnection = requireNotNull(
                  (executingDriver.currentTransaction() as? ConnectionHolder)?.connection,
                ) {
                  "SqlDriver.newTransaction() must return an implementation of ConnectionHolder"
                }

                if(isForeignKeyConstraintsCheckedAfterCreateOrUpdate) {
                  transactionConnection.reportForeignKeyViolations(
                    maxMigrationForeignKeyConstraintViolationsToReport,
                  )
                }

                transactionConnection.execSQL("PRAGMA user_version = ${schema.version}")
              }
            }

            when {
              isCreate -> executingDriver.onCreate()
              else -> executingDriver.onUpdate(currentVersion, schema.version)
            }
          }

          executingDriver.onOpen()

          isFirstInteraction = false
        }
      }
    }

    return executingDriver.block()
  }
}

private suspend inline fun ConnectionPool.withForeignKeysDisabled(
  isForeignKeyConstraintsEnabled: Boolean,
  crossinline block: suspend () -> Unit,
) {
  if(isForeignKeyConstraintsEnabled) {
    withWriterConnection {
      execSQL("PRAGMA foreign_keys = OFF;")
    }
  }

  try {
    block()

    if(isForeignKeyConstraintsEnabled) {
      withWriterConnection {
        execSQL("PRAGMA foreign_keys = ON;")
      }
    }
  } catch(e: Throwable) {
    // An exception happened during creation / migration.
    // We will try to re-enable foreign keys, and if that also fails,
    // we will add it as a suppressed exception to the original one.
    try {
      if(isForeignKeyConstraintsEnabled) {
        withWriterConnection {
          execSQL("PRAGMA foreign_keys = ON;")
        }
      }
    } catch(fkException: Throwable) {
      e.addSuppressed(fkException)
    }
    throw e
  }
}

private fun SQLiteConnection.reportForeignKeyViolations(
  maxMigrationForeignKeyConstraintViolationsToReport: Int,
) {
  prepare("PRAGMA foreign_key_check;").use { check ->
    val violations = mutableListOf<AndroidxSqliteDriver.ForeignKeyConstraintViolation>()
    var count = 0
    while(check.step() && count++ < maxMigrationForeignKeyConstraintViolationsToReport) {
      violations.add(
        AndroidxSqliteDriver.ForeignKeyConstraintViolation(
          referencingTable = check.getText(0),
          referencingRowId = check.getInt(1),
          referencedTable = check.getText(2),
          referencingConstraintIndex = check.getInt(3),
        ),
      )
    }

    if(violations.isNotEmpty()) {
      val unprintedViolationsCount = violations.size - 5
      val unprintedDisclaimer = if(unprintedViolationsCount > 0) " ($unprintedViolationsCount not shown)" else ""

      throw AndroidxSqliteDriver.ForeignKeyConstraintCheckException(
        violations = violations,
        message = """
            |The following foreign key constraints are violated$unprintedDisclaimer:
            |
            |${violations.take(5).joinToString(separator = "\n\n")}
        """.trimMargin(),
      )
    }
  }
}
