package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver

public interface AndroidxSqliteConnectionFactory {
  public val driver: SQLiteDriver

  public fun createConnection(name: String): SQLiteConnection
}

public class DefaultAndroidxSqliteConnectionFactory(
  override val driver: SQLiteDriver,
) : AndroidxSqliteConnectionFactory {
  override fun createConnection(name: String): SQLiteConnection =
    driver.open(name)
}
