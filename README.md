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
  implementation("com.eygraber:sqldelight-androidx-driver:0.0.3")
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

the easiest way to get started is to use the `AndroidxSqliteDriver` factory which will manage migrations for you:

```kotlin
Database(
  AndroidxSqliteDriver(
    driver = BundledSQLiteDriver(),
    type = AndroidxSqliteDatabaseType.File("my.db"),
    schema = Database.Schema,
  )
)
```

If you want to create and configure driver yourself you can construct the `AndroidxSqliteDriver` directly.

[AndroidX Kotlin Multiplatform SQLite]: https://developer.android.com/kotlin/multiplatform/sqlite
[SQLDelight]: https://github.com/sqldelight/sqldelight
