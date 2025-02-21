package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import app.cash.sqldelight.Transacter
import org.junit.Assert
import java.io.File
import java.util.concurrent.Semaphore

actual class CommonCallbackTest : AndroidxSqliteCallbackTest() {
  override fun deleteDbFile(filename: String) {
    File(filename).delete()
  }
}

actual class CommonDriverTest : AndroidxSqliteDriverTest()
actual class CommonDriverOpenFlagsTest : AndroidxSqliteDriverOpenFlagsTest()
actual class CommonQueryTest : AndroidxSqliteQueryTest()
actual class CommonTransacterTest : AndroidxSqliteTransacterTest()

actual class CommonEphemeralTest : AndroidxSqliteEphemeralTest() {
  override fun deleteDbFile(filename: String) {
    File(filename).delete()
  }
}

actual fun androidxSqliteTestDriver(): SQLiteDriver = BundledSQLiteDriver()

actual fun androidxSqliteTestCreateConnection(): (String) -> SQLiteConnection = { name ->
  BundledSQLiteDriver().open(name, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)
}

actual inline fun <T> assertChecksThreadConfinement(
  transacter: Transacter,
  crossinline scope: Transacter.(T.() -> Unit) -> Unit,
  crossinline block: T.() -> Unit,
) {
  lateinit var thread: Thread
  var result: Result<Unit>? = null
  val semaphore = Semaphore(0)

  transacter.scope {
    thread = kotlin.concurrent.thread {
      result = runCatching {
        this@scope.block()
      }

      semaphore.release()
    }
  }

  semaphore.acquire()
  thread.interrupt()
  Assert.assertThrows(IllegalStateException::class.java) {
    result!!.getOrThrow()
  }
}
