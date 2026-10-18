package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection

internal actual fun createDefaultConnectionPool(
  connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  isFileBased: Boolean,
  configuration: AndroidxSqliteConfiguration,
  onConnectionClosed: (SQLiteConnection) -> Unit,
  onClosed: () -> Unit,
): ConnectionPool = AndroidxDriverConnectionPool(
  connectionFactory = connectionFactory,
  nameProvider = nameProvider,
  isFileBased = isFileBased,
  configuration = configuration,
  onConnectionClosed = onConnectionClosed,
  onClosed = onClosed,
)

internal actual fun createPassthroughConnectionPool(
  connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  configuration: AndroidxSqliteConfiguration,
  onConnectionClosed: (SQLiteConnection) -> Unit,
  onClosed: () -> Unit,
): ConnectionPool = PassthroughConnectionPool(
  connectionFactory = connectionFactory,
  nameProvider = nameProvider,
  configuration = configuration,
  onConnectionClosed = onConnectionClosed,
  onClosed = onClosed,
)
