package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL
import androidx.sqlite.async.prepare
import androidx.sqlite.async.step
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

internal class PassthroughConnectionPool(
  private val connectionFactory: AndroidxSqliteConnectionFactory,
  nameProvider: () -> String,
  private val configuration: AndroidxSqliteConfiguration,
) : ConnectionPool {
  private val name by lazy { nameProvider() }

  private val connectionMutex = Mutex()

  @Volatile
  private var delegatedConnection: SQLiteConnection? = null

  private suspend fun delegatedConnection(): SQLiteConnection =
    delegatedConnection ?: connectionMutex.withLock {
      delegatedConnection ?: connectionFactory
        .createConnection(name)
        .withWriterConfiguration(configuration)
        .also { delegatedConnection = it }
    }

  override suspend fun <R> runOnDispatcher(block: suspend () -> R) = block()

  override suspend fun acquireWriterConnection() = delegatedConnection()

  override suspend fun releaseWriterConnection() {}

  override suspend fun acquireReaderConnection() = delegatedConnection()

  override suspend fun releaseReaderConnection(connection: SQLiteConnection) {}

  override suspend fun <R> setJournalMode(
    executeStatement: suspend (SQLiteConnection) -> R,
  ): R {
    val connection = delegatedConnection()
    val isForeignKeyConstraintsEnabled =
      connection.prepare("PRAGMA foreign_keys;").use { statement ->
        statement.step()
        statement.getBoolean(0)
      }

    val queryResult = executeStatement(connection)

    // PRAGMA journal_mode currently wipes out foreign_keys - https://issuetracker.google.com/issues/447613208
    val foreignKeys = if(isForeignKeyConstraintsEnabled) "ON" else "OFF"
    connection.executeSQL("PRAGMA foreign_keys = $foreignKeys;")

    return queryResult
  }

  override fun close() {
    delegatedConnection?.close()
    delegatedConnection = null
  }
}
