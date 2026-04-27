package com.eygraber.sqldelight.androidx.driver

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@Suppress("InjectDispatcher")
internal actual fun defaultIoDispatcher(): CoroutineDispatcher = Dispatchers.Default

// JS and wasmJs are single-threaded — limitedParallelism still serializes coroutines,
// which is what the connection-pool concurrency model relies on.
@OptIn(ExperimentalCoroutinesApi::class)
internal actual fun memoryOptimizedDispatcher(
  dispatcher: CoroutineDispatcher,
  parallelism: Int,
  name: String,
): CoroutineDispatcher = dispatcher.limitedParallelism(
  parallelism = parallelism,
  name = name,
)
