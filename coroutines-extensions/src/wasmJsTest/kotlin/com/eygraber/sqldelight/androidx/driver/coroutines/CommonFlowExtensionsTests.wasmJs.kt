@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver.coroutines

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import org.w3c.dom.Worker

actual class CommonFlowExtensionsTest : FlowExtensionsTest()

actual fun testSqliteDriver(): SQLiteDriver = WebWorkerSQLiteDriver(testOpfsModuleWorker())

// Use the webpack 5 `new Worker(new URL(..., import.meta.url), { type: "module" })` pattern so
// webpack bundles the worker's npm imports. See AndroidxSqliteWebTestSupport.kt in :library.
@JsFun(
  """() => new Worker(
        new URL("./sqldelight-androidx-opfs-worker.js", import.meta.url),
        { type: "module" }
      )""",
)
private external fun testOpfsModuleWorker(): Worker
