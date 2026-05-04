@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver.integration

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import com.eygraber.sqldelight.androidx.driver.opfs.opfsWorker
import kotlinx.coroutines.await
import org.w3c.dom.Worker
import kotlin.js.Promise

// `WebWorkerSQLiteDriver.close()` doesn't terminate the underlying Worker, so the OPFS SAH handles
// it acquired stay locked until the page unloads. Tests have to terminate their workers explicitly
// in cleanup and then yield long enough for the browser to actually release the sync access
// handles before the next test installs a fresh pool — `Worker.terminate()` is asynchronous.
private val knownTestWorkers = mutableSetOf<Worker>()

internal fun freshTestWorker(mode: OpfsMultiTabMode = OpfsMultiTabMode.Single): Worker {
  knownTestWorkers.forEach { it.terminate() }
  knownTestWorkers.clear()
  val w = opfsWorker(mode)
  knownTestWorkers.add(w)
  return w
}

internal fun additionalTestWorker(mode: OpfsMultiTabMode): Worker {
  val w = opfsWorker(mode)
  knownTestWorkers.add(w)
  return w
}

// Should be called from each test's @AfterTest after closing the driver — ensures the workers are
// dead and OPFS sync access handles have been released before the next test starts.
internal suspend fun terminateAndSettleTestWorkers() {
  knownTestWorkers.forEach { it.terminate() }
  knownTestWorkers.clear()
  kotlinx.coroutines.delay(500)
}

actual fun testSqliteDriver(): SQLiteDriver = WebWorkerSQLiteDriver(freshTestWorker())

actual suspend fun deleteFile(name: String) {
  removeOpfsEntryPromise(name).await<JsAny?>()
}

@JsFun(
  """(name) => navigator.storage.getDirectory()
        .then(d => d.removeEntry(name).catch(() => undefined))
        .catch(() => undefined)""",
)
private external fun removeOpfsEntryPromise(name: String): Promise<JsAny?>
