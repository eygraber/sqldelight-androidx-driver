package com.eygraber.sqldelight.androidx.driver

import android.app.Application
import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import org.junit.Test

class AndroidxSqliteDatabaseFileProviderTest {
  @Test
  fun fileProviderDoesNotViolateStrictMode() {
    val oldPolicy = StrictMode.getThreadPolicy()
    try {
      StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
          .detectDiskReads()
          .penaltyDeath()
          .build(),
      )
      AndroidxSqliteDatabaseType.FileProvider(
        context = ApplicationProvider.getApplicationContext<Application>(),
        name = "test.db",
      )
    } finally {
      StrictMode.setThreadPolicy(oldPolicy)
    }
  }

  @Test
  fun fileGetDatabasePathDoesViolateStrictMode() {
    val oldPolicy = StrictMode.getThreadPolicy()
    try {
      StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
          .detectDiskReads()
          .penaltyDeath()
          .build(),
      )

      val context = ApplicationProvider.getApplicationContext<Application>()
      AndroidxSqliteDatabaseType.File(
        databaseFilePath = context.getDatabasePath("test.db").absolutePath,
      )

      error("Should have thrown an exception")
    }
    catch(_: Throwable) {}
    finally {
      StrictMode.setThreadPolicy(oldPolicy)
    }
  }
}
