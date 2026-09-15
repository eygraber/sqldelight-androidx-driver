@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver.integration

import androidx.sqlite.SQLiteDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsLockState
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsSqliteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.androidxSqliteOpfsDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.js.Promise

// The wasmJs test runner does not await a suspend after-hook, so the wait for released OPFS handles runs at the start of the next test.
private val knownTestDrivers = mutableSetOf<OpfsSqliteDriver>()

internal fun newTestDriver(
  mode: OpfsMultiTabMode = OpfsMultiTabMode.Single,
  onLockStateChange: ((OpfsLockState) -> Unit)? = null,
): OpfsSqliteDriver {
  val driver = androidxSqliteOpfsDriver(mode, onLockStateChange)
  knownTestDrivers.add(driver)
  return driver
}

internal fun closeTestDrivers() {
  knownTestDrivers.forEach { it.close() }
  knownTestDrivers.clear()
}

internal fun newTestSqlDriver(driver: OpfsSqliteDriver, dbName: String): AndroidxSqliteDriver =
  AndroidxSqliteDriver(
    driver = driver,
    databaseType = AndroidxSqliteDatabaseType.File(dbName),
    schema = AndroidXDb.Schema,
  )

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

internal const val REAL_TIME_LIMIT_MS = 20_000L

internal suspend fun <T> inRealTime(block: suspend () -> T): T = withContext(Dispatchers.Default) {
  withTimeout(REAL_TIME_LIMIT_MS) { block() }
}

internal suspend fun awaitLockState(states: List<OpfsLockState>, expected: OpfsLockState) {
  inRealTime {
    while(states.lastOrNull() != expected) delay(50)
  }
}

actual fun testSqliteDriver(): SQLiteDriver = newTestDriver()

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
