package com.eygraber.sqldelight.androidx.driver.opfs.worker

internal var sqlite3: dynamic = null
internal var poolUtil: dynamic = null

internal var initData: dynamic = null
internal var multiTabMode: String = "Single"

internal var controlPort: MessagePortLike? = null

// Maps for follower-side / single-mode state. Backed by Kotlin maps; their entries are
// JS objects (constructed via [newDbEntry] / [newStmtEntry]) so the worker stays a faithful
// port of the original JS — `entry.instance` is mutated in place across pause/resume cycles.
internal val databases = mutableMapOf<Int, dynamic>()
internal val statements = mutableMapOf<Int, dynamic>()

internal var nextDatabaseId = 0
internal var nextStatementId = 0

internal val tabId: String = newTabId()

internal var bc: BroadcastChannelLike? = null
internal var isLeader = false
internal var pendingLeaderResponses = mutableMapOf<Int, dynamic>()
internal var nextForwardReqId = 0

// Per-follower leader state. Values are JS objects with `databases` and `statements` JS Maps so
// the existing leaderProcess flow can use their native get/set/delete idempotency.
internal val followerStates = mutableMapOf<String, dynamic>()

internal val queuedDriverMessages = mutableListOf<MessageEventLike>()
internal var acceptingDriverMessages = false

// Single mode: set when sqlite3 initialization has failed for good (retries exhausted).
// Requests are answered with this error instead of queueing forever — see onMessage.
internal var localSqliteInitError: dynamic = null

// PauseOnHidden lifecycle. Resuming is a real state, not a detail: the resume chain
// (sqlite init / unpauseVfs) is asynchronous, and both pause and resume messages that arrive
// mid-chain need to be handled against it, not against Paused/Live.
internal enum class PauseState { Live, Resuming, Paused }

internal var pauseState = PauseState.Live

// A __opfsPause arrived while the resume chain was in flight. The pause work and its ack are
// deferred until the chain settles — see onResumeSettled/onResumeFailed in WorkerMain.
internal var pendingPause = false

internal val pausedQueue = mutableListOf<MessageEventLike>()

// Shared connections live until the worker terminates; followers may still reference them.
internal val sharedLeaderConnections = mutableMapOf<String, dynamic>()

internal var initStarted = false
