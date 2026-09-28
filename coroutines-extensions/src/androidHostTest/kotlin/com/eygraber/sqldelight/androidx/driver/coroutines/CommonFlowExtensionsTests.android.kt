package com.eygraber.sqldelight.androidx.driver.coroutines

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
actual class CommonFlowExtensionsTest : FlowExtensionsTest()

actual fun testSqliteDriver(): SQLiteDriver = AndroidSQLiteDriver()
