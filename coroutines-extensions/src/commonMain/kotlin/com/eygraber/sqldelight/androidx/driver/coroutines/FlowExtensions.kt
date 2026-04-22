@file:JvmName("FlowQuery")
@file:Suppress("MultilineLambdaItParameter")

package com.eygraber.sqldelight.androidx.driver.coroutines

import app.cash.sqldelight.Query
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmName

/** Turns this [Query] into a [Flow] which emits whenever the underlying result set changes. */
@JvmName("toFlow")
public fun <T : Any> Query<T>.asFlow(): Flow<Query<T>> = flow {
  val channel = Channel<Unit>(CONFLATED)
  channel.trySend(Unit)

  val listener = Query.Listener {
    channel.trySend(Unit)
  }

  addListener(listener)
  try {
    @Suppress("UnusedVariable")
    for(item in channel) {
      emit(this@asFlow)
    }
  }
  finally {
    removeListener(listener)
  }
}

public fun <T : Any> Flow<Query<T>>.mapToOne(
  context: CoroutineContext = EmptyCoroutineContext,
): Flow<T> = map {
  withContext(context) {
    it.awaitAsOne()
  }
}

public fun <T : Any> Flow<Query<T>>.mapToOneOrDefault(
  defaultValue: T,
  context: CoroutineContext = EmptyCoroutineContext,
): Flow<T> = map {
  withContext(context) {
    it.awaitAsOneOrNull() ?: defaultValue
  }
}

public fun <T : Any> Flow<Query<T>>.mapToOneOrNull(
  context: CoroutineContext = EmptyCoroutineContext,
): Flow<T?> = map {
  withContext(context) {
    it.awaitAsOneOrNull()
  }
}

public fun <T : Any> Flow<Query<T>>.mapToOneNotNull(
  context: CoroutineContext = EmptyCoroutineContext,
): Flow<T> = mapNotNull {
  withContext(context) {
    it.awaitAsOneOrNull()
  }
}

public fun <T : Any> Flow<Query<T>>.mapToList(
  context: CoroutineContext = EmptyCoroutineContext,
): Flow<List<T>> = map {
  withContext(context) {
    it.awaitAsList()
  }
}
