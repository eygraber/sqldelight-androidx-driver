package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlPreparedStatement

public fun AndroidxSqliteDriver.enableForeignKeys() {
  execute(null, "PRAGMA foreign_keys = ON;", 0, null)
}

public fun AndroidxSqliteDriver.disableForeignKeys() {
  execute(null, "PRAGMA foreign_keys = OFF;", 0, null)
}

public fun AndroidxSqliteDriver.enableWAL() {
  execute(null, "PRAGMA journal_mode = WAL;", 0, null)
}

public fun AndroidxSqliteDriver.disableWAL() {
  execute(null, "PRAGMA journal_mode = DELETE;", 0, null)
}

public class ConfigurableDatabase(
  private val driver: AndroidxSqliteDriver,
) {
  public fun enableForeignKeys() {
    driver.enableForeignKeys()
  }

  public fun disableForeignKeys() {
    driver.disableForeignKeys()
  }

  public fun enableWAL() {
    driver.enableWAL()
  }

  public fun disableWAL() {
    driver.disableWAL()
  }

  public fun executePragma(
    pragma: String,
    parameters: Int = 0,
    binders: (SqlPreparedStatement.() -> Unit)? = null,
  ) {
    driver.execute(null, "PRAGMA $pragma;", parameters, binders)
  }

  public fun <T> executePragmaQuery(
    pragma: String,
    mapper: (SqlCursor) -> QueryResult<T>,
    parameters: Int = 0,
    binders: (SqlPreparedStatement.() -> Unit)? = null,
  ): QueryResult.Value<T> = driver.executeQuery(null, "PRAGMA $pragma;", mapper, parameters, binders)
}
