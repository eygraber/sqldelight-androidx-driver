plugins {
  kotlin("multiplatform")
  id("com.eygraber.conventions-detekt2")
}

kotlin {
  js(IR) {
    useEsModules()
    browser {
      binaries.executable()
    }
  }

  sourceSets {
    jsMain.dependencies {
      implementation(libs.kotlinx.browser)
    }
  }
}

// Expose the webpack-bundled worker JS as a consumable artifact with a custom attribute. Other
// modules pick this up via a matching resolvable configuration; the artifact (and its producer
// task) flows through the dependency graph, so no cross-project task lookup is needed and the
// build stays compatible with Gradle Project Isolation.
val opfsWorkerJsAttribute: Attribute<String> =
  Attribute.of("com.eygraber.sqldelight.opfsWorker", String::class.java)

val opfsWorkerJsBundle = configurations.register("opfsWorkerJsBundle") {
  isCanBeConsumed = true
  isCanBeResolved = false
  attributes {
    attribute(opfsWorkerJsAttribute, "bundle")
  }
}

// Point the artifact at the well-known webpack output path so Gradle doesn't have to evaluate
// the producer task's outputs at graph-resolution time (which fails: `task.outputs.files` is
// empty until the task has run, and `singleFile` blows up). The `builtBy` keeps the producer
// task wired into the dependency graph, so consumers still trigger webpack on demand.
val workerWebpack = tasks.named("jsBrowserProductionWebpack")
val workerJsBundle: Provider<RegularFile> = layout.buildDirectory.file(
  "kotlin-webpack/js/productionExecutable/opfs-worker-impl.js",
)

artifacts {
  add(opfsWorkerJsBundle.name, workerJsBundle) {
    builtBy(workerWebpack)
  }
}
