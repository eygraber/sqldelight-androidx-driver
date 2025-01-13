package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.Transacter
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlin.test.assertFailsWith

actual class CommonDriverTest : AndroidxSqliteDriverTest()
actual class CommonQueryTest : AndroidxSqliteQueryTest()
actual class CommonTransacterTest : AndroidxSqliteTransacterTest()

actual class CommonEphemeralTest : AndroidxSqliteEphemeralTest() {
  override fun deleteDbFile(filename: String) {
    FileSystem.SYSTEM.delete(filename.toPath())
  }
}

actual fun androidxSqliteTestDriver(): SQLiteDriver = BundledSQLiteDriver()

@OptIn(ObsoleteWorkersApi::class)
actual inline fun <T> assertChecksThreadConfinement(
  transacter: Transacter,
  crossinline scope: Transacter.(T.() -> Unit) -> Unit,
  crossinline block: T.() -> Unit,
) {
  val resultRef = AtomicReference<Result<Unit>?>(null)
  val semaphore = AtomicInt(0)

  transacter.scope {
    val worker = Worker.start()
    worker.executeAfter(0L) {
      resultRef.value = runCatching {
        this@scope.block()
      }
      semaphore.value = 1
    }
    worker.requestTermination()
  }

  while(semaphore.value == 0) {
    Worker.current.processQueue()
  }

  assertFailsWith<IllegalStateException> {
    resultRef.value!!.getOrThrow()
  }
}
