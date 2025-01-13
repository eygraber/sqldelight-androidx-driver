package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteDriver
import app.cash.sqldelight.Transacter

expect class CommonDriverTest() : AndroidxSqliteDriverTest
expect class CommonEphemeralTest() : AndroidxSqliteEphemeralTest
expect class CommonQueryTest() : AndroidxSqliteQueryTest
expect class CommonTransacterTest() : AndroidxSqliteTransacterTest

expect fun androidxSqliteTestDriver(): SQLiteDriver

expect inline fun <T> assertChecksThreadConfinement(
  transacter: Transacter,
  crossinline scope: Transacter.(T.() -> Unit) -> Unit,
  crossinline block: T.() -> Unit,
)
