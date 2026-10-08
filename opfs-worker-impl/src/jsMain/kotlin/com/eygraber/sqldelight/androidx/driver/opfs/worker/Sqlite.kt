package com.eygraber.sqldelight.androidx.driver.opfs.worker

private const val MAX_INIT_RETRIES = 5
private const val INIT_INTERRUPTED = "SQLite initialization interrupted."

private var localSqliteInitInFlight = false
private val localSqliteInitWaiters = mutableListOf<Pair<() -> Unit, (dynamic) -> Unit>>()
private var localSqliteRetryTimer: Int? = null
private var sqlite3Module: dynamic = null
private var nextImportId = 0

// Single-flight: concurrent callers join the same import/install chain, so only one WASM
// instance and one SAH pool are installed. Keep a successfully imported module across failures.
internal fun ensureLocalSqlite(onDone: () -> Unit, onError: (dynamic) -> Unit) {
  if(poolUtil != null) {
    onDone()
    return
  }
  localSqliteInitWaiters.add(onDone to onError)
  if(localSqliteInitInFlight) return
  localSqliteInitInFlight = true
  when {
    sqlite3 != null -> installPool(0)
    sqlite3Module != null -> installFactory(0)
    else -> importSqlite3(0)
  }
}

// An active import/install must settle before handles can safely be released. A backoff timer
// holds no handles, so a pause or close can cancel it and settle the chain immediately.
internal fun cancelLocalSqliteRetry() {
  val timer = localSqliteRetryTimer ?: return
  clearTimeout(timer)
  localSqliteRetryTimer = null
  finishLocalSqliteInitFailure(INIT_INTERRUPTED)
}

private fun initInterrupted(): Boolean = pendingPause || closeRequested

private fun importSqlite3(attempt: Int) {
  if(initInterrupted()) {
    finishLocalSqliteInitFailure(INIT_INTERRUPTED)
    return
  }
  val url = initData.sqlite3Url.unsafeCast<String>()
  val importId = nextImportId++
  // Failed dynamic imports can remain in the browser's module map. Fragments give retries
  // fresh module identities while preserving HTTP caching; IDs also stay unique after resume.
  val importUrl = if(importId == 0) url else "$url${if('#' in url) '-' else '#'}retry$importId"
  thenAccept(
    dynamicImport(importUrl),
    { mod ->
      sqlite3Module = mod
      installFactory(0)
    },
    { err -> retryInit(attempt, err) { importSqlite3(attempt + 1) } },
  )
}

private fun installFactory(attempt: Int) {
  if(initInterrupted()) {
    finishLocalSqliteInitFailure(INIT_INTERRUPTED)
    return
  }
  thenAccept(
    installSqlite3(sqlite3Module, initData.wasmUrl.unsafeCast<String>()),
    { factory ->
      sqlite3 = factory
      installPool(0)
    },
    { err -> retryInit(attempt, err) { installFactory(attempt + 1) } },
  )
}

private fun retryInit(attempt: Int, err: dynamic, retry: () -> Unit) {
  if(attempt >= MAX_INIT_RETRIES || initInterrupted()) {
    finishLocalSqliteInitFailure(err)
  }
  else {
    scheduleLocalSqliteRetry(minOf(250 * (1 shl attempt), 4_000), retry)
  }
}

private fun scheduleLocalSqliteRetry(delayMs: Int, retry: () -> Unit) {
  localSqliteRetryTimer = setTimeout(delayMs) {
    localSqliteRetryTimer = null
    retry()
  }
}

internal fun whenLocalSqliteInitSettled(onSettled: () -> Unit) {
  if(!localSqliteInitInFlight) {
    onSettled()
    return
  }
  localSqliteInitWaiters.add(onSettled to { _ -> onSettled() })
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

private fun installPool(attempt: Int) {
  if(initInterrupted()) {
    finishLocalSqliteInitFailure(INIT_INTERRUPTED)
    return
  }
  thenAccept(
    installSqliteOpfsSAHPoolVfs(sqlite3),
    { util ->
      poolUtil = util
      finishLocalSqliteInitSuccess()
    },
    { err ->
      if(attempt >= 11 || initInterrupted()) {
        finishLocalSqliteInitFailure(err)
      }
      else {
        scheduleLocalSqliteRetry(100 + attempt * 50) { installPool(attempt + 1) }
      }
    },
  )
}
