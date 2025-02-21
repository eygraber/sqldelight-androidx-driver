package com.eygraber.sqldelight.androidx.driver

import android.content.Context
import java.io.File as JavaFile

public fun AndroidxSqliteDatabaseType.Companion.File(
  context: Context,
  name: String,
): AndroidxSqliteDatabaseType.File = AndroidxSqliteDatabaseType.File(context.getDatabasePath(name).absolutePath)

public fun AndroidxSqliteDatabaseType.Companion.File(
  file: JavaFile,
): AndroidxSqliteDatabaseType.File = AndroidxSqliteDatabaseType.File(file.absolutePath)
