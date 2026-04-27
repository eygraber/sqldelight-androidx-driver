package com.eygraber.sqldelight.androidx.driver.integration

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

actual fun testSqliteDriver(): SQLiteDriver = BundledSQLiteDriver()
