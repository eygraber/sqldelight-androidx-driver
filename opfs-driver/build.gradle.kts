import org.gradle.api.tasks.PathSensitivity

plugins {
  id("com.eygraber.conventions-kotlin-multiplatform")
  id("com.eygraber.conventions-detekt2")
  id("com.eygraber.conventions-publish-maven-central")
}

// Resolvable configuration that pulls in the webpack-bundled worker JS from :opfs-worker-impl
// via attribute matching. Going through the dependency graph (rather than reaching into the
// other project's tasks directly) is the Project-Isolation-safe way to consume another module's
// build output — Gradle wires the producer task in transparently when the artifact is resolved.
val opfsWorkerJsAttribute: Attribute<String> =
  Attribute.of("com.eygraber.sqldelight.opfsWorker", String::class.java)

val opfsWorkerJs = configurations.register("opfsWorkerJs") {
  isCanBeConsumed = false
  isCanBeResolved = true
  attributes {
    attribute(opfsWorkerJsAttribute, "bundle")
  }
}

dependencies {
  opfsWorkerJs(projects.opfsWorkerImpl)
}

val generateWorkerSource = tasks.register("generateWorkerSource") {
  group = "build"
  description = "Embeds the :opfs-worker-impl webpack output into a Kotlin constant."

  val workerJsFiles: FileCollection = opfsWorkerJs.get()
  inputs.files(workerJsFiles)
    .withPropertyName("workerJsInput")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  val outputDir = layout.buildDirectory.dir("generated/source/opfsWorker/webMain/kotlin")
  outputs.dir(outputDir).withPropertyName("generatedSourceDir")

  doLast {
    val js = workerJsFiles.singleFile.readText(Charsets.UTF_8)

    require(!js.contains("\"\"\"")) {
      "Webpack output contains the literal sequence \"\"\" which would break the Kotlin " +
        "raw-string delimiter. Switch the generator to base64 fallback."
    }

    val packageDir = outputDir.get()
      .dir("com/eygraber/sqldelight/androidx/driver/opfs/generated")
      .asFile
    packageDir.mkdirs()

    File(packageDir, "OpfsWorkerSource.kt").writeText(
      buildString {
        appendLine("package com.eygraber.sqldelight.androidx.driver.opfs.generated")
        appendLine()
        appendLine("internal const val OPFS_WORKER_SOURCE: String = \$\$\$\$\"\"\"")
        append(js)
        if(!js.endsWith("\n")) appendLine()
        appendLine("\"\"\"")
      },
    )
  }
}

kotlin {
  kmpTargets(
    KmpTarget.Js,
    KmpTarget.WasmJs,
    project = project,
    ignoreDefaultTargets = true,
  )

  sourceSets {
    webMain {
      kotlin.srcDir(generateWorkerSource)

      dependencies {
        api(libs.androidx.sqliteWeb)
        api(libs.kotlinx.browser)

        api(npm("@sqlite.org/sqlite-wasm", libs.versions.sqliteWasm.get()))
      }
    }
  }
}
