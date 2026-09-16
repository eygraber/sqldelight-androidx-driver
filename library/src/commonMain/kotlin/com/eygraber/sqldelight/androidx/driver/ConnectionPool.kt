package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal interface ConnectionPool {
  suspend fun <R> runOnDispatcher(block: suspend () -> R): R

  suspend fun acquireWriterConnection(): SQLiteConnection
  suspend fun releaseWriterConnection()
  suspend fun acquireReaderConnection(): SQLiteConnection
  suspend fun releaseReaderConnection(connection: SQLiteConnection)
  suspend fun <R> setJournalMode(
    executeStatement: suspend (SQLiteConnection) -> R,
  ): R
  fun close()
}

internal suspend inline fun <R> ConnectionPool.withWriterConnection(
  block: suspend SQLiteConnection.() -> R,
): R {
  val connection = acquireWriterConnection()
  try {
    return connection.block()
  }
  finally {
    withContext(NonCancellable) {
      releaseWriterConnection()
    }
  }
}

internal suspend fun SQLiteConnection.withWriterConfiguration(
  configuration: AndroidxSqliteConfiguration,
): SQLiteConnection {
  try {
    configuration.apply {
      executeSQL("PRAGMA journal_mode = ${journalMode.value};")
      executeSQL("PRAGMA synchronous = ${sync.value};")

      // this must come after PRAGMA journal_mode while https://issuetracker.google.com/issues/447613208 is broken
      val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
      executeSQL("PRAGMA foreign_keys = $foreignKeys;")
    }
  }
  catch(c: CancellationException) {
    throw c
  }
  catch(t: Throwable) {
    try {
      close()
    }
    catch(closeFailure: Throwable) {
      t.addSuppressed(closeFailure)
    }
    throw t
  }
  return this
}

internal expect fun createDefaultConnectionPool(
  connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  isFileBased: Boolean,
  configuration: AndroidxSqliteConfiguration,
  onConnectionClosed: (SQLiteConnection) -> Unit,
): ConnectionPool

internal expect fun createPassthroughConnectionPool(
  connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  configuration: AndroidxSqliteConfiguration,
): ConnectionPool
