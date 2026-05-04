package com.eygraber.sqldelight.androidx.driver.opfs.worker

internal fun forwardToLeader(id: dynamic, requestData: dynamic) {
  var opaque: Int? = null
  when(requestData.cmd.unsafeCast<String>()) {
    "open" -> {
      opaque = nextDatabaseId++
      databases[opaque] = newDbEntry(requestData.fileName, null)
    }
    "prepare" -> {
      opaque = nextStatementId++
      statements[opaque] = newStmtEntry(requestData.databaseId, requestData.sql, null)
    }
  }
  val reqId = nextForwardReqId++
  pendingLeaderResponses[reqId] = newPendingForward(id, requestData, opaque)
  bc?.postMessage(bcRequest(tabId, reqId, enrichForLeader(requestData, opaque)))
}

private fun enrichStepRequest(requestData: dynamic): dynamic {
  @Suppress("UseLet")
  val stmtInfo = statements[requestData.statementId.unsafeCast<Int>()]
  @Suppress("UseLet")
  val dbInfo: dynamic =
    if(stmtInfo == null) null else databases[stmtInfo.databaseId.unsafeCast<Int>()]
  return enrichStep(
    opaqueStatementId = requestData.statementId,
    opaqueDatabaseId = stmtInfo?.databaseId,
    fileName = dbInfo?.fileName,
    sql = stmtInfo?.sql,
    bindings = requestData.bindings,
  )
}

internal fun enrichForLeader(requestData: dynamic, opaque: Int?): dynamic =
  when(requestData.cmd.unsafeCast<String>()) {
    "open" -> enrichOpen(opaque, requestData.fileName)
    "prepare" -> {
      val dbInfo = databases[requestData.databaseId.unsafeCast<Int>()]
      val fileName: dynamic = dbInfo?.fileName
      enrichPrepare(opaque, requestData.databaseId, fileName, requestData.sql)
    }
    "step" -> enrichStepRequest(requestData)
    "close" -> {
      val stmtInfo: dynamic =
        if(isObject(requestData.statementId)) statements[requestData.statementId.unsafeCast<Int>()] else null
      val dbInfo: dynamic =
        if(isObject(requestData.databaseId)) databases[requestData.databaseId.unsafeCast<Int>()] else null
      val fileName: dynamic = dbInfo?.fileName
      val sql: dynamic = stmtInfo?.sql
      val stmtDbId: dynamic = stmtInfo?.databaseId
      enrichClose(requestData.statementId, requestData.databaseId, fileName, sql, stmtDbId)
    }
    else -> requestData
  }

internal fun handleLeaderResponse(reqId: Int, response: dynamic) {
  val pending = pendingLeaderResponses[reqId] ?: return
  pendingLeaderResponses.remove(reqId)
  val cmd = pending.requestData.cmd.unsafeCast<String>()
  if(isObject(response.error)) {
    if(cmd == "open" && pending.opaque != null) databases.remove(pending.opaque.unsafeCast<Int>())
    if(cmd == "prepare" && pending.opaque != null) statements.remove(pending.opaque.unsafeCast<Int>())
    replyError(pending.driverId, response.error.unsafeCast<String>())
    return
  }
  when(cmd) {
    "open" -> replyOk(pending.driverId, newOpenReplyData(pending.opaque.unsafeCast<Int>()))
    "prepare" -> replyOk(
      pending.driverId,
      newPrepareReplyDataDyn(pending.opaque, response.data.parameterCount, response.data.columnNames),
    )
    "close" -> {
      // Fire-and-forget on success — clean up local state and stay silent.
      if(isObject(pending.requestData.statementId)) {
        statements.remove(pending.requestData.statementId.unsafeCast<Int>())
      }
      if(isObject(pending.requestData.databaseId)) {
        databases.remove(pending.requestData.databaseId.unsafeCast<Int>())
      }
    }
    else -> replyOk(pending.driverId, response.data)
  }
}
