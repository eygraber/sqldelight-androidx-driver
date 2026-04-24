# Migrating to `sqldelight-androidx-driver` 0.1.0

Version `0.1.0` makes the driver **suspending** and bakes in dispatcher management. You no longer need `withContext(Dispatchers.IO)` around database calls, but your generated query
code and a couple of APIs need to be updated.

## 1. Bump the dependency

Android / Jvm

```kotlin
dependencies {
  implementation("com.eygraber:sqldelight-androidx-driver:0.1.0")
}
```

KMP

```kotlin
commonMain.dependencies {
  implementation("com.eygraber:sqldelight-androidx-driver:0.1.0")
}
```

## 2. Enable async code generation in SQLDelight

This is the biggest change. Set `generateAsync = true` on your database:

  ```kotlin
  sqldelight {
    databases {
      create("Database") {
        generateAsync = true
      }
    }
  }
  ```

Without this, your project will not compile against `0.1.0`.

## 3. Update your query calls to `awaitAs...`

With `generateAsync = true`, every generated query returns a suspending result. You have to `.await()` it — the blocking `.executeAsOne()` / `.executeAsList()` helpers no longer
apply.

Add the SQLDelight async extensions artifact if you don't already have it:

  ```kotlin
  dependencies {
    implementation("app.cash.sqldelight:async-extensions:<sqldelight-version>")
  }
  ```

Then rename your call sites:

  ```kotlin
  // Before
  val user: User = database.userQueries.selectById(id).executeAsOne()
  val users: List<User> = database.userQueries.selectAll().executeAsList()
  val userOrNull: User? = database.userQueries.selectById(id).executeAsOneOrNull()

  // After
  val user: User = database.userQueries.selectById(id).awaitAsOne()
  val users: List<User> = database.userQueries.selectAll().awaitAsList()
  val userOrNull: User? = database.userQueries.selectById(id).awaitAsOneOrNull()
  ```

Every call site that used to return a value now suspends, so it must live inside a coroutine.

> [!NOTE]  
> Calls that use `SqlDriver.execute` (e.g `INSERT`, `UPDATE`, `DELETE`, etc...) become suspending and return a `Long`, and don't require calling `await()`
> Calls that use `SqlDriver.executeQuery` (e.g. `SELECT`, etc...) require calling `await()` (or the async extension `await*() functions) on the returned `Query<R>` 

## 4. Transactions are now suspending

Generated queries become a `SuspendingTransacter`. Use `transaction { ... }` the same way, but from a `suspend` function:

  ```kotlin
  // Before
  database.transaction {
    val user: User = database.userQueries.selectById(id).executeAsOne()
    database.userQueries.insert(user)
  }

  // After (inside a suspend function)
  database.transaction {
    val user: User = database.userQueries.selectById(id).awaitAsOne()
    database.userQueries.insert(user)
  }
  ```

## 5. Remove your `withContext(Dispatchers.IO)` wrappers

The driver now runs SQLite work on its own dispatcher automatically and switches back to your calling context when the call returns. If you were doing this:

  ```kotlin
  // Before
  withContext(Dispatchers.IO) {
    database.userQueries.selectAll().executeAsList()
  }
  ```

…simplify it to:

  ```kotlin
  // After
  database.userQueries.selectAll().awaitAsList()
  ```

## 6. `SingleReaderWriter` now takes parentheses

`SingleReaderWriter` changed from an object to a class. Add `()`:

  ```kotlin
  // Before
  AndroidxSqliteConfiguration(
    concurrencyModel = AndroidxSqliteConcurrencyModel.SingleReaderWriter,
  )

  // After
  AndroidxSqliteConfiguration(
    concurrencyModel = AndroidxSqliteConcurrencyModel.SingleReaderWriter(),
  )
  ```

## 7. `AndroidxConnectionFactory` was renamed

If you implemented a custom connection factory (for example, to pass `OpenFlags`), rename the interface:

  ```kotlin
  // Before
  object : AndroidxConnectionFactory { ... }

  // After
  object : AndroidxSqliteConnectionFactory { ... }
  ```

## 8. `driver.setJournalMode(...)` is gone — set it during configuration

The public `setJournalMode` function on the driver has been removed. There are two replacements:

**Option A — set it via configuration (preferred):**

  ```kotlin
  AndroidxSqliteConfiguration(
    journalMode = SqliteJournalMode.WAL,
  )
  ```

**Option B — set it from the `onConfigure` callback (which is now `suspend`):**

  ```kotlin
  AndroidxSqliteDriver(
    driver = BundledSQLiteDriver(),
    databaseType = AndroidxSqliteDatabaseType.File("my.db"),
    schema = Database.Schema,
    onConfigure = {
      // this: AndroidxSqliteConfigurableDriver — suspending
      setJournalMode(SqliteJournalMode.WAL)
    },
  )
  ```

If you previously called `driver.setJournalMode(...)` from application code after construction, move that call into `onConfigure` or the configuration.

## 9. `onConfigure` is now `suspend`

If you pass an `onConfigure` callback, it can now call suspending functions directly. If you were using `runBlocking { ... }` inside it, you can drop that.

  ```kotlin
  AndroidxSqliteDriver(
    // ...
    onConfigure = {
      setForeignKeyConstraintsEnabled(true) // suspending call, works directly
    },
  )
  ```

`onCreate`, `onUpdate`, and `onOpen` remain non-suspending — use them for logging or in-memory bookkeeping only. Any SQL you need during first-time creation or migration should be in
your `SqlSchema.create` / `SqlSchema.migrate`, which run inside a driver-managed transaction.

## 10. (Optional) Use the new Flow extensions artifact

If you were using `app.cash.sqldelight:coroutines-extensions`, it still works. There's now a companion artifact tuned for this driver — the mappers default to `EmptyCoroutineContext`
because the driver already dispatches to its own pool, so you avoid a redundant hop through `Dispatchers.IO`:

  ```kotlin
  dependencies {
    implementation("com.eygraber:sqldelight-coroutines-extensions:0.1.0")
  }
  ```

  ```kotlin
  import com.eygraber.sqldelight.androidx.driver.coroutines.asFlow
  import com.eygraber.sqldelight.androidx.driver.coroutines.mapToList
  import com.eygraber.sqldelight.androidx.driver.coroutines.mapToOneOrNull

  val usersFlow: Flow<List<User>> = database.userQueries.selectAll().asFlow().mapToList()
  val userFlow: Flow<User?> = database.userQueries.selectById(id).asFlow().mapToOneOrNull()
  ```

Switching is just a matter of changing the import — the API shape is the same as the SQLDelight one.

## 11. (Optional) Tune the dispatcher

The driver now owns its dispatcher and closes it for you on `driver.close()`. The default shares threads with `Dispatchers.IO` (low memory). If you want a dedicated pool (better CPU
cache locality, more threads), pass a provider to your concurrency model:

  ```kotlin
  AndroidxSqliteConfiguration(
    concurrencyModel = AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter(
      isWal = true,
      walCount = 3,
      dispatcherProvider = AndroidxSqliteConcurrencyModel.CpuCacheHitOptimizedProvider,
    ),
  )
  ```

No action is required if the defaults are fine.

  ---

## Quick checklist

- [ ] Bump to `0.1.0`
- [ ] Add `generateAsync = true` to your SQLDelight config
- [ ] Add `app.cash.sqldelight:async-extensions` if not already present
- [ ] Replace `executeAsOne` / `executeAsList` / `executeAsOneOrNull` with `awaitAs…`
- [ ] Move query calls into `suspend` functions; drop `withContext(Dispatchers.IO)`
- [ ] Add `()` to `SingleReaderWriter`
- [ ] Rename `AndroidxConnectionFactory` → `AndroidxSqliteConnectionFactory`
- [ ] Replace any `driver.setJournalMode(...)` call with configuration or `onConfigure`
