// Emit a single ES-module JS file. The default Kotlin/JS webpack config wraps the bundle in UMD
// and may split into chunks; both break a module worker loaded from a Blob URL — `new Worker(url,
// { type: 'module' })` can't pull additional chunks across the Blob origin and chokes on the UMD
// wrapper because there's no module/exports global. So we force a single `output.module = true`
// bundle with no code splitting.
config.experiments = config.experiments || {};
config.experiments.outputModule = true;

config.output = config.output || {};
delete config.output.libraryTarget;
config.output.library = { type: "module" };
config.output.module = true;
config.output.environment = config.output.environment || {};
config.output.environment.module = true;
config.output.environment.dynamicImport = true;
config.output.chunkFormat = "module";
config.output.chunkLoading = "import";

config.optimization = config.optimization || {};
config.optimization.splitChunks = false;
config.optimization.runtimeChunk = false;

config.target = ["web", "es2020"];
