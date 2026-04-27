package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteDriver
import app.cash.sqldelight.SuspendingTransacter
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
fun androidxSqliteTestConnectionFactory(): AndroidxSqliteConnectionFactory =
  DefaultAndroidxSqliteConnectionFactory(androidxSqliteTestDriver())

expect val IoDispatcher: CoroutineDispatcher

expect fun deleteFile(name: String)

expect suspend inline fun <T> assertChecksThreadConfinement(
  transacter: SuspendingTransacter,
  crossinline scope: suspend SuspendingTransacter.(T.() -> Unit) -> Unit,
  crossinline block: T.() -> Unit,
)
