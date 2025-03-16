package com.eygraber.sqldelight.androidx.driver

import androidx.collection.mutableIntObjectMapOf
import app.cash.sqldelight.Transacter
import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private object ThreadLocalTransactions {
  val threadLocalMap = mutableIntObjectMapOf<Transacter.Transaction>()
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
