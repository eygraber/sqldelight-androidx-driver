package com.eygraber.sqldelight.androidx.driver

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.newFixedThreadPoolContext

@Suppress("InjectDispatcher")
internal actual fun defaultIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal actual fun cpuCacheHitOptimizedProvider(): (Int, String) -> CoroutineDispatcher = { parallelism, name ->
  newFixedThreadPoolContext(
    nThreads = parallelism,
    name = name,
  )
}
