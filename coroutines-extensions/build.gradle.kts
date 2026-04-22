plugins {
  id("com.android.lint")
  id("com.eygraber.conventions-kotlin-multiplatform")
  id("com.eygraber.conventions-android-kmp-library")
  id("com.eygraber.conventions-detekt2")
  id("com.eygraber.conventions-publish-maven-central")
}

kotlin {
  defaultKmpTargets(
    project = project,
    androidNamespace = "com.eygraber.sqldelight.androidx.driver.coroutines",
  )

  androidLibrary {
    withHostTest {}
  }

  sourceSets {
    commonMain.dependencies {
      api(libs.cashapp.sqldelight.runtime)
      api(libs.kotlinx.coroutines.core)

      implementation(libs.cashapp.sqldelight.async)
    }

    commonTest.dependencies {
      implementation(projects.library)

      implementation(libs.cashapp.sqldelight.async)
      implementation(libs.kotlinx.coroutines.core)

      implementation(libs.test.kotlin)
      implementation(libs.test.kotlinx.coroutines)
    }

    named("androidHostTest").dependencies {
      implementation(libs.androidx.sqliteFramework)

      implementation(libs.test.junit)
      implementation(libs.test.androidx.core)
      implementation(libs.test.robolectric)
    }

    jvmTest.dependencies {
      implementation(libs.androidx.sqliteBundled)
      implementation(libs.test.kotlin.junit)
    }

    nativeTest.dependencies {
      implementation(libs.androidx.sqliteBundled)
    }
  }
}
