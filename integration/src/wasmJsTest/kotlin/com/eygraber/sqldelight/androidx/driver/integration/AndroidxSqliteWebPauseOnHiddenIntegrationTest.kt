@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver.integration

import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.w3c.dom.Worker
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AndroidxSqliteWebPauseOnHiddenIntegrationTest {
  private val dbName = "integration-pauseonhidden-${Random.nextULong()}.db"
  private val worker by lazy { newTestWorker(OpfsMultiTabMode.PauseOnHidden) }
  private val database by lazy {
    AndroidXDb(
      AndroidxSqliteDriver(
        driver = WebWorkerSQLiteDriver(worker),
        databaseType = AndroidxSqliteDatabaseType.File(dbName),
        schema = AndroidXDb.Schema,
      ),
    )
  }

  @AfterTest
  fun cleanup() {
    terminateTestWorkers()
  }

  @Test
  fun insertedRowsAreVisibleViaSqlDelightGeneratedQueries() = runTest {
    awaitOpfsRelease()
    releaseForegroundLock()
    database.transaction {
      database.recordQueries.insert(
        userId = "pause-1",
        withRecord = byteArrayOf(0x01, 0x02, 0x03),
      )
    }

    val all = database.recordQueries.countForUser(whereUserId = "pause-1").awaitAsOne()
    assertEquals(1L, all)

    val records = database.recordQueries.top().awaitAsList()
    assertEquals(1, records.size)
    assertEquals("pause-1", records.first().userId)
  }

  @Test
  fun pauseDuringTransactionWaitsForTheTransactionToEnd() = runTest {
    awaitOpfsRelease()
    releaseForegroundLock()
    val transaction = async(Dispatchers.Default) {
      database.transaction {
        database.recordQueries.insert(
          userId = "pause-2",
          withRecord = byteArrayOf(0x01),
        )
        postOpfsPause(worker)
        database.recordQueries.insert(
          userId = "pause-2",
          withRecord = byteArrayOf(0x02),
        )
      }
    }
    withContext(Dispatchers.Default) { delay(1_500) }
    postOpfsResume(worker)
    transaction.await()

    val all = database.recordQueries.countForUser(whereUserId = "pause-2").awaitAsOne()
    assertEquals(2L, all)
  }

  @Test
  fun pauseOutsideTransactionQueuesQueriesUntilResume() = runTest {
    awaitOpfsRelease()
    releaseForegroundLock()
    database.transaction {
      database.recordQueries.insert(
        userId = "pause-3",
        withRecord = byteArrayOf(0x01),
      )
    }
    postOpfsPause(worker)
    val pending = async(Dispatchers.Default) {
      database.recordQueries.countForUser(whereUserId = "pause-3").awaitAsOne()
    }
    withContext(Dispatchers.Default) { delay(1_500) }
    assertFalse(pending.isCompleted)

    postOpfsResume(worker)
    assertEquals(1L, pending.await())
  }
}

@JsFun("(worker) => worker.postMessage({ __opfsPause: true })")
private external fun postOpfsPause(worker: Worker)

@JsFun("(worker) => worker.postMessage({ __opfsResume: true })")
private external fun postOpfsResume(worker: Worker)
