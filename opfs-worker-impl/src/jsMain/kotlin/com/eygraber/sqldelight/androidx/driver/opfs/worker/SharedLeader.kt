package com.eygraber.sqldelight.androidx.driver.opfs.worker

private fun getFollowerState(followerId: String): dynamic {
  var s = followerStates[followerId]
  if(s == null) {
    s = newFollowerState()
    followerStates[followerId] = s
  }
  return s
}

private fun ensureLeaderDb(state: dynamic, opaqueDatabaseId: dynamic, fileName: dynamic): dynamic {
  var entry = jsMapGet(state.databases, opaqueDatabaseId)
  if(!isObject(entry) || entry.instance == null) {
    if(!isObject(fileName)) {
      throw IllegalStateException(
        "cannot recreate database $opaqueDatabaseId without a fileName",
      )
    }
    val fileNameStr = fileName.unsafeCast<String>()
    var shared = sharedLeaderConnections[fileNameStr]
    if(shared == null) {
      shared = openPoolDbWithRetry(fileNameStr)
      sharedLeaderConnections[fileNameStr] = shared
    }
    entry = newDbEntry(fileName, shared)
    jsMapSet(state.databases, opaqueDatabaseId, entry)
  }
  return entry
}

private fun ensureLeaderStmt(
  state: dynamic,
  opaqueStatementId: dynamic,
  opaqueDatabaseId: dynamic,
  fileName: dynamic,
  sql: dynamic,
): dynamic {
  var entry = jsMapGet(state.statements, opaqueStatementId)
  if(!isObject(entry) || entry.instance == null) {
    if(!isObject(sql)) {
      throw IllegalStateException(
        "cannot recreate statement $opaqueStatementId without sql",
      )
    }
    val dbEntry = ensureLeaderDb(state, opaqueDatabaseId, fileName)
    val instance = stmtPrepare(dbEntry.instance, sql)
    entry = newStmtEntry(opaqueDatabaseId, sql, instance)
    jsMapSet(state.statements, opaqueStatementId, entry)
  }
  return entry
}

internal fun leaderProcess(followerId: String, payload: dynamic): dynamic {
  val state = getFollowerState(followerId)
  return try {
    when(payload.cmd.unsafeCast<String>()) {
      "open" -> {
        ensureLeaderDb(state, payload.opaqueDatabaseId, payload.fileName)
        newLeaderResultData(newOpenReplyData(payload.opaqueDatabaseId.unsafeCast<Int>()))
      }
      "prepare" -> {
        val stmtEntry = ensureLeaderStmt(
          state,
          payload.opaqueStatementId,
          payload.opaqueDatabaseId,
          payload.fileName,
          payload.sql,
        )
        val stmt = stmtEntry.instance
        val parameterCount = bindParameterCount(sqlite3, stmt)
        val columnNames = jsArray()
        val cc = stmtColumnCount(stmt)
        for(i in 0 until cc) {
          jsArrayPush(columnNames, columnName(sqlite3, stmt, i))
        }
        newLeaderResultData(
          newPrepareReplyDataDyn(payload.opaqueStatementId, parameterCount, columnNames),
        )
      }
      "step" -> {
        val stmtEntry = ensureLeaderStmt(
          state,
          payload.opaqueStatementId,
          payload.opaqueDatabaseId,
          payload.fileName,
          payload.sql,
        )
        val stmt = stmtEntry.instance
        val rows = jsArray()
        val columnTypes = jsArray()
        stmtReset(stmt)
        stmtClearBindings(stmt)
        val bindings = payload.bindings
        val bindingsLength = jsArrayLength(bindings)
        for(i in 0 until bindingsLength) {
          stmtBind(stmt, i + 1, jsArrayGet(bindings, i))
        }
        while(stmtStep(stmt)) {
          if(jsArrayLength(columnTypes) == 0) {
            val cc = stmtColumnCount(stmt)
            for(i in 0 until cc) {
              jsArrayPush(columnTypes, columnType(sqlite3, stmt, i))
            }
          }
          jsArrayPush(rows, stmtGetRow(stmt))
        }
        newLeaderResultData(newStepReplyData(rows, columnTypes))
      }
      "close" -> {
        if(isObject(payload.opaqueStatementId)) {
          val entry = jsMapGet(state.statements, payload.opaqueStatementId)
          if(isObject(entry) && entry.instance != null) stmtFinalize(entry.instance)
          jsMapDelete(state.statements, payload.opaqueStatementId)
        }
        if(isObject(payload.opaqueDatabaseId)) {
          // Don't close the underlying shared OpfsSAHPoolDb here — other followers may still be
          // referencing it via sharedLeaderConnections.
          jsMapDelete(state.databases, payload.opaqueDatabaseId)
        }
        newLeaderResultData(emptyJsObject())
      }
      else -> newLeaderResultError("Invalid request, unknown command: '${payload.cmd}'.")
    }
  }
  catch(error: Throwable) {
    newLeaderResultError(error.message ?: error.toString())
  }
}

private fun setupLeader() {
  isLeader = true
  acceptingDriverMessages = true
  bc?.postMessage(bcLeaderChanged(tabId))
  retryPendingRequests()
  drainQueuedDriverMessages()
}

private fun setupFollower() {
  bc?.postMessage(bcWhoIsLeader(tabId))
}

private fun attemptLeaderLock() {
  requestLeaderLock(
    name = "sqldelight-androidx-opfs-leader",
    onAcquired = {
      ensureLocalSqlite(
        onDone = ::setupLeader,
        onError = { err ->
          consoleErrorWith("sqldelight-androidx-opfs-worker: failed to initialize sqlite3", err)
        },
      )
    },
    onFailure = { err ->
      consoleErrorWith("sqldelight-androidx-opfs-worker: leader lock failed", err)
      setTimeout(250) { attemptLeaderLock() }
    },
  )
}

internal fun setupSharedMode() {
  bc = newBroadcastChannel("sqldelight-androidx-opfs").also { channel ->
    channel.addEventListener("message") { e -> handleSharedMessage(channel, e) }
  }
  attemptLeaderLock()
  setupFollower()
}

private fun handleSharedMessage(channel: BroadcastChannelLike, e: MessageEventLike) {
  val m: dynamic = e.data
  if(!isObject(m)) return
  val kind = m.kind.unsafeCast<String?>()
  when {
    kind == "request" && isLeader -> {
      val response = leaderProcess(m.followerId.unsafeCast<String>(), m.payload)
      channel.postMessage(bcResponse(m.followerId, m.reqId, response))
    }
    kind == "response" && m.followerId == tabId ->
      handleLeaderResponse(m.reqId.unsafeCast<Int>(), m.response)
    kind == "leader-changed" ->
      if(m.leaderId != tabId) {
        isLeader = false
        if(!acceptingDriverMessages) {
          acceptingDriverMessages = true
          drainQueuedDriverMessages()
        }
        retryPendingRequests()
      }
    kind == "who-is-leader" && isLeader ->
      channel.postMessage(bcLeaderChanged(tabId))
    kind == "retry-pending" ->
      if(!isLeader) retryPendingRequests()
  }
}

internal fun retryPendingRequests() {
  val old = pendingLeaderResponses
  pendingLeaderResponses = mutableMapOf()
  for(pending in old.values) {
    if(isLeader) {
      processOwnDriverAsLeader(
        pending.driverId,
        pending.requestData,
        pending.opaque.unsafeCast<Int?>(),
      )
    }
    else {
      val reqId = nextForwardReqId++
      pendingLeaderResponses[reqId] = pending
      bc?.postMessage(
        bcRequest(tabId, reqId, enrichForLeader(pending.requestData, pending.opaque.unsafeCast<Int?>())),
      )
    }
  }
}

internal fun processOwnDriverAsLeader(
  driverId: dynamic,
  requestData: dynamic,
  preAllocatedOpaque: Int?,
) {
  val enriched = enrichForLeader(requestData, preAllocatedOpaque)
  val cmd = requestData.cmd.unsafeCast<String>()
  if(cmd == "open" && preAllocatedOpaque != null) {
    enriched.opaqueDatabaseId = preAllocatedOpaque
  }
  else if(cmd == "prepare" && preAllocatedOpaque != null) {
    enriched.opaqueStatementId = preAllocatedOpaque
  }
  val r = leaderProcess(tabId, enriched)
  if(isObject(r.error)) {
    if(cmd == "open" && preAllocatedOpaque != null) databases.remove(preAllocatedOpaque)
    if(cmd == "prepare" && preAllocatedOpaque != null) statements.remove(preAllocatedOpaque)
    replyError(driverId, r.error.unsafeCast<String>())
    return
  }
  when(cmd) {
    "close" -> Unit // fire-and-forget on success
    "open" -> replyOk(driverId, newOpenReplyData(preAllocatedOpaque ?: 0))
    "prepare" -> replyOk(
      driverId,
      newPrepareReplyDataDyn(preAllocatedOpaque, r.data.parameterCount, r.data.columnNames),
    )
    else -> replyOk(driverId, r.data)
  }
}

private fun emptyJsObject(): dynamic = js("({})")
