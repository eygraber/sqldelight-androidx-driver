@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver.integration

import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsLockState
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.w3c.dom.Worker
import kotlin.js.Promise
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
  private val worker by lazy { newTestWorker(OpfsMultiTabMode.PauseOnHidden) }
  private val database by lazy { newDatabase(worker) }

  private fun newDatabase(worker: Worker) = AndroidXDb(
    AndroidxSqliteDriver(
      driver = WebWorkerSQLiteDriver(worker),
      databaseType = AndroidxSqliteDatabaseType.File(dbName),
      schema = AndroidXDb.Schema,
    ),
  )

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

  @Test
  fun workerAcksPauseAndResumeOnTheControlPort() = runTest(timeout = 60.seconds) {
    awaitOpfsRelease()
    releaseForegroundLock()
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
    releaseForegroundLock()
    val holder = newTestWorker(OpfsMultiTabMode.Single)
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

    holder.terminate()
    withContext(Dispatchers.Default) { delay(500) }
    postOpfsResume(worker)
    assertEquals("__opfsResumedAck", awaitControlMessage(control))
    assertEquals(1L, database.recordQueries.countForUser(whereUserId = "pause-5").awaitAsOne())
  }

  @Test
  fun lockStateReachesLiveOnlyAfterTheWorkerCanServeQueries() = runTest(timeout = 90.seconds) {
    awaitOpfsRelease()
    releaseForegroundLock()
    val holder = newTestWorker(OpfsMultiTabMode.Single)
    val holderDatabase = newDatabase(holder)
    holderDatabase.transaction {
      holderDatabase.recordQueries.insert(
        userId = "pause-6",
        withRecord = byteArrayOf(0x01),
      )
    }

    val states = mutableListOf<OpfsLockState>()
    val observed = newTestWorker(OpfsMultiTabMode.PauseOnHidden) { states.add(it) }
    assertEquals(listOf(OpfsLockState.Paused), states)
    val observedDatabase = newDatabase(observed)
    val pending = async(Dispatchers.Default) {
      observedDatabase.recordQueries.countForUser(whereUserId = "pause-6").awaitAsOne()
    }
    withContext(Dispatchers.Default) { delay(6_000) }
    assertEquals(listOf(OpfsLockState.Paused), states)
    assertFalse(pending.isCompleted)

    holder.terminate()
    val count = withContext(Dispatchers.Default) {
      withTimeout(REAL_TIME_LIMIT_MS) { pending.await() }
    }
    assertEquals(1L, count)
    withContext(Dispatchers.Default) {
      withTimeout(REAL_TIME_LIMIT_MS) {
        while(states.lastOrNull() != OpfsLockState.Live) delay(50)
      }
    }
    assertEquals(listOf(OpfsLockState.Paused, OpfsLockState.Live), states)
  }
}

private const val REAL_TIME_LIMIT_MS = 20_000L

private suspend fun awaitControlMessage(control: JsAny): String = withContext(Dispatchers.Default) {
  withTimeout(REAL_TIME_LIMIT_MS) { nextControlMessage(control).await<JsString>().toString() }
}

@JsFun("(worker) => worker.postMessage({ __opfsPause: true })")
private external fun postOpfsPause(worker: Worker)

@JsFun("(worker) => worker.postMessage({ __opfsResume: true })")
private external fun postOpfsResume(worker: Worker)

@JsFun(
  """(worker) => {
    const channel = new MessageChannel();
    worker.postMessage({ __opfsControlPort: channel.port2 }, [channel.port2]);
    const queue = [];
    const waiters = [];
    channel.port1.onmessage = (ev) => {
      const data = ev.data || {};
      const key = Object.keys(data).find((k) => k.startsWith('__opfs')) || '';
      const value = key === '__opfsResumeFailed' ? key + ':' + String(data[key]) : key;
      if (waiters.length) waiters.shift()(value); else queue.push(value);
    };
    return {
      next: () => queue.length ? Promise.resolve(queue.shift()) : new Promise((r) => waiters.push(r))
    };
  }""",
)
private external fun attachTestControlPort(worker: Worker): JsAny

@JsFun("(control) => control.next()")
private external fun nextControlMessage(control: JsAny): Promise<JsString>
