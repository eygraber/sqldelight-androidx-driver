package com.eygraber.sqldelight.androidx.driver.coroutines

import androidx.sqlite.SQLiteDriver

// Stubs to satisfy the expect/actual contract on the JS target. Per the project's Option B
// decision (wasmJs-only for tests), the JS test compilation contains zero tests and these
// helpers are never invoked at runtime.

actual class CommonFlowExtensionsTest : FlowExtensionsTest()

actual fun testSqliteDriver(): SQLiteDriver = error("JS tests are not supported; use wasmJs")
