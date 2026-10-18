package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.SuspendingTransacter

expect class CommonDeleteDatabaseTest() : AndroidxSqliteDeleteDatabaseTest
expect class CommonDriverOpenFlagsTest() : AndroidxSqliteDriverOpenFlagsTest
expect class CommonTransacterThreadingTest() : AndroidxSqliteTransacterThreadingTest

actual fun closeAndroidxSqliteTestDriver() {}

expect fun fileExists(path: String): Boolean

expect suspend inline fun <T> assertChecksThreadConfinement(
  transacter: SuspendingTransacter,
  crossinline scope: suspend SuspendingTransacter.(T.() -> Unit) -> Unit,
  crossinline block: T.() -> Unit,
)
