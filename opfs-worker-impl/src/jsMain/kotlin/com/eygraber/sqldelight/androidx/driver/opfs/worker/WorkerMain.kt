package com.eygraber.sqldelight.androidx.driver.opfs.worker

fun main() {
  self.onmessage = ::onMessage
}

internal fun drainQueuedDriverMessages() {
  while(queuedDriverMessages.isNotEmpty()) {
    val e = queuedDriverMessages.removeAt(0)
    routeDriverMessage(e)
  }
}

private fun routeDriverMessage(e: MessageEventLike) {
  val requestMsg: dynamic = e.data
  if(!isObject(requestMsg) || !isObject(requestMsg.data)) {
    val id: dynamic = if(isObject(requestMsg)) requestMsg.id else null
    replyError(id, "Invalid request, missing 'data'.")
    return
  }
  if(!isObject(requestMsg.data.cmd)) {
    replyError(requestMsg.id, "Invalid request, missing 'cmd'.")
    return
  }
  if(multiTabMode == "Shared") {
    if(isLeader) {
      var opaque: Int? = null
      when(requestMsg.data.cmd.unsafeCast<String>()) {
        "open" -> {
          opaque = nextDatabaseId++
          databases[opaque] = newDbEntry(requestMsg.data.fileName, null)
        }
        "prepare" -> {
          opaque = nextStatementId++
          statements[opaque] = newStmtEntry(requestMsg.data.databaseId, requestMsg.data.sql, null)
        }
      }
      processOwnDriverAsLeader(requestMsg.id, requestMsg.data, opaque)
      return
    }
    forwardToLeader(requestMsg.id, requestMsg.data)
    return
  }
  if(isPaused) {
    pausedQueue.add(e)
    return
  }
  dispatchLocal(requestMsg.id, requestMsg.data)
}

private fun onMessage(e: MessageEventLike) {
  val data: dynamic = e.data
  if(!initStarted && isObject(data) && isObject(data.__opfsInit)) {
    initStarted = true
    initData = data.__opfsInit
    multiTabMode = initData.mode.unsafeCast<String?>() ?: "Single"
    when(multiTabMode) {
      "Shared" -> {
        setupSharedMode()
        return
      }
      "PauseOnHidden" -> {
        // Don't claim SAH handles until the main thread tells us we have the foreground lock.
        isPaused = true
        return
      }
      else -> {
        ensureLocalSqlite(
          onDone = { drainQueuedDriverMessages() },
          onError = { err ->
            consoleErrorWith("sqldelight-androidx-opfs-worker: failed to initialize sqlite3", err)
          },
        )
        return
      }
    }
  }
  if(isObject(data) && isObject(data.__opfsControlPort)) {
    controlPort = data.__opfsControlPort.unsafeCast<MessagePortLike>()
    return
  }
  if(isObject(data) && isObject(data.__opfsPause)) {
    if(multiTabMode == "PauseOnHidden" && !isPaused) {
      isPaused = true
      if(poolUtil != null) {
        suspendLocalInstances()
        try {
          poolPauseVfs(poolUtil)
        }
        catch(err: Throwable) {
          consoleErrorWith("sqldelight-androidx-opfs-worker: pauseVfs failed", err)
        }
      }
    }
    controlPort?.let(::controlPortAck)
    return
  }
  if(isObject(data) && isObject(data.__opfsResume)) {
    if(multiTabMode == "PauseOnHidden" && isPaused) {
      val drain: () -> Unit = {
        isPaused = false
        while(pausedQueue.isNotEmpty()) {
          routeDriverMessage(pausedQueue.removeAt(0))
        }
      }
      if(poolUtil == null) {
        ensureLocalSqlite(
          onDone = drain,
          onError = { err ->
            consoleErrorWith("sqldelight-androidx-opfs-worker: failed to initialize sqlite3", err)
          },
        )
      }
      else {
        thenAccept(
          unpauseVfs(poolUtil),
          { drain() },
          { err ->
            consoleErrorWith("sqldelight-androidx-opfs-worker: unpauseVfs failed", err)
          },
        )
      }
    }
    return
  }
  if(multiTabMode == "Shared") {
    if(acceptingDriverMessages) routeDriverMessage(e) else queuedDriverMessages.add(e)
    return
  }
  if(multiTabMode == "PauseOnHidden") {
    if(isPaused) pausedQueue.add(e) else routeDriverMessage(e)
    return
  }
  if(sqlite3 == null) {
    queuedDriverMessages.add(e)
  }
  else {
    routeDriverMessage(e)
  }
}
