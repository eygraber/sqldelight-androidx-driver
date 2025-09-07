package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import app.cash.sqldelight.Transacter
import kotlinx.coroutines.CoroutineDispatcher

expect class CommonCallbackTest() : AndroidxSqliteCallbackTest
expect class CommonConcurrencyTest() : AndroidxSqliteConcurrencyTest
expect class CommonCreationTest() : AndroidxSqliteCreationTest
expect class CommonDriverTest() : AndroidxSqliteDriverTest
expect class CommonDriverOpenFlagsTest() : AndroidxSqliteDriverOpenFlagsTest
expect class CommonEphemeralTest() : AndroidxSqliteEphemeralTest
expect class CommonMigrationTest() : AndroidxSqliteMigrationTest
expect class CommonQueryTest() : AndroidxSqliteQueryTest
expect class CommonTransacterTest() : AndroidxSqliteTransacterTest

expect fun androidxSqliteTestDriver(): SQLiteDriver
expect fun androidxSqliteTestCreateConnection(): (String) -> SQLiteConnection

expect val IoDispatcher: CoroutineDispatcher

expect fun deleteFile(name: String)

expect inline fun <T> assertChecksThreadConfinement(
  transacter: Transacter,
  crossinline scope: Transacter.(T.() -> Unit) -> Unit,
  crossinline block: T.() -> Unit,
)
