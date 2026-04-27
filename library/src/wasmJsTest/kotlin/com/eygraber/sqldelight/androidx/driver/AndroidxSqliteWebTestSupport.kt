@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import kotlinx.coroutines.await
import org.w3c.dom.Worker
import kotlin.js.Promise

internal fun webTestSqliteDriver(): SQLiteDriver = WebWorkerSQLiteDriver(testOpfsModuleWorker())

// Use the webpack 5 `new Worker(new URL(..., import.meta.url), { type: "module" })` pattern.
// Webpack detects this exact pattern and bundles the worker as a separate chunk, resolving its
// `import` statements (e.g. `@sqlite.org/sqlite-wasm`) at build time. The opfs-driver's runtime
// `opfsWorker()` helper relies on the consumer doing this bundling, but tests don't go through a
// consumer-style webpack build so we have to spawn the worker through this pattern directly.
@JsFun(
  """() => new Worker(
        new URL("./sqldelight-androidx-opfs-worker.js", import.meta.url),
        { type: "module" }
      )""",
)
private external fun testOpfsModuleWorker(): Worker

@JsFun(
  """(name) => navigator.storage.getDirectory()
        .then(d => d.removeEntry(name).catch(() => undefined))
        .catch(() => undefined)""",
)
private external fun removeOpfsEntryPromise(name: String): Promise<JsAny?>

internal suspend fun deleteOpfsFile(name: String) {
  removeOpfsEntryPromise(name).await<JsAny?>()
}
