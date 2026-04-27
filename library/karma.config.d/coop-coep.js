// SQLite WASM's OPFS variant uses SharedArrayBuffer, which is gated on a cross-origin isolated
// context. Karma's dev server must serve test pages with COOP+COEP so the page (and the worker
// it spawns) qualify as crossOriginIsolated.
config.set({
  customHeaders: [
    { match: '.*', name: 'Cross-Origin-Opener-Policy', value: 'same-origin' },
    { match: '.*', name: 'Cross-Origin-Embedder-Policy', value: 'require-corp' },
  ],
});
