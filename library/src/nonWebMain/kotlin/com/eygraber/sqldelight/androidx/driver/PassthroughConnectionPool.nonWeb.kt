package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

internal class PassthroughConnectionPool(
  private val connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  private val configuration: AndroidxSqliteConfiguration,
) : ConnectionPool {
  private val name by lazy { nameProvider() }

  // See note on AndroidxDriverConnectionPool.lazyWriterConnection — runBlocking here is purely
  // a type adapter for the suspend interface, never actually suspends on non-web.
  private val lazyDelegatedConnection = lazy {
    runBlocking {
      connectionFactory
        .createConnection(name)
        .withWriterConfiguration(configuration)
    }
  }
  private val delegatedConnection: SQLiteConnection get() = lazyDelegatedConnection.value

  override suspend fun <R> runOnDispatcher(block: suspend () -> R) = block()

  override suspend fun acquireWriterConnection() = delegatedConnection

  override suspend fun releaseWriterConnection() {}

  override suspend fun acquireReaderConnection() = delegatedConnection

  override suspend fun releaseReaderConnection(connection: SQLiteConnection) {}

  override suspend fun <R> setJournalMode(
    executeStatement: suspend (SQLiteConnection) -> R,
  ): R {
    val isForeignKeyConstraintsEnabled =
      delegatedConnection.prepare("PRAGMA foreign_keys;").use { statement ->
        statement.step()
        statement.getBoolean(0)
      }

    val queryResult = executeStatement(delegatedConnection)

    // PRAGMA journal_mode currently wipes out foreign_keys - https://issuetracker.google.com/issues/447613208
    val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
    delegatedConnection.executeSQL("PRAGMA foreign_keys = $foreignKeys;")

    return queryResult
  }

  override fun close() {
    if(lazyDelegatedConnection.isInitialized()) {
      delegatedConnection.close()
    }
  }
}

private suspend fun SQLiteConnection.withWriterConfiguration(
  configuration: AndroidxSqliteConfiguration,
): SQLiteConnection {
  try {
    configuration.apply {
      executeSQL("PRAGMA journal_mode = ${journalMode.value};")
      executeSQL("PRAGMA synchronous = ${sync.value};")

      // this must come after PRAGMA journal_mode while https://issuetracker.google.com/issues/447613208 is broken
      val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
      executeSQL("PRAGMA foreign_keys = $foreignKeys;")
    }
  }
  catch(c: CancellationException) {
    throw c
  }
  catch(t: Throwable) {
    try {
      close()
    }
    catch(closeFailure: Throwable) {
      t.addSuppressed(closeFailure)
    }
    throw t
  }
  return this
}
