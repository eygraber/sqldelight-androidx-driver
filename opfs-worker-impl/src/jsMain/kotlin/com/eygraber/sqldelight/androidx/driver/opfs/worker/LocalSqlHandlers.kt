package com.eygraber.sqldelight.androidx.driver.opfs.worker

private fun ensureLocalDbInstance(dbEntry: dynamic): dynamic {
  if(dbEntry.instance == null) {
    dbEntry.instance = openPoolDbWithRetry(dbEntry.fileName.unsafeCast<String>())
  }
  return dbEntry.instance
}

private fun ensureLocalStmtInstance(stmtEntry: dynamic): dynamic {
  if(stmtEntry.instance == null) {
    val dbEntry = databases[stmtEntry.databaseId.unsafeCast<Int>()]
      ?: throw IllegalStateException(
        "statement references unknown database id ${stmtEntry.databaseId}",
      )
    val dbInstance = ensureLocalDbInstance(dbEntry)
    stmtEntry.instance = stmtPrepare(dbInstance, stmtEntry.sql)
  }
  return stmtEntry.instance
}

private fun localOpen(id: dynamic, requestData: dynamic) {
  try {
    val newDatabaseId = nextDatabaseId++
    val instance = openPoolDbWithRetry(requestData.fileName.unsafeCast<String>())
    databases[newDatabaseId] = newDbEntry(requestData.fileName, instance)
    replyOk(id, newOpenReplyData(newDatabaseId))
  }
  catch(error: Throwable) {
    replyError(id, error.message ?: error.toString())
  }
}

private fun localPrepare(id: dynamic, requestData: dynamic) {
  try {
    val dbId = requestData.databaseId.unsafeCast<Int>()
    val db = databases[dbId]
    if(db == null) {
      replyError(id, "Invalid database ID: $dbId")
      return
    }
    val dbInstance = ensureLocalDbInstance(db)
    val newStatementId = nextStatementId++
    val stmt = stmtPrepare(dbInstance, requestData.sql)
    statements[newStatementId] = newStmtEntry(requestData.databaseId, requestData.sql, stmt)
    val parameterCount = bindParameterCount(sqlite3, stmt)
    val columnNames = jsArray()
    val cc = stmtColumnCount(stmt)
    for(i in 0 until cc) {
      jsArrayPush(columnNames, columnName(sqlite3, stmt, i))
    }
    replyOk(id, newPrepareReplyData(newStatementId, parameterCount, columnNames))
  }
  catch(error: Throwable) {
    replyError(id, error.message ?: error.toString())
  }
}

private fun localStep(id: dynamic, requestData: dynamic) {
  val stmtId = requestData.statementId.unsafeCast<Int>()
  val stmtEntry = statements[stmtId]
  if(stmtEntry == null) {
    replyError(id, "Invalid statement ID: $stmtId")
    return
  }
  try {
    val stmt = ensureLocalStmtInstance(stmtEntry)
    val rows = jsArray()
    val columnTypes = jsArray()
    stmtReset(stmt)
    stmtClearBindings(stmt)
    val bindings = requestData.bindings
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
    replyOk(id, newStepReplyData(rows, columnTypes))
  }
  catch(error: Throwable) {
    replyError(id, error.message ?: error.toString())
  }
}

// 'close' is fire-and-forget on the driver side: only post a reply on error.
private fun localClose(id: dynamic, requestData: dynamic) {
  if(isObject(requestData.statementId)) {
    val stmtId = requestData.statementId.unsafeCast<Int>()
    val stmt = statements[stmtId]
    if(stmt == null) {
      replyError(id, "Invalid statement ID: $stmtId")
      return
    }
    try {
      if(stmt.instance != null) stmtFinalize(stmt.instance)
      statements.remove(stmtId)
    }
    catch(error: Throwable) {
      replyError(id, error.message ?: error.toString())
      return
    }
  }
  if(isObject(requestData.databaseId)) {
    val dbId = requestData.databaseId.unsafeCast<Int>()
    val db = databases[dbId]
    if(db == null) {
      replyError(id, "Invalid database ID: $dbId")
      return
    }
    try {
      if(db.instance != null) dbClose(db.instance)
      databases.remove(dbId)
    }
    catch(error: Throwable) {
      replyError(id, error.message ?: error.toString())
      return
    }
  }
}

internal fun suspendLocalInstances() {
  for(stmt in statements.values) {
    if(stmt.instance != null) {
      try {
        stmtFinalize(stmt.instance)
      }
      catch(_: Throwable) {
        // ignore
      }
      stmt.instance = null
    }
  }
  for(db in databases.values) {
    if(db.instance != null) {
      try {
        dbClose(db.instance)
      }
      catch(_: Throwable) {
        // ignore
      }
      db.instance = null
    }
  }
}

internal fun dispatchLocal(id: dynamic, requestData: dynamic) {
  when(requestData.cmd.unsafeCast<String>()) {
    "open" -> localOpen(id, requestData)
    "prepare" -> localPrepare(id, requestData)
    "step" -> localStep(id, requestData)
    "close" -> localClose(id, requestData)
    else -> replyError(id, "Invalid request, unknown command: '${requestData.cmd}'.")
  }
}
