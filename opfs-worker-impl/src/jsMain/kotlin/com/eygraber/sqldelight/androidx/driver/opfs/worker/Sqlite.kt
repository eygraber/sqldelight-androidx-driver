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
    importAndInstallSqlite3(
      attempt = 0,
      onDone = { installPool(0, ::finishLocalSqliteInitSuccess, ::finishLocalSqliteInitFailure) },
      onError = ::finishLocalSqliteInitFailure,
    )
  }
  else {
    installPool(0, ::finishLocalSqliteInitSuccess, ::finishLocalSqliteInitFailure)
  }
}

// An import/install failure at boot is usually transient — the tab went offline or a mobile
// browser killed the in-flight download when it was backgrounded — so retry with backoff
// before giving up; connectivity returning makes a later attempt win. Without this, a single
// interrupted download leaves sqlite3 null forever and the driver's requests hang.
private fun importAndInstallSqlite3(attempt: Int, onDone: () -> Unit, onError: (dynamic) -> Unit) {
  val sqlite3Url: String = initData.sqlite3Url.unsafeCast<String>()
  val wasmUrl: String = initData.wasmUrl.unsafeCast<String>()
  // Retries append a fragment: some browsers cache a *failed* dynamic import in the module
  // map, so re-importing the identical URL keeps failing even after connectivity returns. A
  // fragment gives the retry a fresh module key without changing the network request (so a
  // successful fetch still hits the HTTP cache).
  val url = if(attempt == 0) sqlite3Url else "$sqlite3Url#retry$attempt"
  thenAccept(
    dynamicImport(url),
    { mod ->
      thenAccept(
        installSqlite3(mod, wasmUrl),
        { factory ->
          sqlite3 = factory
          onDone()
        },
        { err -> retryImportOrFail(attempt, err, onDone, onError) },
      )
    },
    { err -> retryImportOrFail(attempt, err, onDone, onError) },
  )
}

private fun retryImportOrFail(attempt: Int, err: dynamic, onDone: () -> Unit, onError: (dynamic) -> Unit) {
  if(attempt >= 5) {
    onError(err)
  }
  else {
    // 250ms, 500ms, 1s, 2s, 4s
    setTimeout(250 shl attempt) { importAndInstallSqlite3(attempt + 1, onDone, onError) }
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
