package com.eygraber.sqldelight.androidx.driver

import java.io.File as JavaFile

public fun AndroidxSqliteDatabaseType.Companion.File(
  file: JavaFile,
): AndroidxSqliteDatabaseType.File = AndroidxSqliteDatabaseType.File(file.absolutePath)
