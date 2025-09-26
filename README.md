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
  implementation("com.eygraber:sqldelight-androidx-driver:0.0.15")
}
```

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

By default, one connection will be used for both reading and writing, and only one thread can acquire that connection 
at a time. If you have WAL enabled, you could (and should) set the amount of pooled reader connections that will be used:

```kotlin
AndroidxSqliteDriver(
  ...,
  readerConnections = 4,
  ...,
)
```

On Android you can defer to the system to determine how many reader connections there should be<sup>[1]</sup>:

```kotlin
// Based on SQLiteGlobal.getWALConnectionPoolSize()
fun getWALConnectionPoolSize() {
  val resources = Resources.getSystem()
  val resId =
    resources.getIdentifier("db_connection_pool_size", "integer", "android")
  return if (resId != 0) {
    resources.getInteger(resId)
  } else {
    2
  }
}
```

See [WAL & Dispatchers] for more information about how to configure dispatchers to use for reads and writes.

> [!NOTE]  
> In-Memory and temporary databases will always use 0 reader connections i.e. there will be a single connection 

[1]: https://blog.p-y.wtf/parallelism-with-android-sqlite#heading-secondary-connections
[AndroidX Kotlin Multiplatform SQLite]: https://developer.android.com/kotlin/multiplatform/sqlite
[SQLDelight]: https://github.com/sqldelight/sqldelight
[WAL & Dispatchers]: https://blog.p-y.wtf/parallelism-with-android-sqlite#heading-wal-amp-dispatchers
