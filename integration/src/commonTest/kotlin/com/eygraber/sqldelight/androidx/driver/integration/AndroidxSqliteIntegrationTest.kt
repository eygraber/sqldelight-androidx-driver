package com.eygraber.sqldelight.androidx.driver.integration

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.Query
import app.cash.sqldelight.TransactionWithoutReturn
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneNotNull
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest

abstract class AndroidxSqliteIntegrationTest {
  open var type: AndroidxSqliteDatabaseType = AndroidxSqliteDatabaseType.File("test.db")

  @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
  private fun readDispatcher(): CoroutineDispatcher? = when {
    configuration.readerConnectionsCount >= 1 -> newFixedThreadPoolContext(
      nThreads = configuration.readerConnectionsCount,
      name = "db-reads",
    )
    else -> null
  }

  open var configuration = AndroidxSqliteConfiguration()
    set(value) {
      field = value
      readDispatcher = readDispatcher()
    }

  private var readDispatcher: CoroutineDispatcher? = readDispatcher()

  @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
  val writeDispatcher: CoroutineDispatcher = newSingleThreadContext("db-writes")

  suspend inline fun AndroidXDb.withTransaction(
    crossinline transactionBlock: TransactionWithoutReturn.() -> Unit,
  ) {
    withContext(writeDispatcher) {
      transaction {
        transactionBlock()
      }
    }
  }

  fun <T : Any> Flow<Query<T>>.mapToOne(): Flow<T> = mapToOne(readDispatcher ?: writeDispatcher)
  fun <T : Any> Flow<Query<T>>.mapToOneNotNull(): Flow<T> = mapToOneNotNull(readDispatcher ?: writeDispatcher)

  val driver by lazy {
    AndroidxSqliteDriver(
      createConnection = { name ->
        BundledSQLiteDriver().open(name)
      },
      databaseType = type,
      schema = AndroidXDb.Schema,
      configuration = configuration,
    )
  }

  val database by lazy {
    AndroidXDb(driver = driver)
  }

  @AfterTest
  fun cleanup() {
    driver.close()

    (type as? AndroidxSqliteDatabaseType.File)?.let { type ->
      val dbName = type.databaseFilePath
      deleteFile(dbName)
      deleteFile("$dbName-shm")
      deleteFile("$dbName-wal")
    }
  }
}
