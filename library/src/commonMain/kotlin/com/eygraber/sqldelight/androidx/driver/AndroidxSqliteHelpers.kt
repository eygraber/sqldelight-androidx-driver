package com.eygraber.sqldelight.androidx.driver

public fun AndroidxSqliteDriver.enableForeignKeys() {
  execute(null, "PRAGMA foreign_keys = ON", 0, null)
}

public fun AndroidxSqliteDriver.disableForeignKeys() {
  execute(null, "PRAGMA foreign_keys = OFF", 0, null)
}

public fun AndroidxSqliteDriver.enableWAL() {
  execute(null, "PRAGMA journal_mode = WAL", 0, null)
}

public fun AndroidxSqliteDriver.disableWAL() {
  execute(null, "PRAGMA journal_mode = DELETE", 0, null)
}
