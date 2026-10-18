package com.eygraber.sqldelight.androidx.driver

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.ContinuationInterceptor

@Suppress("RunBlockingInSuspendFunction")
internal actual suspend fun <R> withTransactionThreadConfinement(
  block: suspend () -> R,
): R {
  val context = currentCoroutineContext().minusKey(ContinuationInterceptor)
  return runBlocking(context) {
    block()
  }
}
