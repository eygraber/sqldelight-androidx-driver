package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.async.open

public interface AndroidxSqliteConnectionFactory {
  public val driver: SQLiteDriver

  public suspend fun createConnection(name: String): SQLiteConnection
}

public class DefaultAndroidxSqliteConnectionFactory(
  override val driver: SQLiteDriver,
) : AndroidxSqliteConnectionFactory {
  override suspend fun createConnection(name: String): SQLiteConnection =
    driver.open(name)
}
