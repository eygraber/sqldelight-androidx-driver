package com.eygraber.sqldelight.androidx.driver.integration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsLockState
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsSqliteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.androidxSqliteOpfsDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.w3c.dom.Worker

// The web test runner does not await a suspend after-hook, so the wait for released OPFS handles runs at the start of the next test.
private val knownTestDrivers = mutableSetOf<OpfsSqliteDriver>()

private var sharedOpfsDriver: OpfsSqliteDriver? = null

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
  sharedOpfsDriver = null
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

internal const val REAL_TIME_LIMIT_MS = 20_000L

internal suspend fun <T> inRealTime(block: suspend () -> T): T = withContext(Dispatchers.Default) {
  withTimeout(REAL_TIME_LIMIT_MS) { block() }
}

internal suspend fun awaitLockState(states: List<OpfsLockState>, expected: OpfsLockState) {
  inRealTime {
    while(states.lastOrNull() != expected) delay(50)
  }
}

internal suspend fun openWebTestConnection(fileName: String): SQLiteConnection {
  val driver = sharedOpfsDriver ?: run {
    awaitOpfsRelease()
    newTestDriver().also { sharedOpfsDriver = it }
  }
  return driver.open(fileName)
}

actual fun testSqliteDriver(): SQLiteDriver = WebTestSqliteDriver()

actual fun closeTestSqliteDriver() {
  closeTestDrivers()
}

actual fun deleteFile(name: String) {
  removeOpfsEntry(name)
}

internal expect class WebTestSqliteDriver() : SQLiteDriver

internal expect fun removeOpfsEntry(name: String)

internal expect suspend fun removeOpfsDirectory(name: String): String

internal expect fun postOpfsPause(worker: Worker)

internal expect fun postOpfsResume(worker: Worker)

internal expect fun postFollowerCountRequest(worker: Worker)

internal expect class TestControlPort {
  suspend fun next(): String
}

internal expect fun attachTestControlPort(worker: Worker): TestControlPort

internal expect class TestFollowerCountPort {
  suspend fun next(): Int
}

internal expect fun attachFollowerCountPort(worker: Worker): TestFollowerCountPort
