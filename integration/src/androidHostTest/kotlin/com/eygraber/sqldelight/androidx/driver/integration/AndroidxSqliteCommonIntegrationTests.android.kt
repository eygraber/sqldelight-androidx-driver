package com.eygraber.sqldelight.androidx.driver.integration

import java.io.File

actual fun deleteFile(name: String) {
  File(name).delete()
}
