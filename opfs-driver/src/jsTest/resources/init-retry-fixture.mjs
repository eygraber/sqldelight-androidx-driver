// Run the production worker with a controllable SQLite module. All retry, queue, pause and
// election behavior comes from the compiled worker; the fixture replaces only SQLite itself.
export function createInitRetryFixture(source, mode, options = {}) {
  const moduleSource = options.sqlite3Url ? `
    import initialize from ${JSON.stringify(options.sqlite3Url)};
    self.fixture.imports++;
    export default function (options) {
      self.fixture.factories++;
      return initialize(options);
    }
  ` : `
    const state = self.fixture;
    state.imports++;
    self.postMessage({fixtureEvent: 'import', url: import.meta.url});
    if (state.imports <= state.importFailures) throw new Error('fixture import failed');
    export default function () {
      state.factories++;
      self.postMessage({fixtureEvent: 'factory'});
      if (state.factories <= state.factoryThrowFailures) throw new Error('fixture factory threw');
      if (state.factories <= state.factoryFailures) return Promise.reject(new Error('fixture wasm failed'));
      const install = () => {
        state.pools++;
        self.postMessage({fixtureEvent: 'pool'});
        if (state.pools <= state.poolFailures) {
          const error = new Error('fixture pool failed');
          error.name = state.poolErrorName || 'Error';
          return Promise.reject(error);
        }
        const util = {
          OpfsSAHPoolDb: class { close() {} },
          pauseVfs() { self.postMessage({fixtureEvent: 'pause-vfs'}); },
          unpauseVfs() { return Promise.resolve(); }
        };
        if (state.holdPool) return new Promise(resolve => { state.releasePool = () => resolve(util); });
        return Promise.resolve(util);
      };
      const factory = { installOpfsSAHPoolVfs: install, capi: { sqlite3_get_autocommit: () => 1 } };
      if (state.holdFactory) return new Promise(resolve => { state.releaseFactory = () => resolve(factory); });
      return Promise.resolve(factory);
    }
  `;
  const moduleUrl = URL.createObjectURL(new Blob([moduleSource], {type: 'text/javascript'}));
  const prelude = `
    self.fixture = ${JSON.stringify({importFailures: 0, factoryFailures: 0, poolFailures: 0,
      timerCapMs: 25, timerFloorMs: 0, imports: 0, factories: 0, pools: 0, wasmFetches: 0, ...options})};
    const nativeSetTimeout = self.setTimeout.bind(self);
    self.setTimeout = (callback, delay) => {
      self.postMessage({fixtureEvent: 'retry', delay});
      return nativeSetTimeout(callback, Math.max(self.fixture.timerFloorMs, Math.min(delay, self.fixture.timerCapMs)));
    };
    const NativeBroadcastChannel = self.BroadcastChannel;
    self.BroadcastChannel = class extends NativeBroadcastChannel {
      addEventListener(type, listener) {
        super.addEventListener(type, event => {
          if (self.fixture.holdSharedRequests && event.data.kind === 'request') {
            self.postMessage({fixtureEvent: 'forwarded'});
          } else listener(event);
        });
      }
    };
    const nativeFetch = self.fetch.bind(self);
    self.fetch = (url, options) => {
      if (String(url) === self.fixture.wasmUrl) {
        self.fixture.wasmFetches++;
        if (self.fixture.wasmFetches <= self.fixture.wasmFetchFailures) {
          return Promise.reject(new TypeError('fixture wasm fetch failed'));
        }
      }
      return nativeFetch(url, options);
    };
    // Emscripten falls back from streaming fetch to fetch, then to synchronous XHR.
    const NativeXHR = self.XMLHttpRequest;
    self.XMLHttpRequest = class extends NativeXHR {
      open(method, url, ...args) {
        this.fixtureUrl = String(url);
        return super.open(method, url, ...args);
      }
      send(...args) {
        if (this.fixtureUrl === self.fixture.wasmUrl && self.fixture.wasmXhrFailures > 0) {
          self.fixture.wasmXhrFailures--;
          throw new TypeError('fixture wasm XHR failed');
        }
        return super.send(...args);
      }
    };
  `;
  const epilogue = `
    const handler = self.onmessage;
    self.onmessage = event => {
      const data = event.data;
      if (data.fixtureConfig) {
        Object.assign(self.fixture, data.fixtureConfig);
        self.postMessage({fixtureEvent: 'configured'});
      } else if (data.fixtureReleaseFactory) {
        self.fixture.releaseFactory();
      } else if (data.fixtureReleasePool) {
        self.fixture.releasePool();
      } else if (data.fixtureStats) {
        self.postMessage({fixtureEvent: 'stats', imports: self.fixture.imports,
          factories: self.fixture.factories, pools: self.fixture.pools, wasmFetches: self.fixture.wasmFetches});
      } else handler(event);
    };
  `;
  const workerUrl = URL.createObjectURL(new Blob([prelude, source, epilogue], {type: 'text/javascript'}));
  const worker = new Worker(workerUrl, {type: 'module'});
  URL.revokeObjectURL(workerUrl);
  const queued = [];
  const waiting = [];
  const accept = data => {
    const index = waiting.findIndex(w => w.matches(data));
    if (index >= 0) waiting.splice(index, 1)[0].resolve(data);
    else queued.push(data);
  };
  worker.onmessage = event => accept(event.data);
  const channel = new MessageChannel();
  channel.port1.onmessage = event => accept(event.data);
  worker.postMessage({__opfsControlPort: channel.port2}, [channel.port2]);
  worker.postMessage({__opfsInit: {mode, sqlite3Url: moduleUrl + '#original', wasmUrl: options.wasmUrl || 'unused'}});
  const next = matches => {
    const index = queued.findIndex(matches);
    if (index >= 0) return Promise.resolve(queued.splice(index, 1)[0]);
    return new Promise(resolve => waiting.push({matches, resolve}));
  };
  let id = 0;
  return {
    event: name => next(data => data.fixtureEvent === name),
    control: name => next(data => data[name] !== undefined),
    open() {
      const requestId = ++id;
      worker.postMessage({id: requestId, data: {cmd: 'open', fileName: 'fixture.db'}});
      return next(data => data.id === requestId);
    },
    pause() { worker.postMessage({__opfsPause: true}); },
    resume() { worker.postMessage({__opfsResume: true}); },
    close() { worker.postMessage({__opfsClose: true}); },
    releaseFactory() { worker.postMessage({fixtureReleaseFactory: true}); },
    releasePool() { worker.postMessage({fixtureReleasePool: true}); },
    async configure(config) {
      worker.postMessage({fixtureConfig: config});
      await next(data => data.fixtureEvent === 'configured');
    },
    async stats() {
      worker.postMessage({fixtureStats: true});
      return next(data => data.fixtureEvent === 'stats');
    },
    dispose() {
      worker.terminate();
      channel.port1.close();
      URL.revokeObjectURL(moduleUrl);
    }
  };
}
