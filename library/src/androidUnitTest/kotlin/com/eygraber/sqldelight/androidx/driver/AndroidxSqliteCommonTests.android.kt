package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.cash.sqldelight.Transacter
import org.junit.Assert
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Semaphore

@RunWith(RobolectricTestRunner::class)
actual class CommonDriverTest : AndroidxSqliteDriverTest()

@RunWith(RobolectricTestRunner::class)
actual class CommonEphemeralTest : AndroidxSqliteEphemeralTest()

@RunWith(RobolectricTestRunner::class)
actual class CommonQueryTest : AndroidxSqliteQueryTest()

@RunWith(RobolectricTestRunner::class)
actual class CommonTransacterTest : AndroidxSqliteTransacterTest()

actual fun androidxSqliteTestDriver(): SQLiteDriver = AndroidSQLiteDriver()

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
