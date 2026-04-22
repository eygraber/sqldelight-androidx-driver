package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

public class AndroidxSqliteConfigurableDriver(
  private val driver: SqlDriver,
) {
  public suspend fun setForeignKeyConstraintsEnabled(isForeignKeyConstraintsEnabled: Boolean) {
    val foreignKey = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
    executePragma("foreign_keys = $foreignKey")
  }

  public suspend fun setJournalMode(journalMode: SqliteJournalMode) {
    executePragma("journal_mode = ${journalMode.value}")
  }

  public suspend fun setSync(sync: SqliteSync) {
    executePragma("synchronous = ${sync.value}")
  }

  public suspend fun executePragma(
    pragma: String,
    parameters: Int = 0,
    binders: (SqlPreparedStatement.() -> Unit)? = null,
  ) {
    driver.execute(
      identifier = null,
      sql = "PRAGMA $pragma;",
      parameters = parameters,
      binders = binders,
    ).await()
  }

  public suspend fun <R> executePragmaQuery(
    pragma: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int = 0,
    binders: (SqlPreparedStatement.() -> Unit)? = null,
  ): R = driver.executeQuery(
    identifier = null,
    sql = "PRAGMA $pragma;",
    mapper = mapper,
    parameters = parameters,
    binders = binders,
  ).await()
}
