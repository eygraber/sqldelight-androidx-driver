package com.eygraber.sqldelight.androidx.driver

import androidx.collection.LruCache
import androidx.collection.mutableLongObjectMapOf

internal actual class StatementsCacheThreadLocal actual constructor() {
  private val allCaches = mutableLongObjectMapOf<LruCache<Int, AndroidxStatement>>()
  private val caches = ThreadLocal<LruCache<Int, AndroidxStatement>>()

  internal actual fun get(): LruCache<Int, AndroidxStatement>? = caches.get()

  internal actual fun set(cache: LruCache<Int, AndroidxStatement>?) {
    when(cache) {
      null -> allCaches.remove(Thread.currentThread().id)
      else -> allCaches[Thread.currentThread().id] = cache
    }

    caches.set(cache)
  }

  internal actual fun getAll() = buildList {
    allCaches.forEachValue { add(it) }
  }
}
