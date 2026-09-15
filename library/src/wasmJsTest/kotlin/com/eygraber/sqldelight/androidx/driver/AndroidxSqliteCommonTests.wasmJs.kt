@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.await
import kotlin.js.Promise

internal actual class WebTestSqliteDriver : SQLiteDriver {
  override val hasConnectionPool: Boolean get() = false

  override suspend fun open(fileName: String): SQLiteConnection = openWebTestConnection(fileName)
}

internal actual suspend fun removeOpfsEntry(name: String) {
  removeOpfsEntryPromise(name).await<JsAny?>()
}

internal actual suspend fun removeOpfsDirectory(name: String): String =
  removeOpfsDirectoryPromise(name).await<JsString>().toString()

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
