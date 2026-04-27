package com.eygraber.sqldelight.androidx.driver

internal actual suspend fun <R> withTransactionThreadConfinement(
  block: suspend () -> R,
): R = block()
