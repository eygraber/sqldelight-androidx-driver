package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteStatement
import app.cash.sqldelight.db.QueryResult

/**
 * Advances [statement] one row and returns the result as a [QueryResult].
 *
 * On non-web targets, [SQLiteStatement.step] is non-suspending so this returns a
 * [QueryResult.Value] — letting hand-written mappers read `.value` synchronously.
 *
 * On JS/wasmJs, `step` is suspending, so this returns a [QueryResult.AsyncValue] and the
 * mapper must `await()` it.
 */
internal expect fun stepCursor(statement: SQLiteStatement): QueryResult<Boolean>
