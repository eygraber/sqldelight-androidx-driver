package com.eygraber.sqldelight.androidx.driver

// JS/wasmJs have a single execution thread
internal actual fun currentThreadId(): Long = 0L
