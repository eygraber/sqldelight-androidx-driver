package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteDriver

internal actual fun closeSqliteDriver(driver: SQLiteDriver) {
  (driver as? AutoCloseable)?.close()
}
