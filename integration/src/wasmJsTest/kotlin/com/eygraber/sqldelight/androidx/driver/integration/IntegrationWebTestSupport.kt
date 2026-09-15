@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver.integration

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import com.eygraber.sqldelight.androidx.driver.opfs.opfsWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.w3c.dom.Worker
import kotlin.js.Promise

// The wasmJs test runner does not await a suspend after-hook, so the wait for released OPFS handles runs at the start of the next test.
private val knownTestWorkers = mutableSetOf<Worker>()

internal fun newTestWorker(mode: OpfsMultiTabMode = OpfsMultiTabMode.Single): Worker {
  val w = opfsWorker(mode)
  knownTestWorkers.add(w)
  return w
}

internal fun terminateTestWorkers() {
  knownTestWorkers.forEach { it.terminate() }
  knownTestWorkers.clear()
}

internal suspend fun awaitOpfsRelease() {
  withContext(Dispatchers.Default) {
    var attempts = 0
    var released = false
    while(!released) {
      val result = removeOpfsDirectoryPromise(".opfs-sahpool").await<JsString>().toString()
      released = result == "ok" || result == "NotFoundError"
      if(!released) {
        attempts++
        check(attempts < 100) { "the OPFS SAH pool was not released: $result" }
        delay(100)
      }
    }
  }
}

actual fun testSqliteDriver(): SQLiteDriver = WebWorkerSQLiteDriver(newTestWorker())

actual suspend fun deleteFile(name: String) {
  removeOpfsEntryPromise(name).await<JsAny?>()
}

@JsFun(
  """(name) => navigator.storage.getDirectory()
        .then(d => d.removeEntry(name).catch(() => undefined))
        .catch(() => undefined)""",
)
private external fun removeOpfsEntryPromise(name: String): Promise<JsAny?>

@JsFun(
  """(name) => navigator.storage.getDirectory()
        .then(d => d.removeEntry(name, { recursive: true }).then(() => 'ok', (e) => String(e.name)))
        .catch((e) => String(e && e.name))""",
)
private external fun removeOpfsDirectoryPromise(name: String): Promise<JsString>
