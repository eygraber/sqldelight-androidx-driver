package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReaders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SuspendingConnectionFactoryWalTest {
  @Test
  fun `readers of a WAL file database open when the factory suspends on the pool dispatcher`() =
    runTest(timeout = 15.seconds) {
      val dispatcher = IoDispatcher.limitedParallelism(1)
      val factory = DispatchingConnectionFactory(dispatcher)
      val dbFile = File.createTempFile("SuspendingConnectionFactoryWalTest", ".db")
      val pool = AndroidxDriverConnectionPool(
        connectionFactory = factory,
        nameProvider = { dbFile.absolutePath },
        isFileBased = true,
        configuration = AndroidxSqliteConfiguration(
          concurrencyModel = MultipleReaders(
            readerCount = 2,
            dispatcherProvider = { _, _ -> dispatcher },
          ),
        ),
      )

      try {
        pool.runOnDispatcher {
          val writer = pool.acquireWriterConnection()
          writer.execSQL("CREATE TABLE test(id INTEGER PRIMARY KEY)")
          pool.releaseWriterConnection()

          val readers = coroutineScope {
            List(2) { async { pool.acquireReaderConnection() } }.awaitAll()
          }
          assertNotSame(readers[0], readers[1])
          readers.forEach { pool.releaseReaderConnection(it) }
        }

        assertEquals(3, factory.created)
      }
      finally {
        pool.close()
        dbFile.delete()
        File("${dbFile.absolutePath}-shm").delete()
        File("${dbFile.absolutePath}-wal").delete()
      }
    }

  private class DispatchingConnectionFactory(
    private val dispatcher: CoroutineDispatcher,
  ) : AndroidxSqliteConnectionFactory {
    var created = 0

    override val driver: SQLiteDriver = BundledSQLiteDriver()

    override suspend fun createConnection(name: String): SQLiteConnection =
      withContext(dispatcher) {
        created++
        driver.open(name)
      }
  }
}
