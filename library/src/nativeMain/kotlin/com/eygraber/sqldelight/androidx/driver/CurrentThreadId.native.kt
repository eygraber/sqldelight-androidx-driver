package com.eygraber.sqldelight.androidx.driver

import kotlinx.atomicfu.atomic
import kotlin.native.concurrent.ThreadLocal

private val nextThreadId = atomic(0L)

@ThreadLocal
private object CurrentThreadId {
  val id: Long = nextThreadId.incrementAndGet()
}

internal actual fun currentThreadId(): Long = CurrentThreadId.id
