package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlPreparedStatement

public class ConfigurableDatabase(
  private val driver: AndroidxSqliteDriver,
) {
  public fun setForeignKeyConstraintsEnabled(isForeignKeyConstraintsEnabled: Boolean) {
    driver.setForeignKeyConstraintsEnabled(isForeignKeyConstraintsEnabled)
  }

  public fun setJournalMode(journalMode: SqliteJournalMode) {
    driver.setJournalMode(journalMode)
  }

  public fun setSync(sync: SqliteSync) {
    driver.setSync(sync)
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
