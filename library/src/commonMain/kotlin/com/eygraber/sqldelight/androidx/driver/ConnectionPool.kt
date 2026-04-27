package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
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
