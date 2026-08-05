package com.eygraber.sqldelight.androidx.driver

/**
 * Returns an identifier for the current thread that is unique among all live threads.
 */
internal expect fun currentThreadId(): Long
