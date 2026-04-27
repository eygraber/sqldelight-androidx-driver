package com.eygraber.sqldelight.androidx.driver

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.newFixedThreadPoolContext

@Suppress("InjectDispatcher")
internal actual fun defaultIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

@OptIn(ExperimentalCoroutinesApi::class)
internal actual fun memoryOptimizedDispatcher(
  dispatcher: CoroutineDispatcher,
  parallelism: Int,
  name: String,
): CoroutineDispatcher = dispatcher.limitedParallelism(
  parallelism = parallelism,
  name = name,
)

/**
 * A dispatcher provider that creates a fresh fixed thread pool sized to the requested
 * parallelism. Optimized for CPU cache hit rate, since each connection is pinned to its own
 * thread.
 *
 * Only available on non-web targets — JS and wasmJs are single-threaded, so a fixed thread
 * pool has no meaning there. Web users should stick with the default
 * [AndroidxSqliteConcurrencyModel.Companion.memoryOptimizedProvider].
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
public val AndroidxSqliteConcurrencyModel.Companion.CpuCacheHitOptimizedProvider: (Int, String) -> CoroutineDispatcher
  get() = { parallelism, name ->
    newFixedThreadPoolContext(
      nThreads = parallelism,
      name = name,
    )
  }
