import com.android.build.api.variant.HasUnitTest
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
    androidNamespace = "com.eygraber.sqldelight.androidx.driver.integration",
  )

  androidLibrary {
    withHostTest {}
  }

  sourceSets {
    named("androidHostTest").dependencies {
      implementation(libs.test.junit)
      implementation(libs.test.androidx.core)
      implementation(libs.test.robolectric)
    }

    commonTest.dependencies {
      implementation(projects.library)

      implementation(libs.androidx.sqliteBundled)
      implementation(libs.cashapp.sqldelight.coroutines)
      implementation(libs.cashapp.sqldelight.runtime)

      implementation(libs.kotlinx.coroutines.core)

      implementation(libs.test.kotlin)
      implementation(libs.test.kotlinx.coroutines)
    }

    jvmTest.dependencies {
      implementation(libs.test.kotlin.junit)
    }

    nativeTest.dependencies {
      implementation(libs.okio)
    }
  }
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
