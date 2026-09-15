package com.eygraber.sqldelight.androidx.driver.opfs.worker

internal var sqlite3: dynamic = null
internal var poolUtil: dynamic = null

internal var initData: dynamic = null
internal var multiTabMode: String = "Single"

internal val controlPorts = mutableListOf<MessagePortLike>()

// Maps for follower-side / single-mode state. Backed by Kotlin maps; their entries are
// JS objects (constructed via [newDbEntry] / [newStmtEntry]) so the worker stays a faithful
// port of the original JS — `entry.instance` is mutated in place across pause/resume cycles.
internal val databases = mutableMapOf<Int, dynamic>()
internal val statements = mutableMapOf<Int, dynamic>()

internal var nextDatabaseId = 0
internal var nextStatementId = 0

internal val tabId: String = newTabId()

internal var bc: BroadcastChannelLike? = null
internal var leaderLock: LockHandle? = null
internal var tabLock: LockHandle? = null
internal var isLeader = false
internal var knownLeaderId: String? = null
internal var pendingLeaderResponses = mutableMapOf<Int, dynamic>()
internal var nextForwardReqId = 0

// Per-follower leader state. Values are JS objects with `databases` and `statements` JS Maps so
// the existing leaderProcess flow can use their native get/set/delete idempotency.
internal val followerStates = mutableMapOf<String, dynamic>()

internal val queuedDriverMessages = mutableListOf<MessageEventLike>()
internal var acceptingDriverMessages = false

// PauseOnHidden lifecycle. Resuming is a real state, not a detail: the resume chain
// (sqlite init / unpauseVfs) is asynchronous, and both pause and resume messages that arrive
// mid-chain need to be handled against it, not against Paused/Live.
internal enum class PauseState { Live, Resuming, Paused }

internal var pauseState = PauseState.Live

// Set when a __opfsPause must wait for the resume chain to settle or for an open transaction to end.
internal var pendingPause = false

internal val pausedQueue = mutableListOf<MessageEventLike>()

// Shared connections live until the worker terminates; followers may still reference them.
internal val sharedLeaderConnections = mutableMapOf<String, dynamic>()

internal class SharedTransactionOwner(
  val followerId: String,
  val opaqueDatabaseId: dynamic,
) {
  var lockWatch: dynamic = null
}

internal class QueuedLeaderRequest(
  val followerId: String,
  val payload: dynamic,
  val reply: (dynamic) -> Unit,
)

internal val sharedTransactionOwners = mutableMapOf<String, SharedTransactionOwner>()
internal val sharedRequestQueues = mutableMapOf<String, MutableList<QueuedLeaderRequest>>()

internal var initStarted = false

internal var closeRequested = false

internal var pendingClose = false
