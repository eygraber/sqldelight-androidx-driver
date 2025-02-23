plugins {
  id("com.eygraber.conventions-kotlin-multiplatform")
  id("com.eygraber.conventions-android-library")
  id("com.eygraber.conventions-detekt")
  id("com.eygraber.conventions-publish-maven-central")
  alias(libs.plugins.atomicfu)
}

android {
  namespace = "com.eygraber.sqldelight.androidx.driver"
}

kotlin {
  defaultKmpTargets(
    project = project,
  )

  sourceSets {
    androidMain.dependencies {
      implementation(libs.atomicfu)
    }

    androidUnitTest.dependencies {
      implementation(libs.androidx.sqliteFramework)

      implementation(libs.test.junit)
      implementation(libs.test.androidx.core)
      implementation(libs.test.robolectric)
    }

    commonMain.dependencies {
      implementation(libs.androidx.collections)

      api(libs.androidx.sqlite)
      api(libs.cashapp.sqldelight.runtime)
    }

    commonTest.dependencies {
      implementation(libs.kotlinx.coroutines.core)

      implementation(libs.test.kotlin)
      implementation(libs.test.kotlinx.coroutines)
    }

    jvmTest.dependencies {
      implementation(libs.androidx.sqliteBundled)
      implementation(libs.test.kotlin.junit)
    }

    nativeTest.dependencies {
      implementation(libs.androidx.sqliteBundled)
      implementation(libs.okio)
    }
  }
}
