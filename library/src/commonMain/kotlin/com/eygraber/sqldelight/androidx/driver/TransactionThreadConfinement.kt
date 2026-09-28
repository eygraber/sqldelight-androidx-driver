package com.eygraber.sqldelight.androidx.driver

/**
 * Pins [block] to the current thread for its full duration, regardless of any
 * [kotlinx.coroutines.CoroutineDispatcher] in the surrounding context.
 *
 * On non-web targets this is implemented with the Room-style `runBlocking` trick (drop the
 * [kotlin.coroutines.ContinuationInterceptor] from the context so suspend points stay on the
 * caller thread). On JS/wasmJs there is only one execution thread, so the implementation just
 * runs the block.
 */
internal expect suspend fun <R> withTransactionThreadConfinement(
  block: suspend () -> R,
): R
