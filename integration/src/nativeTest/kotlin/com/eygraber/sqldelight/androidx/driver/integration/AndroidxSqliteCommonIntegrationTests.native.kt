package com.eygraber.sqldelight.androidx.driver.integration

import okio.FileSystem
import okio.Path.Companion.toPath

actual suspend fun deleteFile(name: String) {
  FileSystem.SYSTEM.delete(name.toPath())
}
