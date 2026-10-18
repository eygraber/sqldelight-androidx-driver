package com.eygraber.sqldelight.androidx.driver

/**
 * Closes every connection and deletes the database file with its `-journal`, `-wal`, and `-shm`
 * files. All data is lost. The next interaction opens a new file and runs the first-interaction
 * work again, with `onCreate`. Waits for checked-out connections. For [AndroidxSqliteDatabaseType.Memory]
 * and [AndroidxSqliteDatabaseType.Temporary] it only closes the connections. Do not call it
 * inside a transaction.
 */
public suspend fun AndroidxSqliteDriver.deleteDatabase() {
  resetDatabase(::deleteDatabaseFiles)
}

internal fun deleteDatabaseFiles(databaseFilePath: String) {
  for(suffix in listOf("", "-journal", "-wal", "-shm")) {
    deleteFileIfExists("$databaseFilePath$suffix")
  }
}

internal expect fun deleteFileIfExists(path: String)
