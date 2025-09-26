package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.Transacter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert
import java.io.File
import java.util.concurrent.Semaphore

actual class CommonCallbackTest : AndroidxSqliteCallbackTest()
actual class CommonConcurrencyTest : AndroidxSqliteConcurrencyTest()
actual class CommonCreationTest : AndroidxSqliteCreationTest()
actual class CommonDriverTest : AndroidxSqliteDriverTest()
actual class CommonDriverOpenFlagsTest : AndroidxSqliteDriverOpenFlagsTest()
actual class CommonEphemeralTest : AndroidxSqliteEphemeralTest()
actual class CommonMigrationTest : AndroidxSqliteMigrationTest()
actual class CommonQueryTest : AndroidxSqliteQueryTest()
actual class CommonTransacterTest : AndroidxSqliteTransacterTest()

actual fun androidxSqliteTestDriver(): SQLiteDriver = BundledSQLiteDriver()

@Suppress("InjectDispatcher")
actual val IoDispatcher: CoroutineDispatcher get() = Dispatchers.IO

actual fun deleteFile(name: String) {
  File(name).delete()
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
