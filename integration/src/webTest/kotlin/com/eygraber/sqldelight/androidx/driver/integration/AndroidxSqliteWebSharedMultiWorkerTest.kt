package com.eygraber.sqldelight.androidx.driver.integration

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.w3c.dom.Worker
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class AndroidxSqliteWebSharedMultiWorkerTest {
  private val dbName = "integration-shared-multi-${Random.nextULong()}.db"

  private val opfsDriverA by lazy { newTestDriver(OpfsMultiTabMode.Shared) }
  private val driverA by lazy { newTestSqlDriver(opfsDriverA, dbName) }
  private val databaseA by lazy { AndroidXDb(driverA) }

  private val opfsDriverB by lazy { newTestDriver(OpfsMultiTabMode.Shared) }
  private val driverB by lazy { newTestSqlDriver(opfsDriverB, dbName) }
  private val databaseB by lazy { AndroidXDb(driverB) }

  @AfterTest
  fun cleanup() {
    closeTestDrivers()
  }

  @Test
  fun followerReadsRowsWrittenByLeader() = runTest {
    awaitOpfsRelease()
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
    awaitOpfsRelease()
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

  @Test
  fun concurrentTransactionsFromBothWorkersDoNotInterleave() = runTest(timeout = 120.seconds) {
    awaitOpfsRelease()
    val iterations = 12
    val insertsPerTransaction = 3
    val jobs = listOf(databaseA, databaseB).map { database ->
      launch {
        repeat(iterations) { i ->
          database.transaction {
            repeat(insertsPerTransaction) { n ->
              database.recordQueries.insert(
                userId = "concurrent",
                withRecord = byteArrayOf(i.toByte(), n.toByte()),
              )
            }
            if(i % 3 == 2) rollback()
          }
        }
      }
    }
    jobs.joinAll()

    val committedTransactions = (0 until iterations).count { it % 3 != 2 }
    val expected = (committedTransactions * insertsPerTransaction * 2).toLong()
    assertEquals(expected, databaseA.recordQueries.countForUser(whereUserId = "concurrent").awaitAsOne())
    assertEquals(expected, databaseB.recordQueries.countForUser(whereUserId = "concurrent").awaitAsOne())
  }

  @Test
  fun closingTheLeaderElectsAFollowerThatServesQueries() = runTest(timeout = 60.seconds) {
    awaitOpfsRelease()
    databaseA.transaction {
      databaseA.recordQueries.insert(userId = "handoff", withRecord = byteArrayOf(0x01))
    }
    assertEquals(1L, databaseB.recordQueries.countForUser(whereUserId = "handoff").awaitAsOne())

    driverA.close()

    val count = inRealTime { databaseB.recordQueries.countForUser(whereUserId = "handoff").awaitAsOne() }
    assertEquals(1L, count)
    databaseB.transaction {
      databaseB.recordQueries.insert(userId = "handoff", withRecord = byteArrayOf(0x02))
    }
    assertEquals(2L, databaseB.recordQueries.countForUser(whereUserId = "handoff").awaitAsOne())

    val databaseC = AndroidXDb(newTestSqlDriver(newTestDriver(OpfsMultiTabMode.Shared), dbName))
    assertEquals(2L, inRealTime { databaseC.recordQueries.countForUser(whereUserId = "handoff").awaitAsOne() })
  }

  @Test
  fun leaderRollsBackWhenTheOwnerWorkerTerminates() = runTest(timeout = 60.seconds) {
    awaitOpfsRelease()
    databaseA.recordQueries.countForUser(whereUserId = "warmup").awaitAsOne()

    val insertedByB = CompletableDeferred<Unit>()
    val stall = CompletableDeferred<Unit>()
    val orphan = launch {
      databaseB.transaction {
        databaseB.recordQueries.insert(userId = "orphan", withRecord = byteArrayOf(0x01))
        insertedByB.complete(Unit)
        stall.await()
      }
    }
    insertedByB.await()
    opfsDriverB.opfsWorker.worker.terminate()
    orphan.cancel()

    databaseA.transaction {
      databaseA.recordQueries.insert(userId = "survivor", withRecord = byteArrayOf(0x02))
    }

    assertEquals(0L, databaseA.recordQueries.countForUser(whereUserId = "orphan").awaitAsOne())
    assertEquals(1L, databaseA.recordQueries.countForUser(whereUserId = "survivor").awaitAsOne())
  }

  @Test
  fun leaderEvictsAFollowerThatTerminatesWithoutClose() = runTest(timeout = 60.seconds) {
    awaitOpfsRelease()
    databaseA.recordQueries.countForUser(whereUserId = "warmup").awaitAsOne()
    val leaderWorker = opfsDriverA.opfsWorker.worker
    val followerCounts = attachFollowerCountPort(leaderWorker)

    repeat(3) { i ->
      databaseB.recordQueries.countForUser(whereUserId = "evicted-$i").awaitAsOne()
    }
    databaseB.transaction {
      databaseB.recordQueries.insert(userId = "evicted", withRecord = byteArrayOf(0x01))
    }
    assertEquals(2, followerCount(leaderWorker, followerCounts))

    opfsDriverB.opfsWorker.worker.terminate()

    inRealTime {
      while(followerCount(leaderWorker, followerCounts) != 1) delay(50)
    }

    assertEquals(1L, databaseA.recordQueries.countForUser(whereUserId = "evicted").awaitAsOne())
    databaseA.transaction {
      databaseA.recordQueries.insert(userId = "evicted", withRecord = byteArrayOf(0x02))
    }

    val databaseC = AndroidXDb(newTestSqlDriver(newTestDriver(OpfsMultiTabMode.Shared), dbName))
    assertEquals(2L, inRealTime { databaseC.recordQueries.countForUser(whereUserId = "evicted").awaitAsOne() })
    assertEquals(2, followerCount(leaderWorker, followerCounts))
  }
}

private suspend fun followerCount(worker: Worker, control: TestFollowerCountPort): Int = inRealTime {
  postFollowerCountRequest(worker)
  control.next()
}
