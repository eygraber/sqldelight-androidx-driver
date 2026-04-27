# Full-Parity Web Testing Plan — Commit-by-Commit

Branch: `web` (current). Each commit is independently buildable. Between commits — and only moving on when green — run, in order:

1. `./format` — apply ktlint auto-formatting.
2. `./format --no-format` — verify formatting (lint-only) with no auto-fixes.
3. `./gradlew detektAll allTests` — static analysis + test suite.

## Execution mode

**This plan is intended to be carried out from beginning to end without stopping.** Do not pause for confirmation between commits. If a verification step fails, attempt to fix the failure within the scope of that commit and re-run verification.

The single sanctioned halt point is **commit 5** (SqlDelight on wasmJs), and it is gated by exhausting reasonable self-fix attempts first. Before halting at commit 5, you must have tried, at minimum:

1. Updating SqlDelight to the latest stable (and, if needed, a recent RC/snapshot) and re-running generation.
2. Adjusting the SqlDelight DSL (`generateAsync = true`, dialect choice, `treatNullAsUnknownForEquality`, schema output dir) to see if a different config produces wasmJs-compatible output.
3. Inspecting the generated sources directly and identifying the specific symbols that fail to compile on wasmJs — if the failures are isolated (e.g., a single `expect`/`actual` gap or an import that needs a wasmJs-compatible substitute), patch around them in `webMain` rather than blocking on upstream.
4. Confirming whether the failure is generation-time vs. compile-time vs. link-time, and searching the SqlDelight issue tracker for an existing issue or workaround.
5. Trying a minimal reproducer outside the integration module to rule out project-specific config interference.

Only after all of the above have been attempted and the blocker is genuinely upstream (not solvable within this repo) should you halt and surface the issue. Otherwise, proceed.

## Locked-in decisions

These were resolved up-front so execution proceeds without interruption:

1. **JS support: Option B (wasmJs-only).** `:opfs-driver` stays as-is; downstream `webTest` source sets only depend on it for wasmJs. The `js()` test task will be configured but contains zero tests and will pass trivially.
2. **Karma config: shared via root `kotlin-js-store/`.** It already exists with a `package-lock.json`; do not introduce per-module Karma config.
3. **CI runner cost: accepted.** ~2 minutes added across the three modules is fine; no separate parallel job needed unless the budget gets renegotiated later.
4. **Concurrency-mode tests on web: skipped.** Web only supports `SingleReaderWriter`; the multi-mode matrix in `AndroidxSqliteConcurrencyIntegrationTest` stays in `nonWebTest` only. No `expect fun supportedConcurrencyModels()` parameterization.

---

## Commit 1 — Refactor: extract test driver factory and make `deleteFile` suspend

**Scope:** No web targets touched yet. Pure refactor that paves the way.

- In `:integration` `commonTest`, introduce `expect fun testSqliteDriver(): SQLiteDriver`.
- In `:coroutines-extensions` `commonTest`, do the same (or share via a tiny test-helpers source set if practical).
- Actualize in existing `nonWebTest` source sets to return `BundledSQLiteDriver()`.
- Replace direct `BundledSQLiteDriver()` constructions in `AndroidxSqliteIntegrationTest`, flow extension tests, etc. with `testSqliteDriver()`.
- Convert `expect fun deleteFile(name: String)` → `expect suspend fun deleteFile(name: String)`. Audit and propagate `suspend` through callers (the `AfterTest cleanup` paths, `AndroidxSqliteEphemeralTest.withDatabase`, etc.). For `@AfterTest`, wrap deletes in `runTest { ... }` or similar — match the existing pattern in test files.
- Update existing jvm/android/native actuals (still synchronous — just declared `actual suspend`).

**Verification:** `./format`, then `./format --no-format`, then `./gradlew detektAll allTests` — must remain green. No new tests, just refactor.

---

## Commit 2 — Hierarchy: add `web` group to `:integration` and `:coroutines-extensions`

**Scope:** Build-script-only change. No source changes yet.

- In `integration/build.gradle.kts` and `coroutines-extensions/build.gradle.kts`, extend `applyDefaultHierarchyTemplate`:
  ```kotlin
  common {
    group("nonWeb") { ... }
    group("web") {
      withJs()
      withWasmJs()
    }
  }
  ```
- Add `js()` and `wasmJs { browser { testTask { useKarma { useChromeHeadless() } } } }` targets to both modules (matching `:library`'s setup).
- Confirm both modules' `webMain` source sets compile (empty or not).
- Don't add a `webTest` source set yet — let it stay empty so existing tests still cover everything.

**Verification:** `./format`, then `./format --no-format`, then `./gradlew detektAll allTests` — green. `./gradlew :integration:compileKotlinWasmJs :coroutines-extensions:compileKotlinWasmJs` should also succeed.

---

## Commit 3 — `:opfs-driver`: confirm wasmJs-only is sufficient (no-op commit)

**Scope:** Per the locked-in decision (Option B — wasmJs-only), `:opfs-driver` requires no changes. This slot exists in the sequence so the commit numbering matches the original plan; **skip it and fold any tiny adjustments into commit 4**.

If you reach this slot and decide nothing needs to change in `:opfs-driver`, skip directly to commit 4 — do not author an empty commit.

**Verification:** `./format`, then `./format --no-format`, then `./gradlew :opfs-driver:build detektAll allTests` — green (only run if anything was actually touched).

---

## Commit 4 — `:library` web tests (driver-level, prerequisite for downstream)

**Scope:** Land web tests in `:library` first because it's the simplest and exposes Karma/OPFS plumbing issues without dragging SqlDelight into the picture.

- Add `library/src/webTest/kotlin/.../` files that mirror the existing `nonWebTest` shape. Tests should:
  - Use the OPFS driver (or in-memory `AndroidxSqliteDatabaseType.Memory` for tests that don't need files).
  - Exercise basic execute/query/transaction paths.
- Add `webTest` source set deps in `library/build.gradle.kts`:
  ```kotlin
  named("webTest").dependencies {
    implementation(projects.opfsDriver)
    implementation(libs.kotlinx.browser)
    implementation(npm("@sqlite.org/sqlite-wasm", libs.versions.sqliteWasm.get()))
  }
  ```
- Implement `actual suspend fun deleteFile(name: String)` for `webTest` using `navigator.storage.getDirectory().removeEntry(...)`.
- Add a global OPFS-cleanup hook (clears the OPFS root after the suite).

**Verification:**
- `./gradlew :library:wasmJsTest` — green. (`:library:jsTest` runs but contains no web-specific tests per Option B.)
- `./format`, then `./format --no-format`, then `./gradlew detektAll allTests` — green.

If SqlDelight isn't needed at the library level, this commit is a pure environment validation: it proves Karma + OPFS + the driver work end-to-end before we layer SqlDelight on top.

---

## Commit 5 — `:integration` web tests

**Scope:** This is where SqlDelight on wasmJs gets validated.

- Verify SqlDelight code generation works for wasmJs: `./gradlew :integration:generateCommonMainAndroidXDbInterface` and inspect output. **This is the only sanctioned halting point in the plan**, and it is gated: only halt after exhausting the self-fix attempts enumerated in the **Execution mode** section (SqlDelight upgrade, DSL adjustments, targeted patches in `webMain`, generated-source inspection, minimal reproducer). If all of those fail and the blocker is genuinely upstream, stop and surface it. Otherwise, proceed.
- Promote `AndroidxSqliteIntegrationTest`, `AndroidxSqliteCommonIntegrationTests`, and any other platform-agnostic test scaffolding from `nonWebTest` to `commonTest`. The `BundledSQLiteDriver()` reference is already abstracted (commit 1), so this should be a clean move.
- `AndroidxSqliteConcurrencyIntegrationTest` stays in `nonWebTest` only (per locked-in decision #4 — multi-mode matrix is not parameterized on web).
- Add `webTest` deps in `integration/build.gradle.kts`:
  ```kotlin
  named("webTest").dependencies {
    implementation(projects.opfsDriver)
    implementation(libs.kotlinx.browser)
    implementation(npm("@sqlite.org/sqlite-wasm", libs.versions.sqliteWasm.get()))
  }
  ```
- Actualize `testSqliteDriver()` for `webTest` to return the OPFS driver.
- Actualize `deleteFile()` for `webTest` against OPFS.

**Verification:**
- `./gradlew :integration:wasmJsTest` — green. (`:integration:jsTest` runs trivially per Option B.)
- `./format`, then `./format --no-format`, then `./gradlew detektAll allTests` — green.

---

## Commit 6 — `:coroutines-extensions` web tests

**Scope:** Smaller than commit 5; pure flow-driven tests.

- Promote `CommonFlowExtensionsTests` and `FlowExtensionsTest` (the parts that don't already require platform-specific bits) from `nonWebTest` to `commonTest`.
- Add `webTest` deps mirroring commit 5.
- Actualize `testSqliteDriver()` and `deleteFile()` for `webTest`.
- Confirm flow-collection tests work under Karma's event loop (sometimes `runTest` + browser dispatcher needs `StandardTestDispatcher` rather than `UnconfinedTestDispatcher` — adjust if a test hangs).

**Verification:**
- `./gradlew :coroutines-extensions:wasmJsTest` — green.
- `./format`, then `./format --no-format`, then `./gradlew detektAll allTests` — green.

---

## Commit 7 — CI wiring

**Scope:** Make CI run the new web tests so future regressions are caught.

- Update `.github/workflows/` (or whatever CI definition exists) so the test step runs `./gradlew allTests`. If it currently filters out web targets, remove the filter.
- Add a Gradle cache for `kotlin-js-store/**/package-lock.json` if not already present.
- Verify the runner image has headless Chrome (Linux runners do; Windows/Mac may need scoping).
- If web tests are flaky in CI, add `useKarma { useChromeHeadlessNoSandbox() }` and consider `--info` logging on failure.

**Verification:**
- Push the branch; observe CI run completes with web tests executed and passing.
- Locally: `./format`, then `./format --no-format`, then `./gradlew detektAll allTests` — green (no change from local perspective, but final sanity check).

---

## Commit 8 — Docs & polish

**Scope:** Anything user-facing.

- Update `README.md` to mention web target test coverage.
- Note Chrome-headless requirement for local development.
- Optionally: update the OPFS cleanup helper if it's reusable for downstream consumers.

**Verification:** `./format`, then `./format --no-format`, then `./gradlew detektAll allTests` — green.

---

## Rollback notes

Each commit is independent enough to revert in isolation. The riskiest commit is **commit 5** (SqlDelight on wasmJs); if it gets stuck, commits 1–4 still leave the repo in a strictly better state than before, and commit 6 can technically land independent of commit 5 (coroutines-extensions doesn't use SqlDelight).

## Open decisions before starting

All four decisions have been resolved — see the **Locked-in decisions** section at the top. There are no open questions; execute the plan top-to-bottom.
