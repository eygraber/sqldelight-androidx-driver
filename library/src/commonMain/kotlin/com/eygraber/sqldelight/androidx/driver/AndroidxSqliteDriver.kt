package com.eygraber.sqldelight.androidx.driver

import androidx.collection.LruCache
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal expect class TransactionsThreadLocal() {
  internal fun get(): Transacter.Transaction?
  internal fun set(transaction: Transacter.Transaction?)
}

internal const val DEFAULT_CACHE_SIZE = 20

/**
 * @param databaseType Specifies the type of the database file
 * (see [Sqlite open documentation](https://www.sqlite.org/c3ref/open.html)).
 *
 * @see AndroidxSqliteDriver
 * @see SqlSchema.create
 * @see SqlSchema.migrate
 */
public class AndroidxSqliteDriver(
  createConnection: (String) -> SQLiteConnection,
  databaseType: AndroidxSqliteDatabaseType,
  private val schema: SqlSchema<QueryResult.Value<Unit>>,
  readerConnections: Int = 0,
  private val cacheSize: Int = DEFAULT_CACHE_SIZE,
  private val migrateEmptySchema: Boolean = false,
  private val onConfigure: ConfigurableDatabase.() -> Unit = {},
  private val onCreate: SqlDriver.() -> Unit = {},
  private val onUpdate: SqlDriver.(Long, Long) -> Unit = { _, _ -> },
  private val onOpen: SqlDriver.() -> Unit = {},
  vararg migrationCallbacks: AfterVersion,
) : SqlDriver {
  public constructor(
    driver: SQLiteDriver,
    databaseType: AndroidxSqliteDatabaseType,
    schema: SqlSchema<QueryResult.Value<Unit>>,
    readerConnections: Int = 0,
    cacheSize: Int = DEFAULT_CACHE_SIZE,
    migrateEmptySchema: Boolean = false,
    onConfigure: ConfigurableDatabase.() -> Unit = {},
    onCreate: SqlDriver.() -> Unit = {},
    onUpdate: SqlDriver.(Long, Long) -> Unit = { _, _ -> },
    onOpen: SqlDriver.() -> Unit = {},
    vararg migrationCallbacks: AfterVersion,
  ) : this(
    createConnection = driver::open,
    databaseType = databaseType,
    schema = schema,
    readerConnections = readerConnections,
    cacheSize = cacheSize,
    migrateEmptySchema = migrateEmptySchema,
    onConfigure = onConfigure,
    onCreate = onCreate,
    onUpdate = onUpdate,
    onOpen = onOpen,
    migrationCallbacks = migrationCallbacks,
  )

  @Suppress("NonBooleanPropertyPrefixedWithIs")
  private val isFirstInteraction = atomic(true)

  private val connectionPool by lazy {
    ConnectionPool(
      createConnection = createConnection,
      name = when(databaseType) {
        is AndroidxSqliteDatabaseType.File -> databaseType.databaseFilePath
        AndroidxSqliteDatabaseType.Memory -> ":memory:"
        AndroidxSqliteDatabaseType.Temporary -> ""
      },
      maxReaders = when(databaseType) {
        is AndroidxSqliteDatabaseType.File -> readerConnections
        AndroidxSqliteDatabaseType.Memory -> 0
        AndroidxSqliteDatabaseType.Temporary -> 0
      },
    )
  }

  private val transactions = TransactionsThreadLocal()

  private val statementsCaches = mutableMapOf<SQLiteConnection, LruCache<Int, AndroidxStatement>>()

  private fun getStatementsCache(connection: SQLiteConnection): LruCache<Int, AndroidxStatement> =
    statementsCaches.getOrPut(
      connection,
    ) {
      object : LruCache<Int, AndroidxStatement>(cacheSize) {
        override fun entryRemoved(
          evicted: Boolean,
          key: Int,
          oldValue: AndroidxStatement,
          newValue: AndroidxStatement?,
        ) {
          if(evicted) oldValue.close()
        }
      }
    }

  private var skipStatementsCache = true

  private val listenersLock = SynchronizedObject()
  private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()

  private val migrationCallbacks = migrationCallbacks

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

  override fun newTransaction(): QueryResult<Transacter.Transaction> {
    createOrMigrateIfNeeded()

    val enclosing = transactions.get()
    val transactionConnection = when(enclosing) {
      null -> connectionPool.acquireWriterConnection()
      else -> (enclosing as Transaction).connection
    }
    val transaction = Transaction(enclosing, transactionConnection)
    transactions.set(transaction)

    if(enclosing == null) {
      transactionConnection.execSQL("BEGIN IMMEDIATE")
    }

    return QueryResult.Value(transaction)
  }

  override fun currentTransaction(): Transacter.Transaction? = transactions.get()

  private inner class Transaction(
    override val enclosingTransaction: Transacter.Transaction?,
    val connection: SQLiteConnection,
  ) : Transacter.Transaction() {
    override fun endTransaction(successful: Boolean): QueryResult<Unit> {
      if(enclosingTransaction == null) {
        try {
          if(successful) {
            connection.execSQL("COMMIT")
          } else {
            connection.execSQL("ROLLBACK")
          }
        } finally {
          connectionPool.releaseWriterConnection()
        }
      }
      transactions.set(enclosingTransaction)
      return QueryResult.Unit
    }
  }

  private fun <T> execute(
    identifier: Int?,
    connection: SQLiteConnection,
    createStatement: (SQLiteConnection) -> AndroidxStatement,
    binders: (SqlPreparedStatement.() -> Unit)?,
    result: AndroidxStatement.() -> T,
  ): QueryResult.Value<T> {
    val statementsCache = if(!skipStatementsCache) getStatementsCache(connection) else null
    var statement: AndroidxStatement? = null
    if(identifier != null && statementsCache != null) {
      statement = statementsCache[identifier]

      // remove temporarily from the cache
      if(statement != null) {
        statementsCache.remove(identifier)
      }
    }
    if(statement == null) {
      statement = createStatement(connection)
    }
    try {
      if(binders != null) {
        statement.binders()
      }
      return QueryResult.Value(statement.result())
    } finally {
      if(identifier != null && !skipStatementsCache) {
        statementsCache?.put(identifier, statement)?.close()
        statement.reset()
      } else {
        statement.close()
      }
    }
  }

  override fun execute(
    identifier: Int?,
    sql: String,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult<Long> {
    createOrMigrateIfNeeded()

    val transaction = currentTransaction()
    if(transaction == null) {
      val writerConnection = connectionPool.acquireWriterConnection()
      try {
        return execute(
          identifier = identifier,
          connection = writerConnection,
          createStatement = { AndroidxPreparedStatement(it.prepare(sql)) },
          binders = binders,
          result = { execute() },
        )
      } finally {
        connectionPool.releaseWriterConnection()
      }
    } else {
      val connection = (transaction as Transaction).connection
      return execute(
        identifier = identifier,
        connection = connection,
        createStatement = { AndroidxPreparedStatement(it.prepare(sql)) },
        binders = binders,
        result = { execute() },
      )
    }
  }

  override fun <R> executeQuery(
    identifier: Int?,
    sql: String,
    mapper: (SqlCursor) -> QueryResult<R>,
    parameters: Int,
    binders: (SqlPreparedStatement.() -> Unit)?,
  ): QueryResult.Value<R> {
    createOrMigrateIfNeeded()

    val transaction = currentTransaction()
    if(transaction == null) {
      val connection = connectionPool.acquireReaderConnection()
      try {
        return execute(
          identifier = identifier,
          connection = connection,
          createStatement = { AndroidxQuery(sql, it, parameters) },
          binders = binders,
          result = { executeQuery(mapper) },
        )
      } finally {
        connectionPool.releaseReaderConnection(connection)
      }
    } else {
      val connection = (transaction as Transaction).connection
      return execute(
        identifier = identifier,
        connection = connection,
        createStatement = { AndroidxQuery(sql, it, parameters) },
        binders = binders,
        result = { executeQuery(mapper) },
      )
    }
  }

  override fun close() {
    statementsCaches.forEach { (_, cache) ->
      cache.snapshot().values.forEach { it.close() }
      cache.evictAll()
    }
    statementsCaches.clear()
    connectionPool.close()
  }

  private val createOrMigrateLock = SynchronizedObject()
  private var isNestedUnderCreateOrMigrate = false
  private fun createOrMigrateIfNeeded() {
    if(isFirstInteraction.value) {
      synchronized(createOrMigrateLock) {
        if(isFirstInteraction.value && !isNestedUnderCreateOrMigrate) {
          isNestedUnderCreateOrMigrate = true

          ConfigurableDatabase(this).onConfigure()

          val writerConnection = connectionPool.acquireWriterConnection()
          val currentVersion = try {
            writerConnection.prepare("PRAGMA user_version").use { getVersion ->
              when {
                getVersion.step() -> getVersion.getLong(0)
                else -> 0
              }
            }
          } finally {
            connectionPool.releaseWriterConnection()
          }

          if(currentVersion == 0L && !migrateEmptySchema || currentVersion < schema.version) {
            val driver = this
            val transacter = object : TransacterImpl(driver) {}

            transacter.transaction {
              when(currentVersion) {
                0L -> schema.create(driver).value
                else -> schema.migrate(driver, currentVersion, schema.version, *migrationCallbacks).value
              }
              skipStatementsCache = false
              when(currentVersion) {
                0L -> onCreate()
                else -> onUpdate(currentVersion, schema.version)
              }
              writerConnection.prepare("PRAGMA user_version = ${schema.version}").use { it.step() }
            }
          } else {
            skipStatementsCache = false
          }

          onOpen()

          isFirstInteraction.value = false
        }
      }
    }
  }
}

internal interface AndroidxStatement : SqlPreparedStatement {
  fun execute(): Long
  fun <R> executeQuery(mapper: (SqlCursor) -> QueryResult<R>): R
  fun reset()
  fun close()
}

private class AndroidxPreparedStatement(
  private val statement: SQLiteStatement,
) : AndroidxStatement {
  override fun bindBytes(index: Int, bytes: ByteArray?) {
    if(bytes == null) statement.bindNull(index + 1) else statement.bindBlob(index + 1, bytes)
  }

  override fun bindLong(index: Int, long: Long?) {
    if(long == null) statement.bindNull(index + 1) else statement.bindLong(index + 1, long)
  }

  override fun bindDouble(index: Int, double: Double?) {
    if(double == null) statement.bindNull(index + 1) else statement.bindDouble(index + 1, double)
  }

  override fun bindString(index: Int, string: String?) {
    if(string == null) statement.bindNull(index + 1) else statement.bindText(index + 1, string)
  }

  override fun bindBoolean(index: Int, boolean: Boolean?) {
    if(boolean == null) {
      statement.bindNull(index + 1)
    } else {
      statement.bindLong(index + 1, if(boolean) 1L else 0L)
    }
  }

  override fun <R> executeQuery(mapper: (SqlCursor) -> QueryResult<R>): R =
    throw UnsupportedOperationException()

  override fun execute(): Long {
    var cont = true
    while(cont) {
      cont = statement.step()
    }
    return statement.getColumnCount().toLong()
  }

  override fun reset() {
    statement.reset()
  }

  override fun close() {
    statement.close()
  }
}

private class AndroidxQuery(
  private val sql: String,
  private val connection: SQLiteConnection,
  argCount: Int,
) : AndroidxStatement {
  private val binds = MutableList<((SQLiteStatement) -> Unit)?>(argCount) { null }

  override fun bindBytes(index: Int, bytes: ByteArray?) {
    binds[index] = { if(bytes == null) it.bindNull(index + 1) else it.bindBlob(index + 1, bytes) }
  }

  override fun bindLong(index: Int, long: Long?) {
    binds[index] = { if(long == null) it.bindNull(index + 1) else it.bindLong(index + 1, long) }
  }

  override fun bindDouble(index: Int, double: Double?) {
    binds[index] =
      { if(double == null) it.bindNull(index + 1) else it.bindDouble(index + 1, double) }
  }

  override fun bindString(index: Int, string: String?) {
    binds[index] =
      { if(string == null) it.bindNull(index + 1) else it.bindText(index + 1, string) }
  }

  override fun bindBoolean(index: Int, boolean: Boolean?) {
    binds[index] = { statement ->
      if(boolean == null) {
        statement.bindNull(index + 1)
      } else {
        statement.bindLong(index + 1, if(boolean) 1L else 0L)
      }
    }
  }

  override fun execute() = throw UnsupportedOperationException()

  override fun <R> executeQuery(mapper: (SqlCursor) -> QueryResult<R>): R {
    val statement = connection.prepare(sql)
    for(action in binds) {
      requireNotNull(action).invoke(statement)
    }

    return try {
      mapper(AndroidxCursor(statement)).value
    } finally {
      statement.close()
    }
  }

  override fun toString() = sql

  override fun reset() {}

  override fun close() {}
}

private class AndroidxCursor(
  private val statement: SQLiteStatement,
) : SqlCursor {

  override fun next(): QueryResult.Value<Boolean> = QueryResult.Value(statement.step())
  override fun getString(index: Int) =
    if(statement.isNull(index)) null else statement.getText(index)

  override fun getLong(index: Int) = if(statement.isNull(index)) null else statement.getLong(index)
  override fun getBytes(index: Int) =
    if(statement.isNull(index)) null else statement.getBlob(index)

  override fun getDouble(index: Int) =
    if(statement.isNull(index)) null else statement.getDouble(index)

  override fun getBoolean(index: Int) =
    if(statement.isNull(index)) null else statement.getLong(index) == 1L
}
