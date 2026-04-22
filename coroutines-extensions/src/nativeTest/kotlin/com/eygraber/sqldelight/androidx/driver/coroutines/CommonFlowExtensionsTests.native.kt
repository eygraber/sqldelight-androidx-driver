package com.eygraber.sqldelight.androidx.driver.coroutines

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

actual class CommonFlowExtensionsTest : FlowExtensionsTest()

actual fun testSQLiteDriver(): SQLiteDriver = BundledSQLiteDriver()
