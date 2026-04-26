# SQLDelight AndroidX Driver

[![Maven Central](https://img.shields.io/maven-central/v/com.eygraber/sqldelight-androidx-driver)](https://github.com/eygraber/sqldelight-androidx-driver/releases)

The SQLDelight AndroidX Driver provides a [SQLDelight] driver that wraps [AndroidX Kotlin Multiplatform SQLite] libraries. It works with any of the available implementations of AndroidX SQLite.

* [SQLDelight docs]
* [Set up SQLite for KMP]

## Migrating from the pre-async version of the driver

> [!NOTE]  
> If you are migrating from version 0.0.17 or earlier, check out the [migration guide](./PRE_ASYNC_MIGRATION_GUIDE.md)

## Getting Started

### 1. Add Sqlite Dependencies

First setup SQLite dependencies following [Set up SQLite for KMP].

### 2. Add AndroidX Driver Dependency

Next add the dependency to your project.

```kotlin
repositories {
  mavenCentral()
}

// Android/JVM
dependencies {
  implementation("com.eygraber:sqldelight-androidx-driver:0.1.1")
}

// Multiplatform
commonMain.dependencies {
  implementation("com.eygraber:sqldelight-androidx-driver:0.1.1")
}
```

See [Consuming Via Gradle] for how to add a -SNAPSHOT release.

### 3. Configure Database

Next configure the database.

> [!IMPORTANT]
> `generateAsync` must equal `true` since `AndroidxSqliteDriver` is a suspending driver.

```kotlin
sqldelight {
  databases {
    create("Database") {
      generateAsync = true
    }
  }
}
```

## Usage

> [!TIP]
> AndroidX team recommends `BundledSQLiteDriver` but you can use whichever one suits your needs. See [SQLite Driver Implementations](https://developer.android.com/kotlin/multiplatform/sqlite#sqlite-driver-implementations) for more info.

### Create the Driver and Database

Android or JVM - Pass a `File`:

```kotlin
val driver = AndroidxSqliteDriver(
  driver = BundledSQLiteDriver(),
  databaseType = AndroidxSqliteDatabaseType.File(File("my.db")),
  schema = Database.Schema,
)
val database = Database(driver)

// More database stuff...
```

Android - Use `Context` to create the file in the app's database directory:

```kotlin
val driver = AndroidxSqliteDriver(
  driver = BundledSQLiteDriver(),
  databaseType = AndroidxSqliteDatabaseType.FileProvider(context, "my.db"),
  schema = Database.Schema,
)
val database = Database(driver)

// More database stuff...
```

Multiplatform - Create a database type factory:

```kotlin
// src/commonMain/kotlin
expect class DatabaseTypeFactory {
  fun createDatabaseType(): AndroidxSqliteDatabaseType
}

fun createDatabase(databaseTypeFactory: DatabaseTypeFactory): Database {
  val driver = AndroidxSqliteDriver(
    driver = BundledSQLiteDriver(),
    databaseType = databaseTypeFactory.createDatabaseType(),
    schema = Database.Schema
  )
  val database = Database(driver)
  
  // More database stuff...
}

// src/androidMain/kotlin
actual class DatabaseTypeFactory(private val context: Context) {
  actual fun createDatabaseType(): AndroidxSqliteDatabaseType {
    return AndroidxSqliteDatabaseType.FileProvider(context, "my.db")
  }
}

// src/nativeMain/kotlin
actual class DatabaseTypeFactory {
  actual fun createDatabaseType(): AndroidxSqliteDatabaseType {
    return AndroidxSqliteDatabaseType.File("<absolute path to db file>")
  }
}

// src/jvmMain/kotlin
actual class DatabaseTypeFactory {
  actual fun createDatabaseType(): AndroidxSqliteDatabaseType {
    AndroidxSqliteDatabaseType.File(File("my.db"))
  }
}
```

### Provide OpenFlags

If you want to provide `OpenFlags` to the bundled or native driver, you can use:

```kotlin
Database(
  AndroidxSqliteDriver(
    connectionFactory = object : AndroidxSqliteConnectionFactory {
      override val driver = BundledSQLiteDriver()
      
      override fun createConnection(name: String) =
        driver.open(name, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)
    },
    databaseType = AndroidxSqliteDatabaseType.File("<absolute path to db file>"),
    schema = Database.Schema,
  )
)
```

It will handle calling the `create` and `migrate` functions on your schema for you, and keep track of the database's version.

## Suspending Driver

`AndroidxSqliteDriver` is a suspending driver, so SQLDelight must be configured with
`generateAsync = true`:

```kotlin
sqldelight {
  databases {
    create("Database") {
      generateAsync = true
    }
  }
}
```

With `generateAsync = true`, generated queries return `QueryResult.AsyncValue` rather than
materialized values — you must call `.await()` (or the `awaitAsOne`/`awaitAsList`/etc. helpers
from `app.cash.sqldelight:async-extensions`) to get the result:

```kotlin
val user: User = database.userQueries.selectById(id).awaitAsOne()
val users: List<User> = database.userQueries.selectAll().awaitAsList()
```

All database calls are suspending, and the driver runs them on its own coroutine dispatchers —
you don't need to wrap calls in `withContext(Dispatchers.IO)`. Learn more [below](#dispatchers).

### Lifecycle Callbacks

`AndroidxSqliteDriver` accepts four optional callbacks, all invoked at most once on the first
interaction with the database:

```kotlin
AndroidxSqliteDriver(
  driver = BundledSQLiteDriver(),
  databaseType = AndroidxSqliteDatabaseType.File("my.db"),
  schema = Database.Schema,
  onConfigure = {
    // Suspending. Runs before create/migrate. Use it to override pragmas that aren't
    // covered by AndroidxSqliteConfiguration.
    setForeignKeyConstraintsEnabled(true)
  },
  onCreate = { /* side effects after first create */ },
  onUpdate = { old, new -> /* side effects after migration */ },
  onOpen = { /* side effects after create or migrate */ },
)
```

All four callbacks run at most once per driver instance, on the first database interaction,
guarded by an internal mutex — concurrent first-time callers all wait for them to complete before
any queries execute. If you close the driver and construct a new one against the same database,
`onConfigure` and `onOpen` run again for the new instance; `onCreate` and `onUpdate` only run if
the schema actually needs to be created or migrated.

- `onConfigure` runs first, before any schema work. It's the only suspending callback and is the
  right place to override pragmas that aren't covered by `AndroidxSqliteConfiguration`.
- `onCreate` runs only when the database is created for the first time, after `SqlSchema.create`
  has committed.
- `onUpdate` runs only when the schema version has increased, after `SqlSchema.migrate` has
  committed.
- `onOpen` runs on every first interaction, after any create/migrate work.

To seed data or run additional SQL during create or migrate, put it in your
`SqlSchema.create` / `SqlSchema.migrate` — those run inside a driver-managed transaction.
`onCreate`, `onUpdate`, and `onOpen` are non-suspending and meant for things like logging or
updating in-memory state.

### Flow Extensions

A companion artifact provides `Flow` extensions that mirror
[`app.cash.sqldelight:coroutines-extensions`](https://github.com/sqldelight/sqldelight/blob/master/extensions/coroutines-extensions/src/commonMain/kotlin/app/cash/sqldelight/coroutines/FlowExtensions.kt),
but default the `CoroutineContext` parameter to `EmptyCoroutineContext` — the driver already
dispatches each query onto its own connection pool, so wrapping every mapper in a second
`withContext(Dispatchers.IO)` is redundant.

```kotlin
dependencies {
  implementation("com.eygraber:sqldelight-coroutines-extensions:0.1.0")
}
```

```kotlin
import com.eygraber.sqldelight.androidx.driver.coroutines.asFlow
import com.eygraber.sqldelight.androidx.driver.coroutines.mapToList
import com.eygraber.sqldelight.androidx.driver.coroutines.mapToOne
import com.eygraber.sqldelight.androidx.driver.coroutines.mapToOneOrNull

val usersFlow: Flow<List<User>> = database.userQueries.selectAll().asFlow().mapToList()
val userFlow: Flow<User?> = database.userQueries.selectById(id).asFlow().mapToOneOrNull()
```

You can still pass an explicit `CoroutineContext` if you want the mapper to run somewhere
specific (e.g. `Dispatchers.Main` for UI state). If you prefer the sqldelight extensions,
they still work — just import from `app.cash.sqldelight.coroutines` instead.

## Foreign Key Constraints

When using `AndroidxSqliteDriver`, the handling of foreign key constraints during database creation and migration is
managed to ensure data integrity.

If you have foreign key constraints enabled in your
`AndroidxSqliteConfiguration` (i.e. `isForeignKeyConstraintsEnabled = true`),
the driver will automatically disable them before executing the schema `create` or `migrate` operations.
This is done to prevent issues with table creation order and data manipulation during the migration process.

After the creation or migration is complete, foreign key constraints are re-enabled.

Furthermore, to verify the integrity of the foreign key relationships after these operations,
the driver performs an additional check. If `isForeignKeyConstraintsCheckedAfterCreateOrUpdate`
is `true` (which it is by default), a `PRAGMA foreign_key_check` is executed. If this check finds
any violations, an `AndroidxSqliteDriver.ForeignKeyConstraintCheckException` is thrown, detailing the
specific constraints that have been violated. This helps catch any inconsistencies in your data that might
have been introduced during the migration.

> [!IMPORTANT]  
> By default, the first 100 violations will be parsed out of the result set of
> `PRAGMA foreign_key_check` and stored in the `AndroidxSqliteDriver.ForeignKeyConstraintCheckException`.
> If your use can result in a large number of violations you can adjust the max amount that will be processed via
> `AndroidxSqliteConfiguration.maxMigrationForeignKeyConstraintViolationsToReport`.

## Connection Pooling

SQLite supports several concurrency models that can significantly impact your application's performance. This driver
provides flexible connection pooling through the `AndroidxSqliteConcurrencyModel` interface.

### Available Concurrency Models

#### 1. SingleReaderWriter

The simplest model with one connection handling all operations:

```kotlin
AndroidxSqliteConfiguration(
  concurrencyModel = AndroidxSqliteConcurrencyModel.SingleReaderWriter()
)
```

**Best for:**

- Simple applications with minimal database usage
- Testing and development
- When memory usage is a primary concern
- Single-threaded applications

#### 2. MultipleReaders

Dedicated reader connections for read-only access:

```kotlin
AndroidxSqliteConfiguration(
  concurrencyModel = AndroidxSqliteConcurrencyModel.MultipleReaders(
    readerCount = 3  // Number of concurrent reader connections
  )
)
```

**Best for:**

- Read-only applications (analytics dashboards, reporting tools)
- Data visualization and content browsing applications
- Scenarios where all writes happen externally (data imports, ETL processes)
- Applications that only query pre-populated databases

**Important:** This model is designed for **read-only access**. No write operations (INSERT, UPDATE, DELETE) should be
performed. If you need write capabilities, use `MultipleReadersSingleWriter` in WAL mode instead.

#### 3. MultipleReadersSingleWriter (Recommended)

The most flexible model that adapts based on journal mode:

```kotlin
AndroidxSqliteConfiguration(
  concurrencyModel = AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter(
    isWal = true,        // Enable WAL mode for true concurrency
    walCount = 3,        // Reader connections when WAL is enabled (default: 3)
    nonWalCount = 0,     // Reader connections when WAL is disabled (default: 0)
  )
)
```

**Best for:**

- Most production applications
- Mixed read/write workloads
- When you want to leverage WAL mode benefits
- Applications requiring optimal performance

### WAL Mode Benefits

- **True Concurrency**: Readers and writers don't block each other
- **Better Performance**: Concurrent operations improve throughput
- **Consistency**: ACID properties are maintained (when `PRAGMA synchronous = FULL` is used)
- **Scalability**: Handles higher concurrent load

### Choosing Reader Connection Count

The optimal number of reader connections depends on your use case:

```kotlin
// Conservative (default)
AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter(
  isWal = true,
  walCount = 3,
  nonWalCount = 0,
)

// High-concurrency applications
AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter(
  isWal = true, 
  walCount = 8
)

// Memory-conscious applications
AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter(
  isWal = true,
  walCount = 2
)
```

### Platform-Specific Configuration

On Android, you can use system-determined connection pool sizes:

```kotlin
// Based on SQLiteGlobal.getWALConnectionPoolSize()
fun getWALConnectionPoolSize(): Int {
  val resources = Resources.getSystem()
  val resId = resources.getIdentifier("db_connection_pool_size", "integer", "android")
  return if (resId != 0) {
    resources.getInteger(resId)
  } else {
    2  // Fallback default
  }
}

AndroidxSqliteConfiguration(
  concurrencyModel = AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter(
    isWal = true,
    walCount = getWALConnectionPoolSize(),
    nonWalCount = 0,
  )
)
```

### Performance Considerations

| Model                                 | Memory Usage | Read Concurrency | Write Capability | Best Use Case      |
|---------------------------------------|--------------|------------------|------------------|--------------------|
| SingleReaderWriter                    | Lowest       | None             | Full             | Simple apps        |
| MultipleReaders                       | Medium       | Excellent        | None (read-only) | Read-only apps     |
| MultipleReadersSingleWriter (WAL)     | Higher       | Excellent        | Full             | Production         |
| MultipleReadersSingleWriter (non-WAL) | Medium       | Limited          | Full             | Legacy/constrained |

### Special Database Types

> [!NOTE]  
> In-Memory and temporary databases automatically use `SingleReaderWriter` model regardless of configuration, as
> connection pooling provides no benefit for these database types.

## Dispatchers

The driver runs SQLite work on its own `CoroutineDispatcher`, sized to match the concurrency model
so a writer and each reader get their own slot. You don't need to use `withContext(Dispatchers.IO)`
when calling the driver — queries and transactions will suspend onto the driver's dispatcher
automatically, and switch back to your calling context when they return. Inside a transaction,
every operation stays on the same slot for the lifetime of the transaction, so you don't have to
worry about switching connections mid-transaction.

You pick how those threads are sourced by passing a `dispatcherProvider` when constructing the
concurrency model. Two are bundled:

```kotlin
// Default. Shares threads with Dispatchers.IO via limitedParallelism.
// Lower memory — no dedicated threads are created for the driver.
AndroidxSqliteConcurrencyModel.memoryOptimizedProvider()

// Allocates a dedicated thread pool (via newFixedThreadPoolContext).
// Each connection tends to stay on the same thread, which helps CPU cache locality
// at the cost of extra OS threads.
AndroidxSqliteConcurrencyModel.CpuCacheHitOptimizedProvider
```

`memoryOptimizedProvider()` also accepts a base dispatcher if you'd rather derive parallelism from
somewhere other than `Dispatchers.IO`:

```kotlin
AndroidxSqliteConcurrencyModel.memoryOptimizedProvider(
  dispatcher = myBackgroundDispatcher,
)
```

The provider plugs into any concurrency model:

```kotlin
// Single connection, single dispatcher slot
AndroidxSqliteConcurrencyModel.SingleReaderWriter(
  dispatcherProvider = AndroidxSqliteConcurrencyModel.CpuCacheHitOptimizedProvider,
)

// Multiple readers (read-only), one slot per reader
AndroidxSqliteConcurrencyModel.MultipleReaders(
  readerCount = 3,
  dispatcherProvider = AndroidxSqliteConcurrencyModel.CpuCacheHitOptimizedProvider,
)

// Multiple readers + single writer
AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter(
  isWal = true,
  walCount = 3,
  dispatcherProvider = AndroidxSqliteConcurrencyModel.CpuCacheHitOptimizedProvider,
)
```

`driver.close()` disposes the dispatcher, so you generally don't need to manage its lifetime.

### Journal Mode

If `PRAGMA journal_mode = ...` is executed through the driver, the connection pool will:

1. Block new reader acquisitions and wait for in-flight readers to be returned
2. Close the returned reader connections
3. Acquire the writer connection
4. Run the `PRAGMA` statement
5. If the `MultipleReadersSingleWriter` model is in use, flip its `isWal` flag based on the
   journal mode the statement returned
6. Recreate the reader connections (sized from the updated concurrency model)
7. Release the writer and wake any parked reader acquisitions

This ensures all connections use the same journal mode and prevents inconsistencies.

### Best Practices

1. **Start with defaults**: Uses `MultipleReadersSingleWriter` in WAL mode
2. **Monitor performance**: Profile your specific workload to determine optimal reader count
3. **Consider memory**: Each connection has overhead - balance performance vs memory usage
4. **Test thoroughly**: Verify your concurrency model works under expected load
5. **Platform differences**: Android may have different optimal settings than JVM/Native

For additional background on WAL mode and dispatcher tuning, see [WAL & Dispatchers].

[AndroidX Kotlin Multiplatform SQLite]: https://developer.android.com/kotlin/multiplatform/sqlite
[SQLDelight]: https://github.com/sqldelight/sqldelight
[WAL & Dispatchers]: https://blog.p-y.wtf/parallelism-with-android-sqlite#heading-wal-amp-dispatchers
[Write-Ahead Logging]: https://sqlite.org/wal.html
[SQLDelight docs]: https://sqldelight.github.io/sqldelight/latest/
[Set up SQLite for KMP]: https://developer.android.com/kotlin/multiplatform/sqlite
[Consuming Via Gradle]: https://central.sonatype.org/publish/publish-portal-snapshots/#consuming-via-gradle