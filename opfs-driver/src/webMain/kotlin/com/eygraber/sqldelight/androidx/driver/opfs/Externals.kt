// Every helper in this file is a thin wrapper around a `js("...")` body. The Kotlin parameters
// are referenced inside the JS via Kotlin's variable capture, but detekt doesn't see through
// `js("...")` and flags every parameter as unused — suppress UnusedParameter file-wide.
// The multiline JS strings are intentionally raw (no `.trimIndent()`) so the JS leading
// whitespace stays as-is — it's irrelevant at parse time and avoids a runtime trim per call.
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("UnusedParameter", "TrimMultilineRawString")

package com.eygraber.sqldelight.androidx.driver.opfs

import org.w3c.dom.Worker

/**
 * URL of `@sqlite.org/sqlite-wasm`'s ESM entry, resolved via webpack's
 * `new URL(specifier, import.meta.url)` rewrite. Webpack matches that exact pattern in the
 * generated JS bundle and produces a stable URL that the spawned worker can `import()`.
 */
internal fun resolveSqliteWasmUrl(): String = js(
  """new URL("@sqlite.org/sqlite-wasm", import.meta.url).toString()""",
)

/**
 * URL of the sqlite3 wasm companion file. Same webpack rewrite as [resolveSqliteWasmUrl];
 * handed to sqlite3's `locateFile` so it doesn't try to fetch `sqlite3.wasm` from `/`.
 */
internal fun resolveSqliteWasmCompanionUrl(): String = js(
  """new URL("@sqlite.org/sqlite-wasm/sqlite3.wasm", import.meta.url).toString()""",
)

/** Wraps the worker source in a Blob and returns an `application/javascript` object URL. */
internal fun createWorkerBlobUrl(source: String): String = js(
  """URL.createObjectURL(new Blob([source], { type: 'application/javascript' }))""",
)

/** Releases an object URL produced by [createWorkerBlobUrl]. */
internal fun revokeBlobUrl(url: String) {
  js("URL.revokeObjectURL(url)")
}

/** `new Worker(url, { type: 'module' })` — the worker source uses ESM `import()`. */
internal fun createModuleWorker(blobUrl: String): Worker = js(
  """new Worker(blobUrl, { type: 'module' })""",
)

/**
 * `new MessageChannel()` wrapped to also transfer `port2` to [worker]. Returns `port1`, which
 * stays on the main thread for control-message traffic from the worker.
 *
 * The control channel keeps internal handshake messages (currently just the pause-ack) off the
 * worker's default port so the AndroidX `WebWorkerSQLiteDriver` — which listens on the default
 * port and parses every message as a SQL reply — never sees them.
 */
internal fun createControlChannelAndTransferToWorker(worker: Worker): MessagePortLike = js(
  """
    (() => {
      const c = new MessageChannel();
      worker.postMessage({ __opfsControlPort: c.port2 }, [c.port2]);
      return c.port1;
    })()
  """,
)

/** Posts the `{ __opfsInit: ... }` payload to the worker so it can boot sqlite. */
internal fun postOpfsInit(worker: Worker, sqlite3Url: String, wasmUrl: String, mode: String) {
  js(
    """
      worker.postMessage({
        __opfsInit: { sqlite3Url: sqlite3Url, wasmUrl: wasmUrl, mode: mode }
      })
    """,
  )
}

/** Posts `{ __opfsPause: true }` to ask the worker to release its SAH handles. */
internal fun postOpfsPause(worker: Worker) {
  js("worker.postMessage({ __opfsPause: true })")
}

/** Posts `{ __opfsResume: true }` so the worker can re-acquire SAH handles. */
internal fun postOpfsResume(worker: Worker) {
  js("worker.postMessage({ __opfsResume: true })")
}

/**
 * A handle to the main-thread end of the control [MessageChannel]. Externalized here so the
 * orchestrator can subscribe to pause-ack messages without leaking a reference to the underlying
 * `MessagePort` (the `org.w3c.dom.MessagePort` binding has different shapes between js and
 * wasmJs targets, and we only need a single-shot listener).
 */
internal external class MessagePortLike

/** Subscribes [callback] to pause-ack messages on the control port. */
internal fun listenForPausedAck(controlPort: MessagePortLike, callback: () -> Unit) {
  js(
    """controlPort.onmessage = (ev) => { if (ev.data && ev.data.__opfsPausedAck) callback(); }""",
  )
}

/** True when this document is currently visible (`document.visibilityState === 'visible'`). */
internal fun documentIsVisible(): Boolean = js(
  """document.visibilityState === 'visible'""",
)

/** True when this document currently holds focus. */
internal fun documentHasFocus(): Boolean = js(
  """document.hasFocus()""",
)

/** Adds an event listener to `document` — used for `visibilitychange`. */
internal fun addDocumentEventListener(type: String, listener: () -> Unit) {
  js("document.addEventListener(type, listener)")
}

/** Adds an event listener to `self` (window in the main thread). */
internal fun addSelfEventListener(type: String, listener: () -> Unit) {
  js("self.addEventListener(type, listener)")
}

/**
 * `new BroadcastChannel(name)` plus an `onmessage` handler that fires [onWanting] whenever a
 * peer broadcasts `{ wanting: true }`. The actual `BroadcastChannel.postMessage` is hidden
 * behind [postContentionWanting] so callers don't have to construct the JSON shape twice.
 */
internal fun createContentionBroadcastChannel(name: String, onWanting: () -> Unit): BroadcastChannelLike = js(
  """
    (() => {
      const bc = new BroadcastChannel(name);
      bc.onmessage = (ev) => { if (ev.data && ev.data.wanting) onWanting(); };
      return bc;
    })()
  """,
)

/** Broadcasts `{ wanting: true }` on the contention channel. */
internal fun postContentionWanting(bc: BroadcastChannelLike) {
  js("bc.postMessage({ wanting: true })")
}

/**
 * Opaque handle for a `BroadcastChannel` we created. As with [MessagePortLike], we don't pull in
 * the `org.w3c.dom.BroadcastChannel` binding because all access is via the helpers in this file.
 */
internal external class BroadcastChannelLike

/**
 * Calls `navigator.locks.request(name, { mode: 'exclusive' }, ...)`. The held promise — which
 * the lock callback returns and the browser awaits before releasing the lock — is resolved when
 * [LockReleaser.release] is invoked on the returned handle.
 *
 * [onAcquired] runs the moment the lock is granted; [onFailure] runs only if the request itself
 * rejects (the underlying `request` Promise's `.catch`).
 */
internal fun requestExclusiveLock(
  name: String,
  onAcquired: () -> Unit,
  onFailure: (String) -> Unit,
): LockReleaser = js(
  """
    (() => {
      let resolveHeld;
      const heldUntil = new Promise((r) => { resolveHeld = r; });
      navigator.locks.request(name, { mode: 'exclusive' }, () => {
        onAcquired();
        return heldUntil;
      }).catch((err) => {
        onFailure(err && err.message ? err.message : String(err));
      });
      return { release: () => { if (resolveHeld) { resolveHeld(); resolveHeld = null; } } };
    })()
  """,
)

/** Opaque handle used to release a lock acquired via [requestExclusiveLock]. */
internal external class LockReleaser {
  fun release()
}

/** `console.error(msg)` — there's no Kotlin/Wasm-compatible binding shipped in kotlinx-browser. */
internal fun consoleError(msg: String) {
  js("console.error(msg)")
}

/**
 * `navigator.locks.query()` — invokes [onResult] with `true` if the named lock has any pending
 * waiters, `false` otherwise. Falls back to invoking [onResult] with `false` if the API or the
 * call itself is unavailable (older browsers, restricted contexts).
 */
internal fun queryLockHasPending(name: String, onResult: (Boolean) -> Unit) {
  js(
    """
      (() => {
        try {
          navigator.locks.query()
            .then((snapshot) => {
              const wanted = ((snapshot && snapshot.pending) || []).some((l) => l.name === name);
              onResult(wanted);
            })
            .catch(() => onResult(false));
        } catch (e) {
          onResult(false);
        }
      })()
    """,
  )
}
