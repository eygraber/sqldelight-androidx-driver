package com.eygraber.sqldelight.androidx.driver

import platform.posix.remove

internal actual fun deleteFileIfExists(path: String) {
  remove(path)
}
