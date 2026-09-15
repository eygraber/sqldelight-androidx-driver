package com.eygraber.sqldelight.androidx.driver.integration

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsLockState
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AndroidxSqliteWebLifecycleIntegrationTest {
  private val dbName = "integration-lifecycle-${Random.nextULong()}.db"

  @AfterTest
  fun cleanup() {
    closeTestDrivers()
  }

  @Test
  fun closingAPauseOnHiddenDriverReleasesTheForegroundLock() = runTest(timeout = 60.seconds) {
    awaitOpfsRelease()
    val firstStates = mutableListOf<OpfsLockState>()
    val first = newTestSqlDriver(newTestDriver(OpfsMultiTabMode.PauseOnHidden) { firstStates.add(it) }, dbName)
    val firstDatabase = AndroidXDb(first)
    firstDatabase.transaction {
      firstDatabase.recordQueries.insert(userId = "lifecycle-1", withRecord = byteArrayOf(0x01))
    }
    awaitLockState(firstStates, OpfsLockState.Live)
    first.close()

    val secondStates = mutableListOf<OpfsLockState>()
    val second = newTestSqlDriver(newTestDriver(OpfsMultiTabMode.PauseOnHidden) { secondStates.add(it) }, dbName)
    val secondDatabase = AndroidXDb(second)
    val count = inRealTime { secondDatabase.recordQueries.countForUser(whereUserId = "lifecycle-1").awaitAsOne() }
    assertEquals(1L, count)
    awaitLockState(secondStates, OpfsLockState.Live)
    assertEquals(listOf(OpfsLockState.Paused, OpfsLockState.Live), secondStates)
    assertEquals(listOf(OpfsLockState.Paused, OpfsLockState.Live), firstStates)
  }

  @Test
  fun closingADriverTwiceIsANoOp() = runTest {
    awaitOpfsRelease()
    val opfsDriver = newTestDriver(OpfsMultiTabMode.PauseOnHidden)
    val driver = newTestSqlDriver(opfsDriver, dbName)
    val database = AndroidXDb(driver)
    database.transaction {
      database.recordQueries.insert(userId = "lifecycle-2", withRecord = byteArrayOf(0x01))
    }

    driver.close()
    driver.close()
    opfsDriver.close()
    assertTrue(opfsDriver.opfsWorker.isClosed)

    val reopened = AndroidXDb(newTestSqlDriver(newTestDriver(OpfsMultiTabMode.PauseOnHidden), dbName))
    assertEquals(1L, inRealTime { reopened.recordQueries.countForUser(whereUserId = "lifecycle-2").awaitAsOne() })
  }

  @Test
  fun queriesFailAfterClose() = runTest {
    awaitOpfsRelease()
    val driver = newTestSqlDriver(newTestDriver(OpfsMultiTabMode.Single), dbName)
    val database = AndroidXDb(driver)
    database.transaction {
      database.recordQueries.insert(userId = "lifecycle-3", withRecord = byteArrayOf(0x01))
    }

    driver.close()

    val failure = inRealTime {
      runCatching { database.recordQueries.countForUser(whereUserId = "lifecycle-3").awaitAsOne() }.exceptionOrNull()
    }
    assertIs<IllegalStateException>(failure)
  }

  @Test
  fun queuedQueriesFailWhenAPausedDriverCloses() = runTest(timeout = 60.seconds) {
    awaitOpfsRelease()
    val holderDatabase = AndroidXDb(newTestSqlDriver(newTestDriver(OpfsMultiTabMode.Single), dbName))
    holderDatabase.transaction {
      holderDatabase.recordQueries.insert(userId = "lifecycle-4", withRecord = byteArrayOf(0x01))
    }

    val paused = newTestSqlDriver(newTestDriver(OpfsMultiTabMode.PauseOnHidden), dbName)
    val pausedDatabase = AndroidXDb(paused)
    val pending = async(Dispatchers.Default) {
      runCatching { pausedDatabase.recordQueries.countForUser(whereUserId = "lifecycle-4").awaitAsOne() }
    }
    withContext(Dispatchers.Default) { delay(1_500) }
    assertFalse(pending.isCompleted)

    paused.close()

    val result = inRealTime { pending.await() }
    assertTrue(result.isFailure, "expected the queued query to fail, got $result")
  }
}
