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

internal fun failQueuedDriverMessages(err: dynamic) {
  failQueuedDriverMessagesWith(errorMessage(err))
}

private fun failQueuedDriverMessagesWith(message: String) {
  failMessagesWith(queuedDriverMessages, message)
  failMessagesWith(pausedQueue, message)
}

private fun failMessagesWith(queue: MutableList<MessageEventLike>, message: String) {
  while(queue.isNotEmpty()) {
    val requestMsg: dynamic = queue.removeAt(0).data
    val id: dynamic = if(isObject(requestMsg)) requestMsg.id else null
    replyError(id, message)
  }
}

internal const val CLOSED_MESSAGE = "The OPFS worker is closed."

private fun requestClose() {
  if(closeRequested) return
  closeRequested = true
  when {
    multiTabMode == "PauseOnHidden" && pauseState == PauseState.Resuming -> pendingClose = true
    multiTabMode == "PauseOnHidden" -> finishClose()
    else -> whenLocalSqliteInitSettled(::finishClose)
  }
}

private fun finishClose() {
  pendingClose = false
  pendingPause = false
  pauseState = PauseState.Paused
  failQueuedDriverMessagesWith(CLOSED_MESSAGE)
  if(multiTabMode == "Shared") closeSharedMode()
  suspendLocalInstances()
  if(poolUtil != null) {
    try {
      poolPauseVfs(poolUtil)
    }
    catch(err: Throwable) {
      consoleErrorWith("sqldelight-androidx-opfs-worker: pauseVfs failed", err)
    }
  }
  if(multiTabMode == "Shared") releaseSharedLocks()
  controlPorts.forEach(::controlPortClosedAck)
}

private fun rejectAfterClose(data: dynamic) {
  if(isObject(data) && isObject(data.id)) replyError(data.id, CLOSED_MESSAGE)
}

private fun errorMessage(err: dynamic): String {
  val message: dynamic = if(isObject(err)) err.message else null
  return if(isObject(message)) message.unsafeCast<String>() else "$err"
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
  if(pendingPause && pauseState == PauseState.Live && !anyLocalDbInTransaction()) {
    completePause()
  }
}

private fun anyLocalDbInTransaction(): Boolean =
  databases.values.any { db -> db.instance != null && dbIsInTransaction(sqlite3, db.instance) }

private fun completePause() {
  pendingPause = false
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
  controlPorts.forEach(::controlPortAck)
}

// The resume chain finished claiming handles. If a pause arrived mid-chain, release them
// again and only now ack — the orchestrator holds the Web Lock until the ack arrives.
private fun onResumeSettled() {
  if(pendingClose) {
    finishClose()
    return
  }
  if(pendingPause) {
    completePause()
    return
  }
  pauseState = PauseState.Live
  controlPorts.forEach(::controlPortResumedAck)
  while(pausedQueue.isNotEmpty()) {
    routeDriverMessage(pausedQueue.removeAt(0))
  }
}

// The resume chain failed, so no handles are held. Stay paused; if a pause arrived mid-chain,
// ack it now so the orchestrator can release the Web Lock. Otherwise report the failure.
private fun onResumeFailed(err: dynamic) {
  pauseState = PauseState.Paused
  if(pendingClose) {
    finishClose()
    return
  }
  if(pendingPause) {
    pendingPause = false
    controlPorts.forEach(::controlPortAck)
    return
  }
  controlPorts.forEach { controlPortResumeFailed(it, errorMessage(err)) }
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
          onDone = ::drainQueuedDriverMessages,
          onError = ::onLocalInitFailed,
        )
        return
      }
    }
  }
  if(isObject(data) && isObject(data.__opfsControlPort)) {
    controlPorts.add(data.__opfsControlPort.unsafeCast<MessagePortLike>())
    return
  }
  if(isObject(data) && isObject(data.__opfsDebugFollowerCount)) {
    controlPorts.forEach { controlPortFollowerCount(it, followerStates.size) }
    return
  }
  if(isObject(data) && isObject(data.__opfsClose)) {
    requestClose()
    return
  }
  if(closeRequested) {
    rejectAfterClose(data)
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
      if(anyLocalDbInTransaction()) {
        pendingPause = true
        return
      }
      completePause()
      return
    }
    controlPorts.forEach(::controlPortAck)
    return
  }
  if(isObject(data) && isObject(data.__opfsResume)) {
    if(multiTabMode == "PauseOnHidden" && pauseState == PauseState.Live) {
      pendingPause = false
      return
    }
    if(multiTabMode == "PauseOnHidden" && pauseState == PauseState.Paused) {
      pauseState = PauseState.Resuming
      if(poolUtil == null) {
        ensureLocalSqlite(
          onDone = ::onResumeSettled,
          onError = { err ->
            consoleErrorWith("sqldelight-androidx-opfs-worker: failed to initialize sqlite3", err)
            onResumeFailed(err)
          },
        )
      }
      else {
        thenAccept(
          unpauseVfs(poolUtil),
          { onResumeSettled() },
          { err ->
            consoleErrorWith("sqldelight-androidx-opfs-worker: unpauseVfs failed", err)
            onResumeFailed(err)
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
  if(poolUtil == null) {
    queuedDriverMessages.add(e)
    ensureLocalSqlite(
      onDone = ::drainQueuedDriverMessages,
      onError = ::onLocalInitFailed,
    )
  }
  else {
    routeDriverMessage(e)
  }
}

private fun onLocalInitFailed(err: dynamic) {
  consoleErrorWith("sqldelight-androidx-opfs-worker: failed to initialize sqlite3", err)
  failQueuedDriverMessages(err)
}
