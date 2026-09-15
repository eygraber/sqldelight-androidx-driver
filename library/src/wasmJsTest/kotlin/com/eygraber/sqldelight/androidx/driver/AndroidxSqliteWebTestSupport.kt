@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsSqliteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.androidxSqliteOpfsDriver
import kotlinx.coroutines.await
import kotlin.js.Promise

private val activeTestDrivers = mutableSetOf<OpfsSqliteDriver>()

internal fun webTestSqliteDriver(): SQLiteDriver {
  closeTestDrivers()
  return androidxSqliteOpfsDriver(OpfsMultiTabMode.Single).also { activeTestDrivers += it }
}

internal fun closeTestDrivers() {
  activeTestDrivers.forEach { it.close() }
  activeTestDrivers.clear()
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
