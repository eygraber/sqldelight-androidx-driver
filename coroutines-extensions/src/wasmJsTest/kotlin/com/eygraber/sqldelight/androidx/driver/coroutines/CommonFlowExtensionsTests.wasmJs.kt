@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver.coroutines

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import com.eygraber.sqldelight.androidx.driver.opfs.opfsWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.w3c.dom.Worker
import kotlin.test.AfterTest

actual class CommonFlowExtensionsTest : FlowExtensionsTest() {
  // Worker.terminate() is asynchronous and the OPFS SAH handles aren't released until the
  // browser actually tears the worker down. The next test's worker would otherwise time out
  // its installOpfsSAHPoolVfs retry budget waiting for handles to free.
  @AfterTest
  fun terminateWorker() = runTest {
    workersInThisTest.forEach { it.terminate() }
    workersInThisTest.clear()
    delay(500)
  }
}

private val workersInThisTest = mutableSetOf<Worker>()

actual fun testSqliteDriver(): SQLiteDriver {
  val worker = opfsWorker(OpfsMultiTabMode.Single)
  workersInThisTest.add(worker)
  return WebWorkerSQLiteDriver(worker)
}
