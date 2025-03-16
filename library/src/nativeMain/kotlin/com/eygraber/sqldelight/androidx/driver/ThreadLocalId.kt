package com.eygraber.sqldelight.androidx.driver

import kotlin.concurrent.AtomicInt

internal object ThreadLocalId {
  val id = AtomicInt(0)
  fun next(): Int = id.incrementAndGet()
}
