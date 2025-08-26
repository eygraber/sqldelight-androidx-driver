package com.eygraber.sqldelight.androidx.driver

import android.content.Context
import java.io.File as JavaFile

public fun AndroidxSqliteDatabaseType.Companion.FileProvider(
  context: Context,
  name: String,
): AndroidxSqliteDatabaseType.FileProvider {
  // keep context out of the lambda so it isn't captured and leaked
  val applicationContext = context.applicationContext

  return AndroidxSqliteDatabaseType.FileProvider {
    applicationContext.getDatabasePath(name).absolutePath
  }
}

public fun AndroidxSqliteDatabaseType.Companion.File(
  file: JavaFile,
): AndroidxSqliteDatabaseType.File = AndroidxSqliteDatabaseType.File(file.absolutePath)
