package com.eygraber.sqldelight.androidx.driver.opfs

import org.w3c.dom.Worker

/**
 * Returns a [Worker] that bridges `androidx.sqlite`'s `WebWorkerSQLiteDriver` protocol to
 * `@sqlite.org/sqlite-wasm`'s OPFS VFS. Database files referenced by
 * `AndroidxSqliteDatabaseType.File("name.db")` are persisted in the browser's Origin Private
 * File System.
 *
 * Pass it to [androidx.sqlite.driver.web.WebWorkerSQLiteDriver] and hand the resulting driver
 * to `AndroidxSqliteDriver`:
 *
 * ```kotlin
 * val driver = AndroidxSqliteDriver(
 *   driver = WebWorkerSQLiteDriver(opfsWorker()),
 *   databaseType = AndroidxSqliteDatabaseType.File("music.db"),
 *   schema = MusicDatabase.Schema,
 * )
 * ```
 *
 * The bundled worker JS is a derivative of the AndroidX example worker (Apache 2.0); it's
 * shipped as a JS resource of this module so the consumer's webpack build copies it into the
 * output directory next to the calling JS bundle.
 */
public fun opfsWorker(): Worker = Worker(opfsWorkerUrl())

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun opfsWorkerUrl(): String =
  js("""new URL("./sqldelight-androidx-opfs-worker.js", import.meta.url).toString()""")
