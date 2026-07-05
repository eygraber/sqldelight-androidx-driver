package com.eygraber.sqldelight.androidx.driver.opfs.worker

private var localSqliteInitInFlight = false
private val localSqliteInitWaiters = mutableListOf<Pair<() -> Unit, (dynamic) -> Unit>>()

// Single-flight: the import/install chain is asynchronous, so a second call arriving while the
// first is in flight must join it instead of starting a second chain (which would install the
// SAH pool VFS twice).
internal fun ensureLocalSqlite(onDone: () -> Unit, onError: (dynamic) -> Unit) {
  if(poolUtil != null) {
    onDone()
    return
  }
  localSqliteInitWaiters.add(onDone to onError)
  if(localSqliteInitInFlight) return
  localSqliteInitInFlight = true
  if(sqlite3 == null) {
    val sqlite3Url: String = initData.sqlite3Url.unsafeCast<String>()
    val wasmUrl: String = initData.wasmUrl.unsafeCast<String>()
    thenAccept(
      dynamicImport(sqlite3Url),
      { mod ->
        thenAccept(
          installSqlite3(mod, wasmUrl),
          { factory ->
            sqlite3 = factory
            installPool(0, ::finishLocalSqliteInitSuccess, ::finishLocalSqliteInitFailure)
          },
          ::finishLocalSqliteInitFailure,
        )
      },
      ::finishLocalSqliteInitFailure,
    )
  }
  else {
    installPool(0, ::finishLocalSqliteInitSuccess, ::finishLocalSqliteInitFailure)
  }
}

private fun finishLocalSqliteInitSuccess() {
  localSqliteInitInFlight = false
  val waiters = localSqliteInitWaiters.toList()
  localSqliteInitWaiters.clear()
  waiters.forEach { (onDone, _) -> onDone() }
}

private fun finishLocalSqliteInitFailure(err: dynamic) {
  localSqliteInitInFlight = false
  val waiters = localSqliteInitWaiters.toList()
  localSqliteInitWaiters.clear()
  waiters.forEach { (_, onError) -> onError(err) }
}

private fun installPool(attempt: Int, onDone: () -> Unit, onError: (dynamic) -> Unit) {
  thenAccept(
    installSqliteOpfsSAHPoolVfs(sqlite3),
    { util ->
      poolUtil = util
      onDone()
    },
    { err ->
      if(attempt >= 11) {
        onError(err)
      }
      else {
        setTimeout(100 + attempt * 50) { installPool(attempt + 1, onDone, onError) }
      }
    },
  )
}

@Suppress("TooGenericExceptionCaught")
internal fun openPoolDbWithRetry(fileName: String): dynamic {
  var lastErr: dynamic = null
  for(attempt in 0 until 6) {
    try {
      return newOpfsSAHPoolDb(poolUtil, fileName)
    }
    catch(e: Throwable) {
      lastErr = e
      val deadline = currentTimeMs() + (50 + attempt * 25)
      while(currentTimeMs() < deadline) {
        // brief spin matching the JS implementation
      }
    }
  }
  throw if(lastErr != null) {
    lastErr.unsafeCast<Throwable>()
  }
  else {
    IllegalStateException("OpfsSAHPoolDb open failed")
  }
}
