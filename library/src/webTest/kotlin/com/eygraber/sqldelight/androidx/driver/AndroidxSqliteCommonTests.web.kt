package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsSqliteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.androidxSqliteOpfsDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

actual class CommonCallbackTest : AndroidxSqliteCallbackTest()
actual class CommonConcurrencyTest : AndroidxSqliteConcurrencyTest()
actual class CommonCreationTest : AndroidxSqliteCreationTest()
actual class CommonDriverTest : AndroidxSqliteDriverTest()
actual class CommonEphemeralTest : AndroidxSqliteEphemeralTest()
actual class CommonMigrationTest : AndroidxSqliteMigrationTest()
actual class CommonQueryTest : AndroidxSqliteQueryTest()
actual class CommonTransacterTest : AndroidxSqliteTransacterTest()

actual fun androidxSqliteTestDriver(): SQLiteDriver = WebTestSqliteDriver()

actual fun closeAndroidxSqliteTestDriver() {
  sharedOpfsDriver?.close()
  sharedOpfsDriver = null
}

@Suppress("InjectDispatcher")
actual val IoDispatcher: CoroutineDispatcher get() = Dispatchers.Default

actual suspend fun deleteFile(name: String) {
  removeOpfsEntry(name)
}

internal expect class WebTestSqliteDriver() : SQLiteDriver

private var sharedOpfsDriver: OpfsSqliteDriver? = null

internal suspend fun openWebTestConnection(fileName: String): SQLiteConnection {
  val driver = sharedOpfsDriver ?: run {
    awaitOpfsRelease()
    androidxSqliteOpfsDriver(OpfsMultiTabMode.Single).also { sharedOpfsDriver = it }
  }
  return driver.open(fileName)
}

private suspend fun awaitOpfsRelease() {
  withContext(Dispatchers.Default) {
    var attempts = 0
    var released = false
    while(!released) {
      val result = removeOpfsDirectory(".opfs-sahpool")
      released = result == "ok" || result == "NotFoundError"
      if(!released) {
        attempts++
        check(attempts < 100) { "the OPFS SAH pool was not released: $result" }
        delay(100)
      }
    }
  }
}

internal expect suspend fun removeOpfsEntry(name: String)

internal expect suspend fun removeOpfsDirectory(name: String): String
