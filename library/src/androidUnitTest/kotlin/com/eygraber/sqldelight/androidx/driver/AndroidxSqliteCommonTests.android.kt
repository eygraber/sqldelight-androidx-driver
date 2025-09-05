package com.eygraber.sqldelight.androidx.driver

import android.app.Application
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.Transacter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.Semaphore

@RunWith(RobolectricTestRunner::class)
actual class CommonCallbackTest : AndroidxSqliteCallbackTest()

@RunWith(RobolectricTestRunner::class)
actual class CommonConcurrencyTest : AndroidxSqliteConcurrencyTest()

@RunWith(RobolectricTestRunner::class)
class AndroidGetDatabasePathConcurrencyTest : AndroidxSqliteConcurrencyTest() {
  override fun createDatabaseType(fullDbName: String): AndroidxSqliteDatabaseType.FileProvider {
    val context = ApplicationProvider.getApplicationContext<Application>()

    // https://github.com/robolectric/robolectric/issues/10589
    context.getDatabasePath(fullDbName).parentFile?.mkdirs()

    return AndroidxSqliteDatabaseType.FileProvider(
      context = context,
      name = fullDbName,
    )
  }
}

@RunWith(RobolectricTestRunner::class)
actual class CommonDriverTest : AndroidxSqliteDriverTest()

@RunWith(RobolectricTestRunner::class)
actual class CommonDriverOpenFlagsTest : AndroidxSqliteDriverOpenFlagsTest()

@RunWith(RobolectricTestRunner::class)
actual class CommonEphemeralTest : AndroidxSqliteEphemeralTest()

@RunWith(RobolectricTestRunner::class)
actual class CommonMigrationKeyTest : AndroidxSqliteMigrationKeyTest()

@RunWith(RobolectricTestRunner::class)
actual class CommonQueryTest : AndroidxSqliteQueryTest()

@RunWith(RobolectricTestRunner::class)
actual class CommonTransacterTest : AndroidxSqliteTransacterTest()

actual fun androidxSqliteTestDriver(): SQLiteDriver = AndroidSQLiteDriver()

actual fun androidxSqliteTestCreateConnection(): (String) -> SQLiteConnection = { name ->
  AndroidSQLiteDriver().open(name)
}

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
