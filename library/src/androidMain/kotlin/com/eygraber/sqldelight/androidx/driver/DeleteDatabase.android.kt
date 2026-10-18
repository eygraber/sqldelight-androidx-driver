package com.eygraber.sqldelight.androidx.driver

import java.io.File

internal actual fun deleteFileIfExists(path: String) {
  File(path).delete()
}
