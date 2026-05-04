package com.eygraber.sqldelight.androidx.driver.integration

import androidx.sqlite.SQLiteDriver

// Stubs to satisfy the expect/actual contract on the JS target. Tests run only on wasmJs, so
// these helpers are never invoked at runtime.

actual fun testSqliteDriver(): SQLiteDriver = error("JS tests are not supported; use wasmJs")

actual suspend fun deleteFile(name: String) {
  error("JS tests are not supported; use wasmJs")
}
