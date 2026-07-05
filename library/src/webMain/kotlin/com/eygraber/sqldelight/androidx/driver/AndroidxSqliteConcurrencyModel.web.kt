package com.eygraber.sqldelight.androidx.driver

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@Suppress("InjectDispatcher")
internal actual fun defaultIoDispatcher(): CoroutineDispatcher = Dispatchers.Default

// Note: limitedParallelism bounds how many coroutines run between suspension points; it does
// NOT make suspend-spanning sections atomic — a suspended coroutine yields its slot to the next
// one. Nothing on web relies on it for mutual exclusion: WebConnectionPool never uses the
// concurrency model's dispatcher and serializes connection use with a Mutex instead.
@OptIn(ExperimentalCoroutinesApi::class)
internal actual fun memoryOptimizedDispatcher(
  dispatcher: CoroutineDispatcher,
  parallelism: Int,
  name: String,
): CoroutineDispatcher = dispatcher.limitedParallelism(
  parallelism = parallelism,
  name = name,
)
