package com.eygraber.sqldelight.androidx.driver.integration

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.SuspendingTransactionWithoutReturn
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import kotlin.test.AfterTest

abstract class AndroidxSqliteIntegrationTest {
  abstract var type: AndroidxSqliteDatabaseType

  suspend inline fun AndroidXDb.withTransaction(
    crossinline transactionBlock: suspend SuspendingTransactionWithoutReturn.() -> Unit,
  ) {
    transaction {
      transactionBlock()
    }
  }

  protected open fun createConfiguration() = AndroidxSqliteConfiguration()

  val driver by lazy {
    AndroidxSqliteDriver(
      driver = BundledSQLiteDriver(),
      databaseType = type,
      schema = AndroidXDb.Schema,
      configuration = createConfiguration(),
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
