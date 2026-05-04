import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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
    webOptions = KmpTarget.WebOptions(
      isNodeEnabled = false,
      isBrowserEnabled = true,
      isBrowserEnabledForLibraryTests = true,
    ),
    androidNamespace = "com.eygraber.sqldelight.androidx.driver",
  )

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser {
      testTask {
        useKarma {
          // ChromeHeadlessNoSandbox so the browser starts under restricted CI runners
          // (the default chrome-headless launcher fails to start on GitHub-hosted Linux
          // runners without --no-sandbox).
          useChromeHeadlessNoSandbox()
        }
      }
    }
  }

  android {
    withHostTest {}

    withDeviceTest {
      instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

      managedDevices {
        localDevices {
          create("pixel2Api35") {
            device = "Pixel 2"
            apiLevel = 35
            testedAbi = "x86_64"
            systemImageSource = "aosp-atd"
          }
        }
      }
    }
  }

  @OptIn(ExperimentalKotlinGradlePluginApi::class)
  applyDefaultHierarchyTemplate {
    common {
      group("nonWeb") {
        withCompilations { it.target.targetName == "android" }
        withJvm()
        group("native") {
          withNative()
        }
      }
    }
  }

  sourceSets {
    named("androidDeviceTest").dependencies {
      implementation(libs.androidx.sqliteFramework)

      implementation(libs.test.junit)
      implementation(libs.test.androidx.core)
      implementation(libs.test.androidx.runner)

      implementation(libs.test.kotlin)
      implementation(libs.test.kotlinx.coroutines)
    }

    named("androidHostTest").dependencies {
      implementation(libs.androidx.sqliteFramework)

      implementation(libs.test.junit)
      implementation(libs.test.androidx.core)
      implementation(libs.test.robolectric)
    }

    commonMain.dependencies {
      implementation(libs.androidx.collections)

      api(libs.androidx.sqlite)
      api(libs.androidx.sqliteAsync)
      api(libs.cashapp.sqldelight.runtime)
      api(libs.kotlinx.coroutines.core)

      implementation(libs.atomicfu)
    }

    commonTest.dependencies {
      implementation(libs.kotlinx.coroutines.core)

      implementation(libs.cashapp.sqldelight.async)

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

    named("wasmJsTest").dependencies {
      implementation(projects.opfsDriver)
      implementation(libs.androidx.sqliteWeb)
      implementation(libs.kotlinx.browser)
      implementation(npm("@sqlite.org/sqlite-wasm", libs.versions.sqliteWasm.get()))
    }
  }
}
