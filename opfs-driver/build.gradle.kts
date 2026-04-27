plugins {
  id("com.eygraber.conventions-kotlin-multiplatform")
  id("com.eygraber.conventions-detekt2")
  id("com.eygraber.conventions-publish-maven-central")
}

kotlin {
  // ignoreDefaultTargets = true so the project's default KMP targets (Android, iOS,
  // JVM, etc.) are excluded — opfs-driver only ships JS and wasmJs. Without this flag the
  // conventions plugin's kmpTargets call additively merges with the root defaults, leaving
  // every other target wired up but with no source — which the snapshot publish then
  // chokes on because the iosArm64 klib (etc.) was never produced.
  kmpTargets(
    KmpTarget.Js,
    KmpTarget.WasmJs,
    project = project,
    ignoreDefaultTargets = true,
  )

  sourceSets {
    webMain.dependencies {
      api(libs.kotlinx.browser)

      implementation(npm("@sqlite.org/sqlite-wasm", libs.versions.sqliteWasm.get()))
    }
  }
}
