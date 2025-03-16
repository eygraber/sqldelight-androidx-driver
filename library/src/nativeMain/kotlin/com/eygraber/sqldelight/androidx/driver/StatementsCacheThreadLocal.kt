package com.eygraber.sqldelight.androidx.driver

import androidx.collection.LruCache
import androidx.collection.mutableIntObjectMapOf
import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private object ThreadLocalStatementsCache {
  val threadLocalMap = mutableIntObjectMapOf<LruCache<Int, AndroidxStatement>>()
}

internal actual class StatementsCacheThreadLocal actual constructor() {
  private val threadLocalId = ThreadLocalId.next()

  actual fun get() = ThreadLocalStatementsCache.threadLocalMap[threadLocalId]

  actual fun set(cache: LruCache<Int, AndroidxStatement>?) {
    when(cache) {
      null -> ThreadLocalStatementsCache.threadLocalMap.remove(threadLocalId)
      else -> ThreadLocalStatementsCache.threadLocalMap[threadLocalId] = cache
    }
  }

  actual fun getAll(): List<LruCache<Int, AndroidxStatement>> = buildList {
    ThreadLocalStatementsCache.threadLocalMap.forEachValue { add(it) }
  }
}
