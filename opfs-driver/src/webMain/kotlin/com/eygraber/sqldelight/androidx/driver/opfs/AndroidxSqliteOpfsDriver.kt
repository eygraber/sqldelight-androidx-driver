package com.eygraber.sqldelight.androidx.driver.opfs

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver

/**
 * A `SQLiteDriver` backed by an [OpfsWorker]. [close] closes the worker; see [OpfsWorker.close].
 * `AndroidxSqliteDriver.close()` calls [close] after it closes its connection.
 * [open] fails with an [IllegalStateException] after [close].
 */
public class OpfsSqliteDriver internal constructor(
  public val opfsWorker: OpfsWorker,
) : SQLiteDriver, AutoCloseable {
  private val delegate = WebWorkerSQLiteDriver(opfsWorker.worker)

  override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool

  override suspend fun open(fileName: String): SQLiteConnection {
    check(!opfsWorker.isClosed) { "OpfsSqliteDriver is closed" }
    return delegate.open(fileName)
  }

  override fun close() {
    opfsWorker.close()
  }
}

/**
 * Returns an [OpfsSqliteDriver] over an [opfsWorker] created with the given arguments. Pass it
 * to `AndroidxSqliteDriver`, whose `close()` also closes this driver and its worker.
 */
public fun androidxSqliteOpfsDriver(
  multiTabMode: OpfsMultiTabMode = OpfsMultiTabMode.Default,
  onLockStateChange: ((OpfsLockState) -> Unit)? = null,
): OpfsSqliteDriver = OpfsSqliteDriver(
  opfsWorker = opfsWorker(
    multiTabMode = multiTabMode,
    onLockStateChange = onLockStateChange,
  ),
)
