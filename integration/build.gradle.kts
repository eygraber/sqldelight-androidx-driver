import com.android.build.api.variant.HasUnitTest
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
  id("com.android.lint")
  id("com.eygraber.conventions-kotlin-multiplatform")
  id("com.eygraber.conventions-android-kmp-library")
  id("com.eygraber.conventions-detekt2")
  alias(libs.plugins.sqldelight)
}

kotlin {
  defaultKmpTargets(
    project = project,
    webOptions = KmpTarget.WebOptions(
      isNodeEnabled = false,
      isBrowserEnabled = true,
      isBrowserEnabledForLibraryTests = true,
    ),
    androidNamespace = "com.eygraber.sqldelight.androidx.driver.integration",
  )

  android {
    withHostTest {}
  }

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
    named("androidHostTest").dependencies {
      implementation(libs.test.junit)
      implementation(libs.test.androidx.core)
      implementation(libs.test.robolectric)
    }

    commonTest.dependencies {
      implementation(projects.coroutinesExtensions)
      implementation(projects.library)

      implementation(libs.cashapp.sqldelight.runtime)

      implementation(libs.kotlinx.coroutines.core)

      implementation(libs.test.kotlin)
      implementation(libs.test.kotlinx.coroutines)
    }

    named("nonWebTest").dependencies {
      implementation(libs.androidx.sqliteBundled)
    }

    jvmTest.dependencies {
      implementation(libs.test.kotlin.junit)
    }

    nativeTest.dependencies {
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

// Webpack resolves `new URL("./sqldelight-androidx-opfs-worker.js", import.meta.url)`
// relative to the bundled output directory. Copy the worker resource into the wasmJs test
// bundle so that path resolves at test time.
tasks.named<Copy>("wasmJsTestProcessResources") {
  from(project(":opfs-driver").layout.projectDirectory.dir("src/wasmJsMain/resources"))
}

// Per the Option B decision the JS target is wired up but contains no tests. Keep the test
// task happy when there's nothing to discover.
tasks.named<org.gradle.api.tasks.testing.AbstractTestTask>("jsBrowserTest") {
  failOnNoDiscoveredTests = false
}

sqldelight {
  linkSqlite = false

  databases {
    create("AndroidXDb") {
      dialect(libs.cashapp.sqldelight.dialect)

      packageName = "com.eygraber.sqldelight.androidx.driver.integration"

      schemaOutputDirectory = file("src/main/sqldelight/migrations")

      deriveSchemaFromMigrations = false
      treatNullAsUnknownForEquality = true
      generateAsync = true
    }
  }
}

gradleConventions {
  kotlin {
    explicitApiMode = ExplicitApiMode.Disabled
  }
}

androidComponents {
  onVariants { variant ->
    (variant as HasUnitTest).unitTest?.let { unitTest ->
      with(unitTest.runtimeConfiguration.resolutionStrategy.dependencySubstitution) {
        val bundledArtifact = libs.androidx.sqliteBundled.get().toString()
        val bundledJvmArtifact = bundledArtifact.replace("sqlite-bundled", "sqlite-bundled-jvm")
        substitute(module(bundledArtifact))
          .using(module(bundledJvmArtifact))
      }
    }
  }
}
