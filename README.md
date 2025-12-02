# SqlDelight AndroidX Driver

`sqldelight-androidx-driver` provides a [SQLDelight] `SqlDriver` that wraps the [AndroidX Kotlin Multiplatform SQLite]
libraries.

It works with any of the available implementations of AndroidX SQLite; see their documentation for more information.

## Gradle

```kotlin
repositories {
  mavenCentral()
}

dependencies {
  implementation("com.eygraber:sqldelight-androidx-driver:0.0.17")
}
```

Snapshots can be found [here](https://central.sonatype.org/publish/publish-portal-snapshots/#consuming-via-gradle).

## Usage
Assuming the following configuration:

```kotlin
sqldelight {
  databases {
    create("Database")
  }
}
```

you get started by creating a `AndroidxSqliteDriver`:

```kotlin
Database(
  AndroidxSqliteDriver(
    driver = BundledSQLiteDriver(),
    type = AndroidxSqliteDatabaseType.File("<absolute path to db file>"),
    schema = Database.Schema,
  )
)
```

on Android and JVM you can pass a `File`:

```kotlin
Database(
  AndroidxSqliteDriver(
    driver = BundledSQLiteDriver(),
    type = AndroidxSqliteDatabaseType.File(File("my.db")),
    schema = Database.Schema,
  )
)
```

and on Android you can pass a `Context` to create the file in the app's database directory:

```kotlin
Database(
  AndroidxSqliteDriver(
    driver = BundledSQLiteDriver(),
    type = AndroidxSqliteDatabaseType.FileProvider(context, "my.db"),
    schema = Database.Schema,
  )
)
```

If you want to provide `OpenFlags` to the bundled or native driver, you can use:

```kotlin
Database(
  AndroidxSqliteDriver(
    connectionFactory = object : AndroidxConnectionFactory {
      override val driver = BundledSQLiteDriver()
      
      override fun createConnection(name: String) =
        driver.open(name, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)
    },
    type = AndroidxSqliteDatabaseType.File("<absolute path to db file>"),
    schema = Database.Schema,
  )
)
```

It will handle calling the `create` and `migrate` functions on your schema for you, and keep track of the database's version.

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
  concurrencyModel = AndroidxSqliteConcurrencyModel.SingleReaderWriter
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
    walCount = 4,        // Reader connections when WAL is enabled
    nonWalCount = 0      // Reader connections when WAL is disabled
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
  walCount = 4,
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

### Journal Mode

If `PRAGMA journal_mode = ...` is used, the connection pool will:

1. Acquire the writer connection
2. Acquire all reader connections
3. Close all reader connections
4. Run the `PRAGMA` statement
5. Recreate the reader connections

This ensures all connections use the same journal mode and prevents inconsistencies.

### Best Practices

1. **Start with defaults**: Uses `MultipleReadersSingleWriter` in WAL mode
2. **Monitor performance**: Profile your specific workload to determine optimal reader count
3. **Consider memory**: Each connection has overhead - balance performance vs memory usage
4. **Test thoroughly**: Verify your concurrency model works under expected load
5. **Platform differences**: Android may have different optimal settings than JVM/Native

See [WAL & Dispatchers] for more information about how to configure dispatchers to use for reads and writes.

[AndroidX Kotlin Multiplatform SQLite]: https://developer.android.com/kotlin/multiplatform/sqlite
[SQLDelight]: https://github.com/sqldelight/sqldelight
[WAL & Dispatchers]: https://blog.p-y.wtf/parallelism-with-android-sqlite#heading-wal-amp-dispatchers
[Write-Ahead Logging]: https://sqlite.org/wal.html
