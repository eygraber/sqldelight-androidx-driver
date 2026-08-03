package com.eygraber.sqldelight.androidx.driver

@Suppress("DEPRECATION")
internal actual fun currentThreadId(): Long = Thread.currentThread().id
