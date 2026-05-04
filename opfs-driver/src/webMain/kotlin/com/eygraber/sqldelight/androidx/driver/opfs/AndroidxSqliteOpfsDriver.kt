package com.eygraber.sqldelight.androidx.driver.opfs

import androidx.sqlite.driver.web.WebWorkerSQLiteDriver

public expect fun androidxSqliteOpfsDriver(
  multiTabMode: OpfsMultiTabMode = OpfsMultiTabMode.Default,
  onLockStateChange: ((OpfsLockState) -> Unit)? = null,
): WebWorkerSQLiteDriver
