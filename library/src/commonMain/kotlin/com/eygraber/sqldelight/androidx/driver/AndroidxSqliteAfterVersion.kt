package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.db.SqlDriver

/**
 * A migration callback that runs after [afterVersion] has been applied.
 *
 * Unlike SQLDelight's `AfterVersion`, [block] is `suspend`, so DB operations on the provided
 * [SqlDriver] can be awaited directly. The driver bridges the migration's coroutine context into
 * [block] so writes reuse the migration's writer connection and participate in its transaction.
 */
public class AndroidxSqliteAfterVersion(
  public val afterVersion: Long,
  public val block: suspend (SqlDriver) -> Unit,
)
