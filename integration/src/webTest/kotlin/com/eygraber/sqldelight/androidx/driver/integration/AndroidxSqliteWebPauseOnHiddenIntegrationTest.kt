package com.eygraber.sqldelight.androidx.driver.integration

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsLockState
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AndroidxSqliteWebPauseOnHiddenIntegrationTest {
  private val dbName = "integration-pauseonhidden-${Random.nextULong()}.db"
  private val driver by lazy { newTestDriver(OpfsMultiTabMode.PauseOnHidden) }
  private val worker by lazy { driver.opfsWorker.worker }
  private val database by lazy { newDatabase(driver) }

  private fun newDatabase(driver: OpfsSqliteDriver) = AndroidXDb(newTestSqlDriver(driver, dbName))

  @AfterTest
  fun cleanup() {
    closeTestDrivers()
  }

  @Test
  fun insertedRowsAreVisibleViaSqlDelightGeneratedQueries() = runTest {
    awaitOpfsRelease()
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

  @Test
  fun workerAcksPauseAndResumeOnTheControlPort() = runTest(timeout = 60.seconds) {
    awaitOpfsRelease()
    val control = attachTestControlPort(worker)
    assertEquals("__opfsResumedAck", awaitControlMessage(control))

    database.transaction {
      database.recordQueries.insert(
        userId = "pause-4",
        withRecord = byteArrayOf(0x01),
      )
    }

    postOpfsPause(worker)
    assertEquals("__opfsPausedAck", awaitControlMessage(control))

    postOpfsResume(worker)
    assertEquals("__opfsResumedAck", awaitControlMessage(control))
    assertEquals(1L, database.recordQueries.countForUser(whereUserId = "pause-4").awaitAsOne())
  }

  @Test
  fun resumeFailureIsReportedAndALaterResumeSucceeds() = runTest(timeout = 90.seconds) {
    awaitOpfsRelease()
    val holder = newTestDriver(OpfsMultiTabMode.Single)
    val holderDatabase = newDatabase(holder)
    holderDatabase.transaction {
      holderDatabase.recordQueries.insert(
        userId = "pause-5",
        withRecord = byteArrayOf(0x01),
      )
    }

    val control = attachTestControlPort(worker)
    postOpfsResume(worker)
    val failure = awaitControlMessage(control)
    assertTrue(failure.startsWith("__opfsResumeFailed:"), failure)

    holder.close()
    withContext(Dispatchers.Default) { delay(500) }
    postOpfsResume(worker)
    assertEquals("__opfsResumedAck", awaitControlMessage(control))
    assertEquals(1L, database.recordQueries.countForUser(whereUserId = "pause-5").awaitAsOne())
  }

  @Test
  fun lockStateReachesLiveOnlyAfterTheWorkerCanServeQueries() = runTest(timeout = 90.seconds) {
    awaitOpfsRelease()
    val holder = newTestDriver(OpfsMultiTabMode.Single)
    val holderDatabase = newDatabase(holder)
    holderDatabase.transaction {
      holderDatabase.recordQueries.insert(
        userId = "pause-6",
        withRecord = byteArrayOf(0x01),
      )
    }

    val states = mutableListOf<OpfsLockState>()
    val observed = newTestDriver(OpfsMultiTabMode.PauseOnHidden) { states.add(it) }
    assertEquals(listOf(OpfsLockState.Paused), states)
    val observedDatabase = newDatabase(observed)
    val pending = async(Dispatchers.Default) {
      observedDatabase.recordQueries.countForUser(whereUserId = "pause-6").awaitAsOne()
    }
    withContext(Dispatchers.Default) { delay(6_000) }
    assertEquals(listOf(OpfsLockState.Paused), states)
    assertFalse(pending.isCompleted)

    holder.close()
    val count = inRealTime { pending.await() }
    assertEquals(1L, count)
    awaitLockState(states, OpfsLockState.Live)
    assertEquals(listOf(OpfsLockState.Paused, OpfsLockState.Live), states)
  }
}

private suspend fun awaitControlMessage(control: TestControlPort): String = inRealTime { control.next() }
