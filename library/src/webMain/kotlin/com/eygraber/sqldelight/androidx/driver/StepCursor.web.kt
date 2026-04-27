package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteStatement
import app.cash.sqldelight.db.QueryResult

internal actual fun stepCursor(statement: SQLiteStatement): QueryResult<Boolean> =
  QueryResult.AsyncValue { statement.step() }
