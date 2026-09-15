package com.eygraber.sqldelight.androidx.driver

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Suppress("InjectDispatcher")
internal actual fun defaultIoDispatcher(): CoroutineDispatcher = Dispatchers.Default

internal actual fun cpuCacheHitOptimizedProvider(): (Int, String) -> CoroutineDispatcher =
  throw UnsupportedOperationException(
    "CpuCacheHitOptimizedProvider is not available on web because web targets have no threads. " +
      "Use memoryOptimizedProvider() instead.",
  )
