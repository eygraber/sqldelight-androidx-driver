package com.eygraber.sqldelight.androidx.driver.opfs

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver

/**
 * A `SQLiteDriver` backed by an [OpfsWorker]. [close] closes the worker; see [OpfsWorker.close].
 * `AndroidxSqliteDriver.close()` calls [close] after it closes its connection.
 * `open` fails with an [IllegalStateException] after [close].
 */
public expect class OpfsSqliteDriver internal constructor(
  opfsWorker: OpfsWorker,
) : SQLiteDriver, AutoCloseable {
  public val opfsWorker: OpfsWorker

  override val hasConnectionPool: Boolean

  override suspend fun open(fileName: String): SQLiteConnection

  override fun close()
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
