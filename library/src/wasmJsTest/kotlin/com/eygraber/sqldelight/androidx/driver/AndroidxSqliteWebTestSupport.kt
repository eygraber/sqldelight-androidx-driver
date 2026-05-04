@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import com.eygraber.sqldelight.androidx.driver.opfs.opfsWorker
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import org.w3c.dom.Worker
import kotlin.js.Promise

private val activeTestWorkers = mutableSetOf<Worker>()

// `OpfsMultiTabMode.Single` only lets one worker hold the OPFS SAH handles at a time, and
// `WebWorkerSQLiteDriver.close()` doesn't terminate the underlying Worker. So before spawning a
// new worker we tear down any prior one and yield 500 ms — `Worker.terminate()` is asynchronous,
// and without the wait the new worker's `installOpfsSAHPoolVfs` call burns through its retry
// budget waiting for the browser to actually release the handles.
internal suspend fun webTestSqliteDriver(): SQLiteDriver {
  if(activeTestWorkers.isNotEmpty()) {
    activeTestWorkers.forEach { it.terminate() }
    activeTestWorkers.clear()
    delay(500)
  }
  val worker = opfsWorker(OpfsMultiTabMode.Single)
  activeTestWorkers += worker
  return WebWorkerSQLiteDriver(worker)
}

internal suspend fun terminateTestWorkers() {
  if(activeTestWorkers.isEmpty()) return
  activeTestWorkers.forEach { it.terminate() }
  activeTestWorkers.clear()
  delay(500)
}

@JsFun(
  """(name) => navigator.storage.getDirectory()
        .then(d => d.removeEntry(name).catch(() => undefined))
        .catch(() => undefined)""",
)
private external fun removeOpfsEntryPromise(name: String): Promise<JsAny?>

internal suspend fun deleteOpfsFile(name: String) {
  removeOpfsEntryPromise(name).await<JsAny?>()
}
