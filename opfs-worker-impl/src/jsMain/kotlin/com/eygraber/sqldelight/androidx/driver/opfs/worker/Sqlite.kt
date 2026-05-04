package com.eygraber.sqldelight.androidx.driver.opfs.worker

internal fun ensureLocalSqlite(onDone: () -> Unit, onError: (dynamic) -> Unit) {
  if(poolUtil != null) {
    onDone()
    return
  }
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
            installPool(0, onDone, onError)
          },
          onError,
        )
      },
      onError,
    )
  }
  else {
    installPool(0, onDone, onError)
  }
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
