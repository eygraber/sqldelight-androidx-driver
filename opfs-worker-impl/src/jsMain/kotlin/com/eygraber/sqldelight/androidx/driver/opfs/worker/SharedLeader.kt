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

private fun tabLockName(id: String): String = "sqldelight-androidx-opfs-tab-$id"

private fun requestDatabaseId(payload: dynamic): dynamic =
  if(isObject(payload.opaqueDatabaseId)) payload.opaqueDatabaseId else payload.statementDatabaseId

private fun requestFileName(state: dynamic, payload: dynamic): String? {
  if(isObject(payload.fileName)) return payload.fileName.unsafeCast<String>()
  val entry = jsMapGet(state.databases, requestDatabaseId(payload))
  return if(isObject(entry) && isObject(entry.fileName)) entry.fileName.unsafeCast<String>() else null
}

private fun SharedTransactionOwner.matches(followerId: String, opaqueDatabaseId: dynamic): Boolean =
  this.followerId == followerId && this.opaqueDatabaseId == opaqueDatabaseId

private fun releaseTransactionOwner(fileName: String) {
  val owner = sharedTransactionOwners.remove(fileName) ?: return
  if(owner.lockWatch != null) abortLockWatch(owner.lockWatch)
}

private fun rollbackAndRelease(fileName: String) {
  val db = sharedLeaderConnections[fileName]
  if(db != null && dbIsInTransaction(sqlite3, db)) {
    try {
      dbExec(db, "ROLLBACK")
    }
    catch(error: Throwable) {
      consoleErrorWith("sqldelight-androidx-opfs-worker: rollback after the transaction owner was lost failed", error)
    }
  }
  releaseTransactionOwner(fileName)
}

private fun onTransactionOwnerLost(fileName: String, owner: SharedTransactionOwner) {
  if(sharedTransactionOwners[fileName] !== owner) return
  rollbackAndRelease(fileName)
  drainSharedRequests(fileName)
}

private fun syncTransactionOwner(followerId: String, opaqueDatabaseId: dynamic, fileName: String) {
  val db = sharedLeaderConnections[fileName] ?: return
  val owner = sharedTransactionOwners[fileName]
  val inTransaction = dbIsInTransaction(sqlite3, db)
  if(inTransaction && owner == null) {
    val newOwner = SharedTransactionOwner(followerId, opaqueDatabaseId)
    if(followerId != tabId) {
      newOwner.lockWatch = watchLockRelease(tabLockName(followerId)) {
        onTransactionOwnerLost(fileName, newOwner)
      }
    }
    sharedTransactionOwners[fileName] = newOwner
  }
  else if(!inTransaction && owner != null) {
    releaseTransactionOwner(fileName)
  }
}

private fun executeAndReply(
  followerId: String,
  payload: dynamic,
  fileName: String?,
  reply: (dynamic) -> Unit,
) {
  if(fileName != null) {
    val isDatabaseClose = payload.cmd.unsafeCast<String>() == "close" && isObject(payload.opaqueDatabaseId)
    val owner = sharedTransactionOwners[fileName]
    if(isDatabaseClose && owner != null && owner.matches(followerId, payload.opaqueDatabaseId)) {
      rollbackAndRelease(fileName)
    }
  }
  val result = executeLeaderRequest(followerId, payload)
  if(fileName != null) syncTransactionOwner(followerId, requestDatabaseId(payload), fileName)
  reply(result)
}

private fun drainSharedRequests(fileName: String) {
  val queue = sharedRequestQueues[fileName] ?: return
  while(queue.isNotEmpty() && sharedTransactionOwners[fileName] == null) {
    val next = queue.removeAt(0)
    executeAndReply(next.followerId, next.payload, fileName, next.reply)
  }
  if(queue.isEmpty()) sharedRequestQueues.remove(fileName)
}

internal fun leaderProcess(followerId: String, payload: dynamic, reply: (dynamic) -> Unit) {
  val fileName = requestFileName(getFollowerState(followerId), payload)
  if(fileName != null) {
    val owner = sharedTransactionOwners[fileName]
    if(owner != null && !owner.matches(followerId, requestDatabaseId(payload))) {
      sharedRequestQueues.getOrPut(fileName) { mutableListOf() }
        .add(QueuedLeaderRequest(followerId, payload, reply))
      return
    }
  }
  executeAndReply(followerId, payload, fileName, reply)
  if(fileName != null) drainSharedRequests(fileName)
}

private fun executeLeaderRequest(followerId: String, payload: dynamic): dynamic {
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
  knownLeaderId = tabId
  acceptingDriverMessages = true
  bc?.postMessage(bcLeaderChanged(tabId))
  retryPendingRequests()
  drainQueuedDriverMessages()
}

private fun setupFollower() {
  bc?.postMessage(bcWhoIsLeader(tabId))
}

private fun attemptLeaderLock() {
  leaderLock = requestLock(
    name = "sqldelight-androidx-opfs-leader",
    onAcquired = {
      if(!closeRequested) {
        ensureLocalSqlite(
          onDone = ::setupLeader,
          onError = { err ->
            consoleErrorWith("sqldelight-androidx-opfs-worker: failed to initialize sqlite3", err)
          },
        )
      }
    },
    onFailure = { err ->
      if(!closeRequested) {
        consoleErrorWith("sqldelight-androidx-opfs-worker: leader lock failed", err)
        setTimeout(250) { attemptLeaderLock() }
      }
    },
  )
}

internal fun setupSharedMode() {
  bc = newBroadcastChannel("sqldelight-androidx-opfs").also { channel ->
    channel.addEventListener("message") { e -> handleSharedMessage(channel, e) }
  }
  tabLock = requestLock(tabLockName(tabId), onAcquired = {}, onFailure = {})
  attemptLeaderLock()
  setupFollower()
}

internal fun closeSharedMode() {
  for(fileName in sharedTransactionOwners.keys.toList()) {
    releaseTransactionOwner(fileName)
  }
  sharedRequestQueues.clear()
  followerStates.clear()
  for(db in sharedLeaderConnections.values) {
    try {
      dbClose(db)
    }
    catch(error: Throwable) {
      consoleErrorWith("sqldelight-androidx-opfs-worker: close failed", error)
    }
  }
  sharedLeaderConnections.clear()
  for(pending in pendingLeaderResponses.values) {
    replyError(pending.driverId, CLOSED_MESSAGE)
  }
  pendingLeaderResponses.clear()
  isLeader = false
}

internal fun releaseSharedLocks() {
  leaderLock?.abort()
  leaderLock?.release()
  leaderLock = null
  tabLock?.abort()
  tabLock?.release()
  tabLock = null
  bc?.close()
  bc = null
}

private fun handleSharedMessage(channel: BroadcastChannelLike, e: MessageEventLike) {
  val m: dynamic = e.data
  if(!isObject(m)) return
  val kind = m.kind.unsafeCast<String?>()
  when {
    kind == "request" && isLeader -> {
      val followerId = m.followerId.unsafeCast<String>()
      val reqId: dynamic = m.reqId
      leaderProcess(followerId, m.payload) { response ->
        channel.postMessage(bcResponse(followerId, reqId, response))
      }
    }
    kind == "response" && m.followerId == tabId ->
      handleLeaderResponse(m.reqId.unsafeCast<Int>(), m.response)
    kind == "leader-changed" ->
      if(m.leaderId != tabId) {
        val leaderId = m.leaderId.unsafeCast<String>()
        val leaderChanged = knownLeaderId != leaderId
        knownLeaderId = leaderId
        isLeader = false
        if(leaderChanged) retryPendingRequests()
        if(!acceptingDriverMessages) {
          acceptingDriverMessages = true
          drainQueuedDriverMessages()
        }
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
  leaderProcess(tabId, enriched) { r ->
    replyToOwnDriver(driverId, requestData, cmd, preAllocatedOpaque, r)
  }
}

private fun replyToOwnDriver(
  driverId: dynamic,
  requestData: dynamic,
  cmd: String,
  preAllocatedOpaque: Int?,
  r: dynamic,
) {
  if(isObject(r.error)) {
    if(cmd == "open" && preAllocatedOpaque != null) databases.remove(preAllocatedOpaque)
    if(cmd == "prepare" && preAllocatedOpaque != null) statements.remove(preAllocatedOpaque)
    replyError(driverId, r.error.unsafeCast<String>())
    return
  }
  when(cmd) {
    "close" -> {
      if(isObject(requestData.statementId)) statements.remove(requestData.statementId.unsafeCast<Int>())
      if(isObject(requestData.databaseId)) databases.remove(requestData.databaseId.unsafeCast<Int>())
    }
    "open" -> replyOk(driverId, newOpenReplyData(preAllocatedOpaque ?: 0))
    "prepare" -> replyOk(
      driverId,
      newPrepareReplyDataDyn(preAllocatedOpaque, r.data.parameterCount, r.data.columnNames),
    )
    else -> replyOk(driverId, r.data)
  }
}

private fun emptyJsObject(): dynamic = js("({})")
