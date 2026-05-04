package com.eygraber.sqldelight.androidx.driver.opfs

import com.eygraber.sqldelight.androidx.driver.opfs.generated.OPFS_WORKER_SOURCE
import org.w3c.dom.Worker

/**
 * Pair of references the orchestrator needs to drive a freshly-spawned OPFS worker:
 *  - [worker]: the `Worker` itself, used both to forward driver messages (transparently, by
 *    `WebWorkerSQLiteDriver`) and to send pause/resume control messages.
 *  - [controlPort]: the main-thread end of a dedicated [MessageChannel]. Used only for
 *    main-thread ↔ worker control traffic so it can't collide with the AndroidX driver's
 *    SQL-reply listener on the worker's default port.
 */
internal class WorkerHandle(
  val worker: Worker,
  val controlPort: MessagePortLike,
)

/**
 * Spawns the OPFS worker and wires up the control-port plumbing. After this returns:
 *  - The worker has been instantiated from [OPFS_WORKER_SOURCE] via a Blob URL (revoked here
 *    once the worker has captured it — keeping object URLs alive past the constructor leaks
 *    them).
 *  - The worker has received its `__opfsInit` payload (sqlite-wasm + companion URLs and the
 *    multi-tab mode).
 *  - The worker has received and stashed the transferred `port2` of a fresh [MessageChannel];
 *    [WorkerHandle.controlPort] is the corresponding `port1`.
 *
 * No multi-tab orchestration happens here — that's the orchestrator's job (see
 * `startPauseOnHiddenOrchestration` for `PauseOnHidden`; `Single`/`Shared` need none).
 */
internal fun buildOpfsWorker(mode: OpfsMultiTabMode): WorkerHandle {
  val sqlite3Url = resolveSqliteWasmUrl()
  val wasmUrl = resolveSqliteWasmCompanionUrl()
  val blobUrl = createWorkerBlobUrl(OPFS_WORKER_SOURCE)
  val worker = createModuleWorker(blobUrl)
  postOpfsInit(worker, sqlite3Url, wasmUrl, mode.name)
  val controlPort = createControlChannelAndTransferToWorker(worker)
  revokeBlobUrl(blobUrl)
  return WorkerHandle(worker, controlPort)
}
