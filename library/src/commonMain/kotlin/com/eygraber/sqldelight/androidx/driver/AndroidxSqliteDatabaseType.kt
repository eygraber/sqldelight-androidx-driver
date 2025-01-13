package com.eygraber.sqldelight.androidx.driver

public sealed interface AndroidxSqliteDatabaseType {
  public data class File(val filename: String) : AndroidxSqliteDatabaseType
  public data object Memory : AndroidxSqliteDatabaseType
  public data object Temporary : AndroidxSqliteDatabaseType
}
