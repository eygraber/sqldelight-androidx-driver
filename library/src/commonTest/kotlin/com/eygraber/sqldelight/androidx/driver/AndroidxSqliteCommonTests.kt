package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher

expect class CommonCallbackTest() : AndroidxSqliteCallbackTest
expect class CommonConcurrencyTest() : AndroidxSqliteConcurrencyTest
expect class CommonCreationTest() : AndroidxSqliteCreationTest
expect class CommonDriverTest() : AndroidxSqliteDriverTest
expect class CommonEphemeralTest() : AndroidxSqliteEphemeralTest
expect class CommonMigrationTest() : AndroidxSqliteMigrationTest
expect class CommonQueryTest() : AndroidxSqliteQueryTest
expect class CommonTransacterTest() : AndroidxSqliteTransacterTest

expect fun androidxSqliteTestDriver(): SQLiteDriver
fun androidxSqliteTestConnectionFactory(): AndroidxSqliteConnectionFactory =
  DefaultAndroidxSqliteConnectionFactory(androidxSqliteTestDriver())

expect fun closeAndroidxSqliteTestDriver()

expect val IoDispatcher: CoroutineDispatcher

expect suspend fun deleteFile(name: String)
