@file:Suppress("UnusedParameter", "TrimMultilineRawString")

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
  removeOpfsDirectoryPromise(name).await()

internal actual class TestControlPort(private val handle: dynamic) {
  actual suspend fun next(): String = nextControlMessage(handle).await()
}

internal actual fun attachTestControlPort(worker: Worker): TestControlPort =
  TestControlPort(attachControlPort(worker))

internal actual class TestFollowerCountPort(private val handle: dynamic) {
  actual suspend fun next(): Int = nextFollowerCount(handle).await()
}

internal actual fun attachFollowerCountPort(worker: Worker): TestFollowerCountPort =
  TestFollowerCountPort(attachFollowerCounts(worker))

private fun removeOpfsEntryPromise(name: String): Promise<Any?> = js(
  """navigator.storage.getDirectory()
        .then(d => d.removeEntry(name).catch(() => undefined))
        .catch(() => undefined)""",
)

private fun removeOpfsDirectoryPromise(name: String): Promise<String> = js(
  """navigator.storage.getDirectory()
        .then(d => d.removeEntry(name, { recursive: true }).then(() => 'ok', (e) => String(e.name)))
        .catch((e) => String(e && e.name))""",
)

internal actual fun postOpfsPause(worker: Worker) {
  js("worker.postMessage({ __opfsPause: true })")
}

internal actual fun postOpfsResume(worker: Worker) {
  js("worker.postMessage({ __opfsResume: true })")
}

internal actual fun postFollowerCountRequest(worker: Worker) {
  js("worker.postMessage({ __opfsDebugFollowerCount: true })")
}

private fun attachControlPort(worker: Worker): dynamic = js(
  """(function () {
    var channel = new MessageChannel();
    worker.postMessage({ __opfsControlPort: channel.port2 }, [channel.port2]);
    var queue = [];
    var waiters = [];
    channel.port1.onmessage = function (ev) {
      var data = ev.data || {};
      var key = Object.keys(data).find(function (k) { return k.startsWith('__opfs'); }) || '';
      var value = key === '__opfsResumeFailed' ? key + ':' + String(data[key]) : key;
      if (waiters.length) waiters.shift()(value); else queue.push(value);
    };
    return {
      next: function () {
        return queue.length ? Promise.resolve(queue.shift()) : new Promise(function (r) { waiters.push(r); });
      }
    };
  })()""",
)

private fun nextControlMessage(control: dynamic): Promise<String> = js("control.next()")

private fun attachFollowerCounts(worker: Worker): dynamic = js(
  """(function () {
    var channel = new MessageChannel();
    worker.postMessage({ __opfsControlPort: channel.port2 }, [channel.port2]);
    var queue = [];
    var waiters = [];
    channel.port1.onmessage = function (ev) {
      var data = ev.data || {};
      if (data.__opfsFollowerCount === undefined) return;
      var value = data.__opfsFollowerCount;
      if (waiters.length) waiters.shift()(value); else queue.push(value);
    };
    return {
      next: function () {
        return queue.length ? Promise.resolve(queue.shift()) : new Promise(function (r) { waiters.push(r); });
      }
    };
  })()""",
)

private fun nextFollowerCount(control: dynamic): Promise<Int> = js("control.next()")
