package com.eygraber.sqldelight.androidx.driver.opfs

import org.w3c.dom.Worker

/**
 * The current cross-tab status of an [opfsWorker]. Reported via the optional
 * `onLockStateChange` callback so an app can show a "another window is using the database"
 * indicator (or queue user-driven actions) instead of presenting suspended queries as a hang.
 */
public enum class OpfsLockState {
  /** This worker can serve queries. */
  Live,

  /** Another tab or window is currently using the database. Queries from this worker are queued
   *  until the database becomes available again. */
  Paused,
}

/**
 * Strategy for sharing an OPFS-backed database across multiple tabs of the same origin.
 *
 * Browsers don't allow more than one connection to an OPFS database at a time, so when an app is
 * opened in two tabs, the tabs need to coordinate access. This enum picks the coordination
 * strategy.
 */
public enum class OpfsMultiTabMode {
  /**
   * Only one tab can use the database at a time. Opening the app in a second tab while the
   * first is still open will fail. Use only if your app is guaranteed to run in a single tab.
   */
  Single,

  /**
   * Tabs share the database one-at-a-time: the active tab uses the database, and tabs that go
   * into the background pause access and queue their queries until they regain focus. Adds no
   * per-query overhead. The trade-off is that a backgrounded tab cannot run queries until it's
   * brought back to the foreground — a tab opened in a separate window that doesn't yet have
   * focus will appear frozen if it tries to run startup queries.
   */
  PauseOnHidden,

  /**
   * All tabs can run queries concurrently. One tab is automatically elected as the database
   * owner and serves queries on behalf of the others; ownership transfers automatically when
   * the owning tab closes. The default — works regardless of which tabs have focus.
   *
   * Trade-offs: queries from non-owner tabs incur a cross-tab message round-trip, and in-flight
   * transactions on the owner tab are not preserved if it closes mid-transaction (the new owner
   * starts with fresh connections).
   */
  Shared,

  ;

  public companion object {
    public val Default: OpfsMultiTabMode = PauseOnHidden
  }
}

/**
 * Returns a [Worker] that bridges `androidx.sqlite`'s `WebWorkerSQLiteDriver` protocol to
 * `@sqlite.org/sqlite-wasm`'s OPFS VFS. Database files referenced by
 * `AndroidxSqliteDatabaseType.File("name.db")` are persisted in the browser's Origin Private
 * File System.
 *
 * Pass it to [androidx.sqlite.driver.web.WebWorkerSQLiteDriver] and hand the resulting driver
 * to `AndroidxSqliteDriver`:
 *
 * ```kotlin
 * val driver = AndroidxSqliteDriver(
 *   driver = WebWorkerSQLiteDriver(opfsWorker()),
 *   databaseType = AndroidxSqliteDatabaseType.File("music.db"),
 *   schema = MusicDatabase.Schema,
 * )
 * ```
 *
 * The worker source is embedded as a string and instantiated from a `Blob` URL, so consumers
 * don't need to copy any JS resource into their bundle. URLs for sqlite-wasm's `index.mjs` and
 * `sqlite3.wasm` are resolved via webpack's `new URL(..., import.meta.url)` syntax against the
 * transitive `@sqlite.org/sqlite-wasm` npm dep, then handed to the worker so it can dynamic-import
 * sqlite3 and override `locateFile` for the wasm companion.
 *
 * @param multiTabMode strategy for sharing the database across multiple tabs of the same origin.
 *   Defaults to [OpfsMultiTabMode.Shared] which lets every tab run queries regardless of focus.
 *   See the enum entries for the trade-offs of each mode.
 * @param onLockStateChange optional callback invoked on the main thread whenever this worker
 *   transitions between [OpfsLockState.Live] and [OpfsLockState.Paused]. Use it to surface a
 *   visible indicator when another tab/window is using the database. Always fires once
 *   synchronously with the initial state during this call.
 */
public fun opfsWorker(
  multiTabMode: OpfsMultiTabMode = OpfsMultiTabMode.Default,
  onLockStateChange: ((OpfsLockState) -> Unit)? = null,
): Worker {
  val handle = buildOpfsWorker(multiTabMode)
  val onLive = onLockStateChange?.let { cb -> { cb(OpfsLockState.Live) } } ?: {}
  val onPaused = onLockStateChange?.let { cb -> { cb(OpfsLockState.Paused) } } ?: {}
  if(multiTabMode == OpfsMultiTabMode.PauseOnHidden) {
    startPauseOnHiddenOrchestration(handle, onLive, onPaused)
  }
  else {
    // Single and Shared never voluntarily pause from the consumer's perspective — emit Live
    // once so consumers can write a uniform `state == Live` predicate without a mode check.
    onLive()
  }
  return handle.worker
}
