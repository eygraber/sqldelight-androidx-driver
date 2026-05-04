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

class AndroidxSqliteWebSharedMultiWorkerTest {
  private val dbName = "integration-shared-multi-${Random.nextULong()}.db"

  private val workerA = freshTestWorker(OpfsMultiTabMode.Shared)
  private val driverA = AndroidxSqliteDriver(
    driver = WebWorkerSQLiteDriver(workerA),
    databaseType = AndroidxSqliteDatabaseType.File(dbName),
    schema = AndroidXDb.Schema,
  )
  private val databaseA = AndroidXDb(driverA)

  private val workerB = additionalTestWorker(OpfsMultiTabMode.Shared)
  private val driverB = AndroidxSqliteDriver(
    driver = WebWorkerSQLiteDriver(workerB),
    databaseType = AndroidxSqliteDatabaseType.File(dbName),
    schema = AndroidXDb.Schema,
  )
  private val databaseB = AndroidXDb(driverB)

  @AfterTest
  fun cleanup() = runTest {
    driverA.close()
    driverB.close()
    terminateAndSettleTestWorkers()
    deleteFile(dbName)
    deleteFile("$dbName-shm")
    deleteFile("$dbName-wal")
  }

  @Test
  fun followerReadsRowsWrittenByLeader() = runTest {
    databaseA.transaction {
      databaseA.recordQueries.insert(
        userId = "shared-leader",
        withRecord = byteArrayOf(0x10, 0x11, 0x12),
      )
    }

    val countViaB = databaseB.recordQueries.countForUser(whereUserId = "shared-leader").awaitAsOne()
    assertEquals(1L, countViaB)

    val recordsViaB = databaseB.recordQueries.top().awaitAsList()
    assertEquals(1, recordsViaB.size)
    assertEquals("shared-leader", recordsViaB.first().userId)
  }

  @Test
  fun leaderReadsRowsWrittenByFollower() = runTest {
    // Force leader election to complete by routing one query through driverA first.
    databaseA.recordQueries.countForUser(whereUserId = "warmup").awaitAsOne()

    databaseB.transaction {
      databaseB.recordQueries.insert(
        userId = "shared-follower",
        withRecord = byteArrayOf(0x20, 0x21, 0x22),
      )
    }

    val countViaA = databaseA.recordQueries.countForUser(whereUserId = "shared-follower").awaitAsOne()
    assertEquals(1L, countViaA)
  }
}
