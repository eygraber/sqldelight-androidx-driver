package com.eygraber.sqldelight.androidx.driver.coroutines

import androidx.sqlite.SQLiteDriver

expect class CommonFlowExtensionsTest() : FlowExtensionsTest

expect fun testSQLiteDriver(): SQLiteDriver
