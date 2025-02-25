package com.eygraber.sqldelight.androidx.driver

import androidx.sqlite.SQLiteConnection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ConnectionPool(
  private val createConnection: (String) -> SQLiteConnection,
  private val name: String,
  private val maxReaders: Int = 4,
) {
  private val writerConnection: SQLiteConnection by lazy { createConnection(name) }
  private val writerMutex = Mutex()

  private val readerChannel = Channel<SQLiteConnection>(capacity = maxReaders)
  private val readerConnections = List(maxReaders) { lazy { createConnection(name) } }

  /**
   * Acquires the writer connection, blocking if it's currently in use.
   * @return The writer SQLiteConnection
   */
  fun acquireWriterConnection() = runBlocking {
    writerMutex.lock()
    writerConnection
  }

  /**
   * Releases the writer connection (mutex unlocks automatically).
   */
  fun releaseWriterConnection() {
    writerMutex.unlock()
  }

  /**
   * Acquires a reader connection, blocking if none are available.
   * @return A reader SQLiteConnection
   */
  fun acquireReaderConnection() = when(maxReaders) {
    0 -> acquireWriterConnection()
    else -> runBlocking {
      val firstUninitialized = readerConnections.firstOrNull { !it.isInitialized() }
      firstUninitialized?.value ?: readerChannel.receive()
    }
  }

  /**
   * Releases a reader connection back to the pool.
   * @param connection The SQLiteConnection to release
   */
  fun releaseReaderConnection(connection: SQLiteConnection) = when(maxReaders) {
    0 -> releaseWriterConnection()
    else -> runBlocking {
      readerChannel.send(connection)
    }
  }

  /**
   * Closes all connections in the pool.
   */
  fun close() {
    runBlocking {
      writerMutex.withLock {
        writerConnection.close()
      }
      readerConnections.forEach { reader ->
        if(reader.isInitialized()) reader.value.close()
      }
      readerChannel.close()
    }
  }
}
