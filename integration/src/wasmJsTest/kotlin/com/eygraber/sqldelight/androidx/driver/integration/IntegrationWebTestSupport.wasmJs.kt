@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.eygraber.sqldelight.androidx.driver.integration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import kotlinx.coroutines.await
import org.w3c.dom.Worker
import kotlin.js.Promise

internal actual class WebTestSqliteDriver : SQLiteDriver {
  override val hasConnectionPool: Boolean get() = false

  override suspend fun open(fileName: String): SQLiteConnection = openWebTestConnection(fileName)
}

internal actual fun removeOpfsEntry(name: String) {
  removeOpfsEntryPromise(name)
}

internal actual suspend fun removeOpfsDirectory(name: String): String =
  removeOpfsDirectoryPromise(name).await<JsString>().toString()

internal actual class TestControlPort(private val handle: JsAny) {
  actual suspend fun next(): String = nextControlMessage(handle).await<JsString>().toString()
}

internal actual fun attachTestControlPort(worker: Worker): TestControlPort =
  TestControlPort(attachControlPort(worker))

internal actual class TestFollowerCountPort(private val handle: JsAny) {
  actual suspend fun next(): Int = nextFollowerCount(handle).await<JsNumber>().toInt()
}

internal actual fun attachFollowerCountPort(worker: Worker): TestFollowerCountPort =
  TestFollowerCountPort(attachFollowerCounts(worker))

internal actual class TestPoolFileHold(private val handle: JsAny) {
  actual suspend fun release() {
    releasePoolFileHold(handle).await<JsAny?>()
  }
}

internal actual suspend fun holdFirstPoolFile(): TestPoolFileHold {
  val handle = startPoolFileHold()
  awaitPoolFileHeld(handle).await<JsAny?>()
  return TestPoolFileHold(handle)
}

@JsFun(
  """() => {
    const source = [
      'let ah = null;',
      'async function tryHold() {',
      '  let d = await navigator.storage.getDirectory();',
      '  d = await d.getDirectoryHandle(".opfs-sahpool");',
      '  d = await d.getDirectoryHandle(".opaque");',
      '  for await (const [, h] of d.entries()) {',
      '    if (h.kind === "file") return await h.createSyncAccessHandle();',
      '  }',
      '  throw new Error("the SAH pool has no files");',
      '}',
      'onmessage = async (ev) => {',
      '  if (ev.data === "hold") {',
      '    while (!ah) {',
      '      try { ah = await tryHold(); } catch (e) { await new Promise((r) => setTimeout(r, 50)); }',
      '    }',
      '    postMessage("held");',
      '  } else if (ev.data === "release") {',
      '    ah.close();',
      '    ah = null;',
      '    postMessage("released");',
      '  }',
      '};',
    ].join('\n');
    const worker = new Worker(URL.createObjectURL(new Blob([source], { type: 'text/javascript' })));
    const replies = [];
    const waiters = [];
    worker.onmessage = (ev) => {
      if (waiters.length) waiters.shift()(ev.data); else replies.push(ev.data);
    };
    const next = () => replies.length ? Promise.resolve(replies.shift()) : new Promise((r) => waiters.push(r));
    worker.postMessage('hold');
    const held = next();
    return {
      held: () => held,
      release: () => {
        worker.postMessage('release');
        return next().then(() => worker.terminate());
      }
    };
  }""",
)
private external fun startPoolFileHold(): JsAny

@JsFun("(hold) => hold.held()")
private external fun awaitPoolFileHeld(hold: JsAny): Promise<JsAny?>

@JsFun("(hold) => hold.release()")
private external fun releasePoolFileHold(hold: JsAny): Promise<JsAny?>

@JsFun(
  """(name) => navigator.storage.getDirectory()
        .then(d => d.removeEntry(name).catch(() => undefined))
        .catch(() => undefined)""",
)
private external fun removeOpfsEntryPromise(name: String): Promise<JsAny?>

@JsFun(
  """(name) => navigator.storage.getDirectory()
        .then(d => d.removeEntry(name, { recursive: true }).then(() => 'ok', (e) => String(e.name)))
        .catch((e) => String(e && e.name))""",
)
private external fun removeOpfsDirectoryPromise(name: String): Promise<JsString>

@JsFun("(worker) => worker.postMessage({ __opfsPause: true })")
internal actual external fun postOpfsPause(worker: Worker)

@JsFun("(worker) => worker.postMessage({ __opfsResume: true })")
internal actual external fun postOpfsResume(worker: Worker)

@JsFun("(worker) => worker.postMessage({ __opfsDebugFollowerCount: true })")
internal actual external fun postFollowerCountRequest(worker: Worker)

@JsFun(
  """(worker) => {
    const channel = new MessageChannel();
    worker.postMessage({ __opfsControlPort: channel.port2 }, [channel.port2]);
    const queue = [];
    const waiters = [];
    channel.port1.onmessage = (ev) => {
      const data = ev.data || {};
      const key = Object.keys(data).find((k) => k.startsWith('__opfs')) || '';
      const value = key === '__opfsResumeFailed' ? key + ':' + String(data[key]) : key;
      if (waiters.length) waiters.shift()(value); else queue.push(value);
    };
    return {
      next: () => queue.length ? Promise.resolve(queue.shift()) : new Promise((r) => waiters.push(r))
    };
  }""",
)
private external fun attachControlPort(worker: Worker): JsAny

@JsFun("(control) => control.next()")
private external fun nextControlMessage(control: JsAny): Promise<JsString>

@JsFun(
  """(worker) => {
    const channel = new MessageChannel();
    worker.postMessage({ __opfsControlPort: channel.port2 }, [channel.port2]);
    const queue = [];
    const waiters = [];
    channel.port1.onmessage = (ev) => {
      const data = ev.data || {};
      if (data.__opfsFollowerCount === undefined) return;
      const value = data.__opfsFollowerCount;
      if (waiters.length) waiters.shift()(value); else queue.push(value);
    };
    return {
      next: () => queue.length ? Promise.resolve(queue.shift()) : new Promise((r) => waiters.push(r))
    };
  }""",
)
private external fun attachFollowerCounts(worker: Worker): JsAny

@JsFun("(control) => control.next()")
private external fun nextFollowerCount(control: JsAny): Promise<JsNumber>
