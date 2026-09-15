package com.eygraber.sqldelight.androidx.driver.coroutines

import androidx.sqlite.SQLiteDriver
import com.eygraber.sqldelight.androidx.driver.opfs.OpfsMultiTabMode
import com.eygraber.sqldelight.androidx.driver.opfs.androidxSqliteOpfsDriver

actual class CommonFlowExtensionsTest : FlowExtensionsTest()

actual fun testSqliteDriver(): SQLiteDriver = androidxSqliteOpfsDriver(OpfsMultiTabMode.Single)
