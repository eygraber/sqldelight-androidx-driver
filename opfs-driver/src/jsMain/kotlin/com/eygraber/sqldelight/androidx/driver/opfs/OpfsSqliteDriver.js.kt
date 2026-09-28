package com.eygraber.sqldelight.androidx.driver.opfs

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver

public actual class OpfsSqliteDriver internal actual constructor(
  public actual val opfsWorker: OpfsWorker,
) : SQLiteDriver, AutoCloseable {
  private val delegate = WebWorkerSQLiteDriver(opfsWorker.worker)

  actual override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool

  actual override suspend fun open(fileName: String): SQLiteConnection {
    check(!opfsWorker.isClosed) { "OpfsSqliteDriver is closed" }
    return delegate.open(fileName)
  }

  actual override fun close() {
    opfsWorker.close()
  }
}
