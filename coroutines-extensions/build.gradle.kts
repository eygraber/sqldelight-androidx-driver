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
    androidNamespace = "com.eygraber.sqldelight.androidx.driver.coroutines",
  )

  android {
    withHostTest {}
  }

  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser {
      testTask {
        useKarma {
          useChromeHeadless()
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

    named("wasmJsTest").dependencies {
      implementation(projects.opfsDriver)
      implementation(libs.androidx.sqliteWeb)
      implementation(libs.kotlinx.browser)
      implementation(npm("@sqlite.org/sqlite-wasm", libs.versions.sqliteWasm.get()))
    }
  }
}

// Webpack resolves `new URL("./sqldelight-androidx-opfs-worker.js", import.meta.url)`
// relative to the bundled output directory. Copy the worker resource into the wasmJs test
// bundle so that path resolves at test time.
tasks.named<Copy>("wasmJsTestProcessResources") {
  from(project(":opfs-driver").layout.projectDirectory.dir("src/wasmJsMain/resources"))
}

// Per the Option B decision the JS target is wired up but tests run only on wasmJs. The JS
// test compilation must contain expect/actual stubs to satisfy the contract, which means the
// JS test task picks up the inherited test methods even though they can't run (the stub
// driver throws). Skip the task entirely.
tasks.named("jsBrowserTest") {
  enabled = false
}
tasks.named("jsTest") {
  enabled = false
}
