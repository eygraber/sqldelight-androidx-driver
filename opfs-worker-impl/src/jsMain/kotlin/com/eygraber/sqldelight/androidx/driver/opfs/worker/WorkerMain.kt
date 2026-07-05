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
  if(pauseState != PauseState.Live) {
    pausedQueue.add(e)
    return
  }
  dispatchLocal(requestMsg.id, requestMsg.data)
}

// The resume chain finished claiming handles. If a pause arrived mid-chain, release them
// again and only now ack — the orchestrator holds the Web Lock until the ack arrives.
private fun onResumeSettled() {
  if(pendingPause) {
    pendingPause = false
    pauseState = PauseState.Paused
    suspendLocalInstances()
    try {
      poolPauseVfs(poolUtil)
    }
    catch(err: Throwable) {
      consoleErrorWith("sqldelight-androidx-opfs-worker: pauseVfs failed", err)
    }
    controlPort?.let(::controlPortAck)
    return
  }
  pauseState = PauseState.Live
  while(pausedQueue.isNotEmpty()) {
    routeDriverMessage(pausedQueue.removeAt(0))
  }
}

// The resume chain failed, so no handles are held. Stay paused; if a pause arrived mid-chain,
// ack it now so the orchestrator can release the Web Lock.
private fun onResumeFailed() {
  pauseState = PauseState.Paused
  if(pendingPause) {
    pendingPause = false
    controlPort?.let(::controlPortAck)
  }
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
        pauseState = PauseState.Paused
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
    if(multiTabMode == "PauseOnHidden" && pauseState == PauseState.Resuming) {
      // The resume chain is mid-flight and will claim SAH handles when it settles. Defer the
      // pause work AND the ack until then — the orchestrator releases the Web Lock on ack, and
      // releasing it before our handles are actually released would let another tab claim the
      // pool while we're claiming it too.
      pendingPause = true
      return
    }
    if(multiTabMode == "PauseOnHidden" && pauseState == PauseState.Live) {
      pauseState = PauseState.Paused
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
    if(multiTabMode == "PauseOnHidden" && pauseState == PauseState.Paused) {
      pauseState = PauseState.Resuming
      if(poolUtil == null) {
        ensureLocalSqlite(
          onDone = ::onResumeSettled,
          onError = { err ->
            consoleErrorWith("sqldelight-androidx-opfs-worker: failed to initialize sqlite3", err)
            onResumeFailed()
          },
        )
      }
      else {
        thenAccept(
          unpauseVfs(poolUtil),
          { onResumeSettled() },
          { err ->
            consoleErrorWith("sqldelight-androidx-opfs-worker: unpauseVfs failed", err)
            onResumeFailed()
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
    if(pauseState != PauseState.Live) pausedQueue.add(e) else routeDriverMessage(e)
    return
  }
  if(sqlite3 == null) {
    queuedDriverMessages.add(e)
  }
  else {
    routeDriverMessage(e)
  }
}
