package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import app.cash.sqldelight.Transacter

expect class CommonCallbackTest() : AndroidxSqliteCallbackTest
expect class CommonDriverTest() : AndroidxSqliteDriverTest
expect class CommonDriverOpenFlagsTest() : AndroidxSqliteDriverOpenFlagsTest
expect class CommonQueryTest() : AndroidxSqliteQueryTest
expect class CommonTransacterTest() : AndroidxSqliteTransacterTest

expect class CommonEphemeralTest() : AndroidxSqliteEphemeralTest

expect fun androidxSqliteTestDriver(): SQLiteDriver
expect fun androidxSqliteTestCreateConnection(): (String) -> SQLiteConnection

expect inline fun <T> assertChecksThreadConfinement(
  transacter: Transacter,
  crossinline scope: Transacter.(T.() -> Unit) -> Unit,
  crossinline block: T.() -> Unit,
)
