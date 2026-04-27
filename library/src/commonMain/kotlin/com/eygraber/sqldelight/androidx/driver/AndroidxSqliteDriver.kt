package com.eygraber.sqldelight.androidx.driver

import androidx.annotation.VisibleForTesting
import androidx.annotation.VisibleForTesting.Companion.PRIVATE
import androidx.collection.LruCache
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import app.cash.sqldelight.Query
import app.cash.sqldelight.SuspendingTransacter
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.locks.withLock

/**
 * @param databaseType Specifies the type of the database file
 * (see [Sqlite open documentation](https://www.sqlite.org/c3ref/open.html)).
 *
 * @see AndroidxSqliteDriver
 * @see SqlSchema.create
 * @see SqlSchema.migrate
 */
public class AndroidxSqliteDriver @VisibleForTesting(otherwise = PRIVATE) internal constructor(
  connectionFactory: AndroidxSqliteConnectionFactory,
  databaseType: AndroidxSqliteDatabaseType,
  private val schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
  configuration: AndroidxSqliteConfiguration = AndroidxSqliteConfiguration(),
  private val migrateEmptySchema: Boolean = false,
  /**
   * A callback to configure the database connection when it's first opened.
   *
   * This lambda is invoked on the first interaction with the database, immediately before the schema
   * is created or migrated. It provides an [AndroidxSqliteConfigurableDriver] as its receiver
   * to allow for safe configuration of connection properties like journal mode or foreign key
   * constraints.
   *
   * **Warning:** The [AndroidxSqliteConfigurableDriver] receiver is ephemeral and **must not** escape the callback.
   */
  private val onConfigure: suspend AndroidxSqliteConfigurableDriver.() -> Unit = {},
  /**
   * A callback invoked when the database is created for the first time.
   *
   * This lambda is invoked after the schema has been created but before `onOpen` is called.
   *
   * **Warning:** The [SqlDriver] receiver **must not** escape the callback.
   */
  private val onCreate: suspend SqlDriver.() -> Unit = {},
  /**
   * A callback invoked when the database is upgraded.
   *
   * This lambda is invoked after the schema has been migrated but before `onOpen` is called.
   *
   * **Warning:** The [SqlDriver] receiver **must not** escape the callback.
   */
  private val onUpdate: suspend SqlDriver.(Long, Long) -> Unit = { _, _ -> },
  /**
   * A callback invoked when the database has been opened.
   *
   * This lambda is invoked after the schema has been created or migrated.
   *
   * **Warning:** The [SqlDriver] receiver **must not** escape the callback.
   */
  private val onOpen: suspend SqlDriver.() -> Unit = {},
  overridingConnectionPool: ConnectionPool? = null,
  vararg migrationCallbacks: AndroidxSqliteAfterVersion,
) : SqlDriver, SuspendingTransacter.TransactionDispatcher {
  private val statementsCache = HashMap<SQLiteConnection, LruCache<Int, AndroidxStatement>>()
  private val statementsCacheLock = ReentrantLock()

  private val connectionPool by lazy {
    val nameProvider = when(databaseType) {
      is AndroidxSqliteDatabaseType.File -> databaseType::databaseFilePath

      is AndroidxSqliteDatabaseType.FileProvider -> databaseType.databaseFilePathProvider

      AndroidxSqliteDatabaseType.Memory -> {
        { ":memory:" }
      }

      AndroidxSqliteDatabaseType.Temporary -> {
        { "" }
      }
    }

    overridingConnectionPool ?: when {
      connectionFactory.driver.hasConnectionPool ->
        createPassthroughConnectionPool(
          connectionFactory = connectionFactory,
          nameProvider = nameProvider,
          configuration = configuration,
        )

      else ->
        createDefaultConnectionPool(
          connectionFactory = connectionFactory,
          nameProvider = nameProvider,
          isFileBased = when(databaseType) {
            is AndroidxSqliteDatabaseType.File -> true
            is AndroidxSqliteDatabaseType.FileProvider -> true
            AndroidxSqliteDatabaseType.Memory -> false
            AndroidxSqliteDatabaseType.Temporary -> false
          },
          configuration = configuration,
          onConnectionClosed = { connection ->
            statementsCacheLock.withLock {
              statementsCache.remove(connection)
            }?.evictAll()
          },
        )
    }
  }

  private val listenersLock = SynchronizedObject()
  private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()

  private val executingDriverHolder by lazy {
    @Suppress("ktlint:standard:max-line-length")
    AndroidxSqliteDriverHolder(
      connectionPool = this.connectionPool,
      statementCache = statementsCache,
      statementCacheLock = statementsCacheLock,
      statementCacheSize = configuration.cacheSize,
      schema = schema,
      isForeignKeyConstraintsEnabled = configuration.isForeignKeyConstraintsEnabled,
      isForeignKeyConstraintsCheckedAfterCreateOrUpdate = configuration.isForeignKeyConstraintsCheckedAfterCreateOrUpdate,
      maxMigrationForeignKeyConstraintViolationsToReport = configuration.maxMigrationForeignKeyConstraintViolationsToReport,
      migrateEmptySchema = migrateEmptySchema,
      onConfigure = onConfigure,
      onCreate = onCreate,
      onUpdate = onUpdate,
      onOpen = onOpen,
      migrationCallbacks = migrationCallbacks,
    )
  }

  public constructor(
    connectionFactory: AndroidxSqliteConnectionFactory,
    databaseType: AndroidxSqliteDatabaseType,
    schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
    configuration: AndroidxSqliteConfiguration = AndroidxSqliteConfiguration(),
    migrateEmptySchema: Boolean = false,
    /**
     * A callback to configure the database connection when it's first opened.
     *
     * This lambda is invoked on the first interaction with the database, immediately before the schema
     * is created or migrated. It provides an [AndroidxSqliteConfigurableDriver] as its receiver
     * to allow for safe configuration of connection properties like journal mode or foreign key
     * constraints.
     *
     * **Warning:** The [AndroidxSqliteConfigurableDriver] receiver is ephemeral and **must not** escape the callback.
     */
    onConfigure: suspend AndroidxSqliteConfigurableDriver.() -> Unit = {},
    /**
     * A callback invoked when the database is created for the first time.
     *
     * This lambda is invoked after the schema has been created but before `onOpen` is called.
     *
     * **Warning:** The [SqlDriver] receiver **must not** escape the callback.
     */
    onCreate: suspend SqlDriver.() -> Unit = {},
    /**
     * A callback invoked when the database is upgraded.
     *
     * This lambda is invoked after the schema has been migrated but before `onOpen` is called.
     *
     * **Warning:** The [SqlDriver] receiver **must not** escape the callback.
     */
    onUpdate: suspend SqlDriver.(Long, Long) -> Unit = { _, _ -> },
    /**
     * A callback invoked when the database has been opened.
     *
     * This lambda is invoked after the schema has been created or migrated.
     *
     * **Warning:** The [SqlDriver] receiver **must not** escape the callback.
     */
    onOpen: suspend SqlDriver.() -> Unit = {},
    vararg migrationCallbacks: AndroidxSqliteAfterVersion,
  ) : this(
    connectionFactory = connectionFactory,
    databaseType = databaseType,
    schema = schema,
    configuration = configuration,
    migrateEmptySchema = migrateEmptySchema,
    onConfigure = onConfigure,
    onCreate = onCreate,
    onUpdate = onUpdate,
    onOpen = onOpen,
    overridingConnectionPool = null,
    migrationCallbacks = migrationCallbacks,
  )

  public constructor(
    driver: SQLiteDriver,
    databaseType: AndroidxSqliteDatabaseType,
    schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
    configuration: AndroidxSqliteConfiguration = AndroidxSqliteConfiguration(),
    migrateEmptySchema: Boolean = false,
    /**
     * A callback to configure the database connection when it's first opened.
     *
     * This lambda is invoked on the first interaction with the database, immediately before the schema
     * is created or migrated. It provides an [AndroidxSqliteConfigurableDriver] as its receiver
     * to allow for safe configuration of connection properties like journal mode or foreign key
     * constraints.
     *
     * **Warning:** The [AndroidxSqliteConfigurableDriver] receiver is ephemeral and **must not** escape the callback.
     */
    onConfigure: suspend AndroidxSqliteConfigurableDriver.() -> Unit = {},
    /**
     * A callback invoked when the database is created for the first time.
     *
     * This lambda is invoked after the schema has been created but before `onOpen` is called.
     *
     * **Warning:** The [SqlDriver] receiver **must not** escape the callback.
     */
    onCreate: suspend SqlDriver.() -> Unit = {},
    /**
     * A callback invoked when the database is upgraded.
     *
     * This lambda is invoked after the schema has been migrated but before `onOpen` is called.
     *
     * **Warning:** The [SqlDriver] receiver **must not** escape the callback.
     */
    onUpdate: suspend SqlDriver.(Long, Long) -> Unit = { _, _ -> },
    /**
     * A callback invoked when the database has been opened.
     *
     * This lambda is invoked after the schema has been created or migrated.
     *
     * **Warning:** The [SqlDriver] receiver **must not** escape the callback.
     */
    onOpen: suspend SqlDriver.() -> Unit = {},
    vararg migrationCallbacks: AndroidxSqliteAfterVersion,
  ) : this(
    connectionFactory = DefaultAndroidxSqliteConnectionFactory(driver),
    databaseType = databaseType,
    schema = schema,
    configuration = configuration,
    migrateEmptySchema = migrateEmptySchema,
    onConfigure = onConfigure,
    onCreate = onCreate,
    onUpdate = onUpdate,
    onOpen = onOpen,
    overridingConnectionPool = null,
    migrationCallbacks = migrationCallbacks,
  )

  override suspend fun <R> dispatch(transaction: suspend () -> R): R =
    executingDriverHolder.ensureSchemaIsReady {
      dispatch(transaction)
    }

  override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
    synchronized(listenersLock) {
      queryKeys.forEach {
        listeners.getOrPut(it) { linkedSetOf() }.add(listener)
      }
    }
  }

  override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
    synchronized(listenersLock) {
      queryKeys.forEach {
        listeners[it]?.remove(listener)
      }
    }
  }

  override fun notifyListeners(vararg queryKeys: String) {
    val listenersToNotify = linkedSetOf<Query.Listener>()
    synchronized(listenersLock) {
      queryKeys.forEach { listeners[it]?.let(listenersToNotify::addAll) }
    }
    listenersToNotify.forEach(Query.Listener::queryResultsChanged)
  }

  override fun newTransaction(): QueryResult<Transacter.Transaction> =
    QueryResult.AsyncValue {
      executingDriverHolder.ensureSchemaIsReady {
        newTransaction()
      }.await()
    }

  override fun currentTransaction(): Transacter.Transaction? =
    executingDriverHolder.currentTransaction()

  override fun execute(
    identifier: Int?,
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<Long> = QueryResult.AsyncValue {
    executingDriverHolder.ensureSchemaIsReady {
      execute(identifier, sql, parameters, binders)
    }.await()
  }

  override fun <R> executeQuery(
    identifier: Int?,
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<R> = QueryResult.AsyncValue {
    executingDriverHolder.ensureSchemaIsReady {
      executeQuery(identifier, sql, mapper, parameters, binders)
    }.await()
  }

  /**
   * Closes all connections in the pool and clears the statement cache.
   *
   * It is the caller's responsibility to ensure that no other coroutines
   * are using any of the connections starting from when close is invoked.
   *
   * An [IllegalStateException] may be thrown if there are coroutines using any of the connections.
   */
  override fun close() {
    statementsCacheLock.withLock {
      statementsCache.values.forEach { it.evictAll() }
      statementsCache.clear()
    }
    connectionPool.close()
  }

  public data class ForeignKeyConstraintViolation(
    val referencingTable: String,
    val referencingRowId: Int,
    val referencedTable: String,
    val referencingConstraintIndex: Int,
  ) {
    override fun toString(): String =
      """
      |ForeignKeyConstraintViolation:
      |  Constraint index: $referencingConstraintIndex
      |  Referencing table: $referencingTable
      |  Referencing rowId: $referencingRowId
      |  Referenced table: $referencedTable
      """.trimMargin()
  }

  public class ForeignKeyConstraintCheckException(
    public val violations: List<ForeignKeyConstraintViolation>,
    message: String,
  ) : Exception(message)
}
