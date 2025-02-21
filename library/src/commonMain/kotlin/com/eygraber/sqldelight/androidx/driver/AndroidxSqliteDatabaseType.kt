package com.eygraber.sqldelight.androidx.driver

public sealed interface AndroidxSqliteDatabaseType {
  public data class File(val databaseFilePath: String) : AndroidxSqliteDatabaseType
  public data object Memory : AndroidxSqliteDatabaseType
  public data object Temporary : AndroidxSqliteDatabaseType

  public companion object
}
