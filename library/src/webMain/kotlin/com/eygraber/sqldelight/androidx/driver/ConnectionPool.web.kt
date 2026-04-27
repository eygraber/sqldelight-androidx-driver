package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection

internal actual fun createDefaultConnectionPool(
  connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  isFileBased: Boolean,
  configuration: AndroidxSqliteConfiguration,
  onConnectionClosed: (SQLiteConnection) -> Unit,
): ConnectionPool = WebConnectionPool(
  connectionFactory = connectionFactory,
  nameProvider = nameProvider,
  configuration = configuration,
)

internal actual fun createPassthroughConnectionPool(
  connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  configuration: AndroidxSqliteConfiguration,
): ConnectionPool = WebConnectionPool(
  connectionFactory = connectionFactory,
  nameProvider = nameProvider,
  configuration = configuration,
)
