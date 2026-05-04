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

internal var isPaused = false
internal val pausedQueue = mutableListOf<MessageEventLike>()

// Shared connections live until the worker terminates; followers may still reference them.
internal val sharedLeaderConnections = mutableMapOf<String, dynamic>()

internal var initStarted = false
