@file:Suppress("UnsafeCastFromDynamic")

package com.eygraber.sqldelight.androidx.driver.opfs

import com.eygraber.sqldelight.androidx.driver.opfs.generated.OPFS_WORKER_SOURCE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@JsModule("./init-retry-fixture.mjs")
@JsNonModule
@Suppress("UnusedParameter")
private external object InitRetryFixtures {
  fun createInitRetryFixture(source: String, mode: String, options: dynamic): InitRetryFixture
}

private external interface InitRetryFixture {
  fun event(name: String): Promise<dynamic>
  fun control(name: String): Promise<dynamic>
  fun open(): Promise<dynamic>
  fun stats(): Promise<dynamic>
  fun pause()
  fun resume()
  fun close()
  fun releaseFactory()
  fun releasePool()
  fun dispose()
}

class OpfsWorkerInitRetryTest {
  @Test
  fun failedImportsUseFreshModuleIdentities() = withWorker(options = js("({importFailures: 2})")) { worker ->
    val response = worker.open().awaitDynamic()
    assertNotNull(response.data)
    val urls = (0..2).map { worker.event("import").awaitDynamic().url as String }
    assertEquals(3, urls.toSet().size)
    assertTrue(urls[0].endsWith("#original"))
    assertTrue(urls[1].endsWith("#original-retry1"))
    assertTrue(urls[2].endsWith("#original-retry2"))
    assertEquals(250, worker.event("retry").awaitDynamic().delay as Int)
    assertEquals(500, worker.event("retry").awaitDynamic().delay as Int)
    val stats = worker.stats().awaitDynamic()
    assertEquals(1, stats.factories as Int)
    assertEquals(1, stats.pools as Int)
  }

  @Test
  fun wasmRetriesReuseTheImportedModule() = withWorker(options = js("({factoryFailures: 2})")) { worker ->
    assertNotNull(worker.open().awaitDynamic().data)
    val stats = worker.stats().awaitDynamic()
    assertEquals(1, stats.imports as Int)
    assertEquals(3, stats.factories as Int)
    assertEquals(1, stats.pools as Int)
  }

  @Test
  fun synchronousFactoryErrorsAreRetried() = withWorker(options = js("({factoryThrowFailures: 1})")) { worker ->
    assertNotNull(worker.open().awaitDynamic().data)
    val stats = worker.stats().awaitDynamic()
    assertEquals(1, stats.imports as Int)
    assertEquals(2, stats.factories as Int)
  }

  @Test
  fun poolFailuresRejectRequestsAndRecoverWithoutReinstallingWasm() =
    withWorker(options = js("({poolFailures: 12})")) { worker ->
      assertEquals("fixture pool failed", worker.open().awaitDynamic().error as String)
      assertNotNull(worker.open().awaitDynamic().data)
      val stats = worker.stats().awaitDynamic()
      assertEquals(1, stats.imports as Int)
      assertEquals(1, stats.factories as Int)
      assertEquals(13, stats.pools as Int)
    }

  @Test
  fun realSqliteRecoversAfterAnInterruptedWasmFetch() = runTest(timeout = 30.seconds) {
    val options = js("({wasmFetchFailures: 2, wasmXhrFailures: 1})")
    options.sqlite3Url = resolveSqliteWasmUrl()
    options.wasmUrl = resolveSqliteWasmCompanionUrl()
    withContext(Dispatchers.Default) {
      withWorkerFixture("Single", options) { worker ->
        assertNotNull(worker.open().awaitDynamic().data)
        val stats = worker.stats().awaitDynamic()
        assertEquals(1, stats.imports as Int)
        assertEquals(2, stats.factories as Int)
        val wasmFetches = stats.wasmFetches as Int
        assertTrue(wasmFetches >= 3)
        worker.close()
        awaitPromptAck(worker, "__opfsClosedAck")
      }
    }
  }

  @Test
  fun concurrentRequestsShareInitialization() = withWorker(options = js("({factoryFailures: 1})")) { worker ->
    val first = worker.open()
    val second = worker.open()
    assertNotNull(first.awaitDynamic().data)
    assertNotNull(second.awaitDynamic().data)
    val stats = worker.stats().awaitDynamic()
    assertEquals(1, stats.imports as Int)
    assertEquals(2, stats.factories as Int)
    assertEquals(1, stats.pools as Int)
  }

  @Test
  fun singleModeFailsQueuedRequestsAndRecoversOnTheNextRequest() =
    withWorker(options = js("({importFailures: 6})")) { worker ->
      val first = worker.open()
      val second = worker.open()
      assertEquals("fixture import failed", first.awaitDynamic().error as String)
      assertEquals("fixture import failed", second.awaitDynamic().error as String)
      val delays = (0 until 5).map { worker.event("retry").awaitDynamic().delay as Int }
      assertEquals(listOf(250, 500, 1_000, 2_000, 4_000), delays)
      assertNotNull(worker.open().awaitDynamic().data)
      assertEquals(7, worker.stats().awaitDynamic().imports as Int)
    }

  @Test
  fun recoveryAfterWasmFailureRetainsTheModule() = withWorker(options = js("({factoryFailures: 6})")) { worker ->
    assertEquals("fixture wasm failed", worker.open().awaitDynamic().error as String)
    assertNotNull(worker.open().awaitDynamic().data)
    val stats = worker.stats().awaitDynamic()
    assertEquals(1, stats.imports as Int)
    assertEquals(7, stats.factories as Int)
  }

  @Test
  fun pauseCancelsImportBackoffAndResumeUsesAFreshIdentity() =
    withWorker("PauseOnHidden", js("({importFailures: 1, timerFloorMs: 5000})")) { worker ->
      val pending = worker.open()
      worker.resume()
      worker.event("retry").awaitDynamic()
      worker.pause()
      awaitPromptAck(worker, "__opfsPausedAck")
      assertEquals(1, worker.stats().awaitDynamic().imports as Int)
      worker.resume()
      worker.control("__opfsResumedAck").awaitDynamic()
      assertNotNull(pending.awaitDynamic().data)
      val stats = worker.stats().awaitDynamic()
      assertEquals(2, stats.imports as Int)
      assertEquals(1, stats.pools as Int)
    }

  @Test
  fun pauseCancelsPoolBackoffWithoutReinstallingWasm() =
    withWorker("PauseOnHidden", js("({poolFailures: 1, timerFloorMs: 5000})")) { worker ->
      worker.resume()
      worker.event("retry").awaitDynamic()
      worker.pause()
      awaitPromptAck(worker, "__opfsPausedAck")
      assertEquals(1, worker.stats().awaitDynamic().pools as Int)
      worker.resume()
      worker.control("__opfsResumedAck").awaitDynamic()
      val stats = worker.stats().awaitDynamic()
      assertEquals(1, stats.imports as Int)
      assertEquals(1, stats.factories as Int)
      assertEquals(2, stats.pools as Int)
    }

  @Test
  fun pauseDuringFactoryInstallWaitsForSettlementAndAvoidsClaimingHandles() =
    withWorker("PauseOnHidden", js("({holdFactory: true})")) { worker ->
      worker.resume()
      worker.event("factory").awaitDynamic()
      worker.pause()
      worker.releaseFactory()
      awaitPromptAck(worker, "__opfsPausedAck")
      assertEquals(0, worker.stats().awaitDynamic().pools as Int)
      worker.resume()
      worker.control("__opfsResumedAck").awaitDynamic()
      assertEquals(1, worker.stats().awaitDynamic().factories as Int)
    }

  @Test
  fun failedResumeRejectsQueuedRequestsAndCanResumeAgain() =
    withWorker("PauseOnHidden", js("({importFailures: 6})")) { worker ->
      val pending = worker.open()
      worker.resume()
      assertEquals("fixture import failed", pending.awaitDynamic().error as String)
      worker.control("__opfsResumeFailed").awaitDynamic()
      val next = worker.open()
      worker.resume()
      worker.control("__opfsResumedAck").awaitDynamic()
      assertNotNull(next.awaitDynamic().data)
    }

  @Test
  fun ordinaryOpfsContentionPreservesPausedQueries() =
    withWorker("PauseOnHidden", js("({poolFailures: 12, poolErrorName: 'NoModificationAllowedError'})")) { worker ->
      var replied = false
      val pending = worker.open()
      pending.then<Unit> { replied = true }
      worker.resume()
      worker.control("__opfsResumeFailed").awaitDynamic()
      worker.stats().awaitDynamic()
      assertFalse(replied)
      worker.resume()
      worker.control("__opfsResumedAck").awaitDynamic()
      assertNotNull(pending.awaitDynamic().data)
    }

  @Test
  fun closeCancelsBackoffAndRejectsRequests() =
    withWorker(options = js("({importFailures: 6, timerFloorMs: 5000})")) { worker ->
      val pending = worker.open()
      worker.event("retry").awaitDynamic()
      worker.close()
      awaitPromptAck(worker, "__opfsClosedAck")
      assertEquals("The OPFS worker is closed.", pending.awaitDynamic().error as String)
      assertEquals(1, worker.stats().awaitDynamic().imports as Int)
      assertEquals("The OPFS worker is closed.", worker.open().awaitDynamic().error as String)
    }

  @Test
  fun closeDuringPoolInstallWaitsAndDoesNotServeQueuedQueries() =
    withWorker(options = js("({holdPool: true})")) { worker ->
      val pending = worker.open()
      worker.event("pool").awaitDynamic()
      var closed = false
      val ack = worker.control("__opfsClosedAck")
      ack.then<Unit> { closed = true }
      worker.close()
      worker.stats().awaitDynamic()
      assertFalse(closed)
      worker.releasePool()
      withTimeout(1_000) { ack.await() }
      assertEquals("The OPFS worker is closed.", pending.awaitDynamic().error as String)
    }

  @Test
  fun sharedModeFailsFollowersAndYieldsToAHealthyCandidate() =
    withWorker("Shared", js("({importFailures: 6})")) { broken ->
      broken.event("import").awaitDynamic()
      withWorkerFixture("Shared", js("({})")) { healthy ->
        val brokenRequest = broken.open()
        val healthyRequest = healthy.open()
        assertEquals("fixture import failed", brokenRequest.awaitDynamic().error as String)
        assertEquals("fixture import failed", healthyRequest.awaitDynamic().error as String)
        assertNotNull(healthy.open().awaitDynamic().data)
        assertNotNull(broken.open().awaitDynamic().data)
        assertEquals(1, healthy.stats().awaitDynamic().pools as Int)
      }
    }

  @Test
  fun failedReplacementRejectsAlreadyForwardedRequests() =
    withWorker("Shared", js("({holdSharedRequests: true})")) { leader ->
      assertNotNull(leader.open().awaitDynamic().data)
      withWorkerFixture("Shared", js("({importFailures: 6})")) { broken ->
        val brokenRequest = broken.open()
        leader.event("forwarded").awaitDynamic()
        withWorkerFixture("Shared", js("({})")) { healthy ->
          val healthyRequest = healthy.open()
          leader.event("forwarded").awaitDynamic()
          leader.dispose()
          assertEquals("fixture import failed", brokenRequest.awaitDynamic().error as String)
          assertEquals("fixture import failed", healthyRequest.awaitDynamic().error as String)
          assertNotNull(healthy.open().awaitDynamic().data)
          assertNotNull(broken.open().awaitDynamic().data)
        }
      }
    }

  @Test
  fun sharedModeRecoversWithoutAnotherTab() = withWorker("Shared", js("({importFailures: 6})")) { worker ->
    assertEquals("fixture import failed", worker.open().awaitDynamic().error as String)
    assertNotNull(worker.open().awaitDynamic().data)
    assertEquals(7, worker.stats().awaitDynamic().imports as Int)
  }
}

private fun withWorker(
  mode: String = "Single",
  options: dynamic = js("({})"),
  block: suspend (InitRetryFixture) -> Unit,
) = runTest(timeout = 30.seconds) {
  withContext(Dispatchers.Default) { withWorkerFixture(mode, options, block) }
}

private suspend fun withWorkerFixture(mode: String, options: dynamic, block: suspend (InitRetryFixture) -> Unit) {
  val worker = InitRetryFixtures.createInitRetryFixture(OPFS_WORKER_SOURCE, mode, options)
  try {
    block(worker)
  }
  finally {
    worker.dispose()
  }
}

private suspend fun Promise<dynamic>.awaitDynamic(): dynamic = await()

private suspend fun awaitPromptAck(worker: InitRetryFixture, name: String) {
  // The fixture holds retry timers for five seconds. Pause/close must ack without waiting for them.
  withTimeout(1_000) { worker.control(name).await() }
}
