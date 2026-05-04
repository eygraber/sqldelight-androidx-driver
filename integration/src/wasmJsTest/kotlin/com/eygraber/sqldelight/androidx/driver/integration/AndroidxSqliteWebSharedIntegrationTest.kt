@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver.integration

import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidxSqliteWebSharedIntegrationTest {
  private val dbName = "integration-shared-${Random.nextULong()}.db"
  private val worker = freshTestWorker(OpfsMultiTabMode.Shared)
  private val driver = AndroidxSqliteDriver(
    driver = WebWorkerSQLiteDriver(worker),
    databaseType = AndroidxSqliteDatabaseType.File(dbName),
    schema = AndroidXDb.Schema,
  )
  private val database = AndroidXDb(driver)

  @AfterTest
  fun cleanup() = runTest {
    driver.close()
    terminateAndSettleTestWorkers()
    deleteFile(dbName)
    deleteFile("$dbName-shm")
    deleteFile("$dbName-wal")
  }

  @Test
  fun insertedRowsAreVisibleViaSqlDelightGeneratedQueries() = runTest {
    database.transaction {
      database.recordQueries.insert(
        userId = "shared-1",
        withRecord = byteArrayOf(0x01, 0x02, 0x03),
      )
    }

    val all = database.recordQueries.countForUser(whereUserId = "shared-1").awaitAsOne()
    assertEquals(1L, all)

    val records = database.recordQueries.top().awaitAsList()
    assertEquals(1, records.size)
    assertEquals("shared-1", records.first().userId)
  }
}
