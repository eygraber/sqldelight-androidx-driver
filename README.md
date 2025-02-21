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
  implementation("com.eygraber:sqldelight-androidx-driver:0.0.4")
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
    type = AndroidxSqliteDatabaseType.File(context, "my.db"),
    schema = Database.Schema,
  )
)
```

If you want to provide `OpenFlags` to the bundled or native driver, you can use:

```kotlin
Database(
  AndroidxSqliteDriver(
    createConnection = { name ->
      BundledSQLiteDriver().open(name, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)
    },
    type = AndroidxSqliteDatabaseType.File("<absolute path to db file>"),
    schema = Database.Schema,
  )
)
```

It will handle calling the `create` and `migrate` functions on your schema for you, and keep track of the database's version.

[AndroidX Kotlin Multiplatform SQLite]: https://developer.android.com/kotlin/multiplatform/sqlite
[SQLDelight]: https://github.com/sqldelight/sqldelight
