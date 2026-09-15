package com.eygraber.sqldelight.androidx.driver

import app.cash.sqldelight.SuspendingTransacter

expect class CommonDriverOpenFlagsTest() : AndroidxSqliteDriverOpenFlagsTest
expect class CommonTransacterThreadingTest() : AndroidxSqliteTransacterThreadingTest

actual fun closeAndroidxSqliteTestDriver() {}

expect suspend inline fun <T> assertChecksThreadConfinement(
  transacter: SuspendingTransacter,
  crossinline scope: suspend SuspendingTransacter.(T.() -> Unit) -> Unit,
  crossinline block: T.() -> Unit,
)
