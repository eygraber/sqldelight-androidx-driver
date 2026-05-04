package com.eygraber.sqldelight.androidx.driver.opfs.worker

import kotlin.js.Promise

external val self: DedicatedWorkerGlobalScopeLike

external interface DedicatedWorkerGlobalScopeLike {
  var onmessage: ((MessageEventLike) -> Unit)?
  fun postMessage(message: dynamic)
  fun postMessage(message: dynamic, transfer: Array<dynamic>)
}

external interface MessageEventLike {
  val data: dynamic
}

external interface MessagePortLike {
  var onmessage: ((MessageEventLike) -> Unit)?
  fun postMessage(message: dynamic)
}

external interface BroadcastChannelLike {
  fun postMessage(message: dynamic)
  fun addEventListener(type: String, listener: (MessageEventLike) -> Unit)
}

internal fun newBroadcastChannel(name: String): BroadcastChannelLike = js(
  """new BroadcastChannel(name)""",
)

// We need the BROWSER's native `import()` here, not webpack's. If webpack's static analyzer sees
// `import(url)` in our source it rewrites it into a registry lookup that throws "Cannot find
// module" because the URL is dynamic. The Kotlin compiler also strips JS magic comments from
// `js("...")` literals so `import(/* webpackIgnore: true */ url)` doesn't survive either. Hide
// the call behind a `Function` constructor so webpack can't see it at all — at runtime we get a
// real native dynamic import that resolves the already-fully-qualified URL passed via __opfsInit.
internal fun dynamicImport(url: String): Promise<dynamic> = js(
  """(new Function('u', 'return import(u)'))(url)""",
)

internal fun thenAccept(
  promise: Promise<*>,
  onFulfilled: (dynamic) -> Unit,
  onRejected: (dynamic) -> Unit,
) {
  js("promise.then(onFulfilled, onRejected)")
}

internal fun setTimeout(ms: Int, callback: () -> Unit) {
  js("setTimeout(callback, ms)")
}

internal fun newTabId(): String = js(
  """(self.crypto && self.crypto.randomUUID) ? self.crypto.randomUUID() : ('t-' + Math.random().toString(36).slice(2))""",
)

internal fun consoleError(message: String) {
  js("console.error(message)")
}

internal fun consoleErrorWith(message: String, error: dynamic) {
  js("console.error(message, error)")
}

internal fun requestLeaderLock(
  name: String,
  onAcquired: () -> Unit,
  onFailure: (dynamic) -> Unit,
) {
  js(
    """
      navigator.locks.request(name, { mode: 'exclusive' }, () => {
        onAcquired();
        return new Promise(() => {});
      }).catch((err) => { onFailure(err); })
    """,
  )
}

internal fun installSqliteOpfsSAHPoolVfs(sqlite3: dynamic): Promise<dynamic> = js(
  """sqlite3.installOpfsSAHPoolVfs({ clearOnInit: false })""",
)

internal fun newOpfsSAHPoolDb(poolUtil: dynamic, fileName: String): dynamic = js(
  """new poolUtil.OpfsSAHPoolDb(fileName)""",
)

internal fun installSqlite3(mod: dynamic, wasmUrl: String): Promise<dynamic> = js(
  """mod.default({ locateFile: (path) => path === 'sqlite3.wasm' ? wasmUrl : path })""",
)

internal fun bindParameterCount(sqlite3: dynamic, stmt: dynamic): Int = js(
  """sqlite3.capi.sqlite3_bind_parameter_count(stmt)""",
)

internal fun columnName(sqlite3: dynamic, stmt: dynamic, index: Int): String = js(
  """sqlite3.capi.sqlite3_column_name(stmt, index)""",
)

internal fun columnType(sqlite3: dynamic, stmt: dynamic, index: Int): Int = js(
  """sqlite3.capi.sqlite3_column_type(stmt, index)""",
)

internal fun replyOk(id: dynamic, data: dynamic) {
  js("self.postMessage({ id: id, data: data })")
}

internal fun replyError(id: dynamic, error: String) {
  js("self.postMessage({ id: id, error: error })")
}

internal fun controlPortAck(controlPort: MessagePortLike) {
  js("controlPort.postMessage({ __opfsPausedAck: true })")
}

internal fun isObject(value: dynamic): Boolean = js(
  """value !== null && value !== undefined""",
)

internal fun unpauseVfs(util: dynamic): Promise<dynamic> = js(
  """Promise.resolve(util.unpauseVfs())""",
)

internal fun currentTimeMs(): Double = js("Date.now()")

internal fun newJsMap(): dynamic = js("new Map()")

internal fun jsMapGet(map: dynamic, key: dynamic): dynamic = js("map.get(key)")

internal fun jsMapSet(map: dynamic, key: dynamic, value: dynamic) {
  js("map.set(key, value)")
}

internal fun jsMapDelete(map: dynamic, key: dynamic) {
  js("map.delete(key)")
}

internal fun jsArray(): dynamic = js("[]")

internal fun jsArrayPush(array: dynamic, value: dynamic) {
  js("array.push(value)")
}

internal fun jsArrayLength(array: dynamic): Int = js("array.length")

internal fun jsArrayGet(array: dynamic, index: Int): dynamic = js("array[index]")

// ---- Object construction helpers (mirrors the JS literals exactly so wire format stays stable). ----

internal fun newDbEntry(fileName: dynamic, instance: dynamic): dynamic = js(
  """({ fileName: fileName, instance: instance })""",
)

internal fun newStmtEntry(databaseId: dynamic, sql: dynamic, instance: dynamic): dynamic = js(
  """({ databaseId: databaseId, sql: sql, instance: instance })""",
)

internal fun newOpenReplyData(databaseId: Int): dynamic = js(
  """({ databaseId: databaseId })""",
)

internal fun newPrepareReplyData(statementId: Int, parameterCount: Int, columnNames: dynamic): dynamic = js(
  """({ statementId: statementId, parameterCount: parameterCount, columnNames: columnNames })""",
)

internal fun newPrepareReplyDataDyn(statementId: dynamic, parameterCount: dynamic, columnNames: dynamic): dynamic = js(
  """({ statementId: statementId, parameterCount: parameterCount, columnNames: columnNames })""",
)

internal fun newStepReplyData(rows: dynamic, columnTypes: dynamic): dynamic = js(
  """({ rows: rows, columnTypes: columnTypes })""",
)

internal fun newPendingForward(driverId: dynamic, requestData: dynamic, opaque: dynamic): dynamic = js(
  """({ driverId: driverId, requestData: requestData, opaque: opaque })""",
)

internal fun newFollowerState(): dynamic = js(
  """({ databases: new Map(), statements: new Map() })""",
)

// ---- BroadcastChannel envelopes. Each helper produces the exact JS literal the legacy worker
// posted so cross-version compatibility holds at the wire boundary. ----

internal fun bcRequest(followerId: String, reqId: Int, payload: dynamic): dynamic = js(
  """({ kind: 'request', followerId: followerId, reqId: reqId, payload: payload })""",
)

internal fun bcResponse(followerId: dynamic, reqId: dynamic, response: dynamic): dynamic = js(
  """({ kind: 'response', followerId: followerId, reqId: reqId, response: response })""",
)

internal fun bcLeaderChanged(leaderId: String): dynamic = js(
  """({ kind: 'leader-changed', leaderId: leaderId })""",
)

internal fun bcWhoIsLeader(followerId: String): dynamic = js(
  """({ kind: 'who-is-leader', followerId: followerId })""",
)

internal fun enrichOpen(opaqueDatabaseId: dynamic, fileName: dynamic): dynamic = js(
  """({ cmd: 'open', opaqueDatabaseId: opaqueDatabaseId, fileName: fileName })""",
)

internal fun enrichPrepare(
  opaqueStatementId: dynamic,
  opaqueDatabaseId: dynamic,
  fileName: dynamic,
  sql: dynamic,
): dynamic = js(
  """({
    cmd: 'prepare',
    opaqueStatementId: opaqueStatementId,
    opaqueDatabaseId: opaqueDatabaseId,
    fileName: fileName,
    sql: sql
  })""",
)

internal fun enrichStep(
  opaqueStatementId: dynamic,
  opaqueDatabaseId: dynamic,
  fileName: dynamic,
  sql: dynamic,
  bindings: dynamic,
): dynamic = js(
  """({
    cmd: 'step',
    opaqueStatementId: opaqueStatementId,
    opaqueDatabaseId: opaqueDatabaseId,
    fileName: fileName,
    sql: sql,
    bindings: bindings
  })""",
)

internal fun enrichClose(
  opaqueStatementId: dynamic,
  opaqueDatabaseId: dynamic,
  fileName: dynamic,
  sql: dynamic,
  statementDatabaseId: dynamic,
): dynamic = js(
  """({
    cmd: 'close',
    opaqueStatementId: opaqueStatementId,
    opaqueDatabaseId: opaqueDatabaseId,
    fileName: fileName,
    sql: sql,
    statementDatabaseId: statementDatabaseId
  })""",
)

internal fun newLeaderResultData(data: dynamic): dynamic = js("({ data: data })")

internal fun newLeaderResultError(error: String): dynamic = js("({ error: error })")

internal fun stmtBind(stmt: dynamic, index: Int, value: dynamic) {
  js("stmt.bind(index, value)")
}

internal fun stmtStep(stmt: dynamic): Boolean = js("!!stmt.step()")

internal fun stmtReset(stmt: dynamic) {
  js("stmt.reset()")
}

internal fun stmtClearBindings(stmt: dynamic) {
  js("stmt.clearBindings()")
}

internal fun stmtFinalize(stmt: dynamic) {
  js("stmt.finalize()")
}

internal fun stmtPrepare(db: dynamic, sql: dynamic): dynamic = js("db.prepare(sql)")

internal fun stmtGetRow(stmt: dynamic): dynamic = js("stmt.get([])")

internal fun stmtColumnCount(stmt: dynamic): Int = js("stmt.columnCount")

internal fun dbClose(db: dynamic) {
  js("db.close()")
}

internal fun poolPauseVfs(util: dynamic) {
  js("util.pauseVfs()")
}
