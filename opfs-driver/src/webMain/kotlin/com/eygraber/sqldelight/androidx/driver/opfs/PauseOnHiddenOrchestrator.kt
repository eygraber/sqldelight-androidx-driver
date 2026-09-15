@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver.opfs

/**
 * Wires up the main-thread half of `PauseOnHidden` mode: acquires a same-origin foreground Web
 * Lock while the document is visible, yields it on hide/blur (when another tab is contending),
 * and bridges the worker's pause/resume protocol to the lock's lifetime.
 *
 * Invariants the orchestrator maintains:
 *  - Exactly one `navigator.locks.request(LOCK_NAME, ...)` is in flight at a time. The
 *    `releaser` field holds the resolver-handle for the held promise; non-null ⇒ we own (or are
 *    actively trying to own) the lock.
 *  - The Web Lock is not released until the worker has acked `__opfsPause` via the control
 *    port, so the next tab can't try to install the SAH pool while our handles are still open.
 *  - `onLive`/`onPaused` follow the worker's acknowledgements. Live fires only after the worker
 *    acked `__opfsResume`, and Paused fires after the worker acked `__opfsPause`.
 *  - A failed resume releases the Web Lock and retries the acquisition with a bounded backoff
 *    while the document is visible.
 */
internal fun startPauseOnHiddenOrchestration(
  handle: WorkerHandle,
  onLive: () -> Unit,
  onPaused: () -> Unit,
): PauseOnHiddenOrchestrator {
  // Initial state: paused — we have to wait for the foreground lock before claiming the OPFS
  // handles. Fires synchronously so the consumer can render the "another window has the
  // database" UI before any Live transition arrives on a microtask.
  onPaused()
  return PauseOnHiddenOrchestrator(handle, onLive, onPaused).apply { start() }
}

private const val LOCK_NAME = "sqldelight-androidx-opfs-foreground"
private const val CONTENTION_CHANNEL_NAME = "sqldelight-androidx-opfs-contention"
private const val INITIAL_RETRY_DELAY_MS = 250
private const val MAX_RETRY_DELAY_MS = 5_000

internal class PauseOnHiddenOrchestrator(
  private val handle: WorkerHandle,
  private val onLive: () -> Unit,
  private val onPaused: () -> Unit,
) {
  // Resolver-handle for the held-promise of the in-flight `navigator.locks.request`. Non-null
  // means we either hold the lock or are racing to acquire it; null means we're paused.
  private var releaser: LockReleaser? = null

  // Stashed [releaser] waiting for the worker to ack `__opfsPause` before we resolve the held
  // promise (and thereby release the Web Lock). See [dropLock] / [onPausedAck].
  private var pendingRelease: LockReleaser? = null

  private var lockGeneration = 0

  private var live = false

  private var retryTimer: Int? = null
  private var retryDelayMs = INITIAL_RETRY_DELAY_MS

  // Cross-tab signal channel. Non-focused holders drop the lock when a peer broadcasts wanting.
  private lateinit var contentionBc: BroadcastChannelLike

  private val listeners = createAbortController()

  private var detached = false

  fun start() {
    handle.onPausedAck = { onPausedAck() }
    handle.onResumedAck = { onResumedAck() }
    handle.onResumeFailed = { message -> onResumeFailed(message) }
    contentionBc = createContentionBroadcastChannel(CONTENTION_CHANNEL_NAME) { onWanting() }

    // (document.hasFocus() can be unreliable in headless browsers, so don't gate the initial
    // acquire on it — visibility alone decides.)
    if(documentIsVisible()) requestLock()

    val signal = listeners.signal
    addDocumentEventListener("visibilitychange", { onVisibilityChange() }, signal)
    addSelfEventListener("focus", { onFocus() }, signal)
    addSelfEventListener("blur", { onBlur() }, signal)
    addSelfEventListener("pagehide", { dropLock() }, signal)
  }

  /**
   * Stops all lock requests, listeners, timers, and callbacks. The held Web Lock stays held
   * until [releaseLock] runs, so the worker can release its handles first.
   */
  fun detach() {
    if(detached) return
    detached = true
    cancelRetry()
    listeners.abort()
    closeBroadcastChannel(contentionBc)
  }

  fun releaseLock() {
    lockGeneration++
    pendingRelease?.release()
    pendingRelease = null
    releaser?.release()
    releaser = null
  }

  private fun requestLock() {
    if(releaser != null || detached) return
    cancelRetry()
    // Tell other tabs we want the lock — any holder that isn't focused will yield.
    postContentionWanting(contentionBc)
    val generation = ++lockGeneration
    releaser = requestExclusiveLock(
      name = LOCK_NAME,
      onAcquired = {
        if(generation == lockGeneration && releaser != null) {
          postOpfsResume(handle.worker)
        }
      },
      onFailure = { err ->
        consoleError("sqldelight-androidx-opfs: foreground lock failed: $err")
        if(generation == lockGeneration) releaser = null
      },
    )
  }

  private fun dropLock() {
    cancelRetry()
    val r = releaser ?: return
    releaser = null
    // Wait for the worker to confirm it has run pauseVfs() and released its SAH handles before
    // releasing the Web Lock — otherwise the next tab can win the lock and try to unpause while
    // our handles are still open, hitting NoModificationAllowedError.
    pendingRelease = r
    postOpfsPause(handle.worker)
  }

  private fun onPausedAck() {
    emitPaused()
    val r = pendingRelease ?: return
    pendingRelease = null
    r.release()
  }

  private fun onResumedAck() {
    if(releaser == null || pendingRelease != null) return
    retryDelayMs = INITIAL_RETRY_DELAY_MS
    emitLive()
  }

  private fun onResumeFailed(message: String) {
    consoleError("sqldelight-androidx-opfs: resume failed: $message")
    val r = releaser ?: return
    if(pendingRelease != null) return
    releaser = null
    r.release()
    emitPaused()
    scheduleRetry()
  }

  private fun scheduleRetry() {
    if(detached || !documentIsVisible()) return
    val delayMs = retryDelayMs
    retryDelayMs = minOf(delayMs * 2, MAX_RETRY_DELAY_MS)
    retryTimer = scheduleTimeout(delayMs) {
      retryTimer = null
      if(documentIsVisible() && releaser == null) requestLock()
    }
  }

  private fun cancelRetry() {
    retryTimer?.let(::cancelTimeout)
    retryTimer = null
  }

  private fun emitLive() {
    if(live || detached) return
    live = true
    onLive()
  }

  private fun emitPaused() {
    if(!live || detached) return
    live = false
    onPaused()
  }

  private fun onWanting() {
    if(releaser != null && !documentHasFocus()) dropLock()
  }

  private fun onFocus() {
    if(documentIsVisible() && releaser == null) requestLock()
  }

  private fun onBlur() {
    // Only drop on blur when another tab is actually contending — transient focus loss from
    // clicking the URL bar or opening dev tools shouldn't pause us, but focus moving to another
    // window of the same app should.
    if(releaser == null) return
    queryLockHasPending(LOCK_NAME) { wanted ->
      if(wanted && releaser != null && !documentHasFocus()) dropLock()
    }
  }

  private fun onVisibilityChange() {
    if(documentIsVisible()) {
      if(releaser == null) requestLock()
    }
    else {
      // Tab is fully hidden — yield unconditionally.
      dropLock()
    }
  }
}
