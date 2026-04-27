package com.eygraber.sqldelight.androidx.driver.integration

import java.io.File

actual suspend fun deleteFile(name: String) {
  File(name).delete()
}
