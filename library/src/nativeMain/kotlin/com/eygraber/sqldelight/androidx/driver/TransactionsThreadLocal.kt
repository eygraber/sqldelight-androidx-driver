package com.eygraber.sqldelight.androidx.driver

import androidx.collection.mutableIntObjectMapOf
import app.cash.sqldelight.Transacter
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private object ThreadLocalTransactions {
  val threadLocalMap = mutableIntObjectMapOf<Transacter.Transaction>()
}

private object ThreadLocalId {
  val id = AtomicInt(0)
  fun next(): Int = id.incrementAndGet()
}

internal actual class TransactionsThreadLocal actual constructor() {
  private val threadLocalId = ThreadLocalId.next()

  actual fun get() = ThreadLocalTransactions.threadLocalMap[threadLocalId]

  actual fun set(transaction: Transacter.Transaction?) {
    when(transaction) {
      null -> ThreadLocalTransactions.threadLocalMap.remove(threadLocalId)
      else -> ThreadLocalTransactions.threadLocalMap[threadLocalId] = transaction
    }
  }
}
