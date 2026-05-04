package com.eygraber.sqldelight.androidx.driver.opfs

import androidx.sqlite.driver.web.WebWorkerSQLiteDriver

public actual fun androidxSqliteOpfsDriver(
  multiTabMode: OpfsMultiTabMode,
  onLockStateChange: ((OpfsLockState) -> Unit)?,
): WebWorkerSQLiteDriver = WebWorkerSQLiteDriver(
  worker = opfsWorker(
    multiTabMode = multiTabMode,
    onLockStateChange = onLockStateChange,
  ),
)
