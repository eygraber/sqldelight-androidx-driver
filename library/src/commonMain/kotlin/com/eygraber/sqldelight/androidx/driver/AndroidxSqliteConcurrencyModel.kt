package com.eygraber.sqldelight.androidx.driver

import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.Companion.CpuCacheHitOptimizedProvider
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.Companion.MemoryOptimizedProvider
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.newFixedThreadPoolContext

/**
 * Defines the concurrency model for SQLite database connections, controlling how many
 * reader and writer connections are maintained in the connection pool.
 *
 * SQLite supports different concurrency models depending on the journal mode and application needs:
 * - Single connection for simple use cases
 * - Multiple readers with WAL (Write-Ahead Logging) for better read concurrency
 * - Configurable reader counts for fine-tuned performance
 *
 * @property dispatcher The [CoroutineDispatcher] used for database operations.
 * The underlying thread will be blocked for the duration of any database operation
 * (including the entire transaction for explicit transactions).
 *
 * Defaults to the value provided by [MemoryOptimizedProvider], but if you want to optimize for CPU cache hits,
 * you can use [CpuCacheHitOptimizedProvider].
 * @property readerCount The number of reader connections to maintain in the pool
 */
public sealed interface AndroidxSqliteConcurrencyModel : AutoCloseable {
  public val dispatcher: CoroutineDispatcher
  public val readerCount: Int

  public companion object {
    public const val DISPATCHER_NAME: String = "AndroidxSqliteDriver"

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    public val CpuCacheHitOptimizedProvider: (Int, String) -> CoroutineDispatcher = { parallelism, name ->
      newFixedThreadPoolContext(
        nThreads = parallelism,
        name = name,
      )
    }

    public val MemoryOptimizedProvider: (Int, String) -> CoroutineDispatcher = { parallelism, name ->
      // MemoryOptimizedProvider is "injected" as dispatcherProvider so if
      // the user wants they can provide their own dispatcherProvider to control the dispatcher used
      @Suppress("InjectDispatcher")
      Dispatchers.IO.limitedParallelism(
        parallelism = parallelism,
        name = name,
      )
    }
  }

  /**
   * Single connection model - one connection handles both reads and writes.
   *
   * This is the simplest and most conservative approach, suitable for:
   * - Applications with low concurrency requirements
   * - Simple database operations
   * - Testing scenarios
   * - When database contention is not a concern
   *
   * **Performance characteristics:**
   * - Lowest memory overhead
   * - No connection pooling complexity
   * - Sequential read/write operations only
   * - Suitable for single-threaded or low-concurrency scenarios
   */
  public class SingleReaderWriter(
    dispatcherProvider: (Int, String) -> CoroutineDispatcher = MemoryOptimizedProvider,
  ) : AndroidxSqliteConcurrencyModel {
    override val readerCount: Int = 0
    override val dispatcher: CoroutineDispatcher = dispatcherProvider(1, DISPATCHER_NAME)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun close() {
      if(dispatcher is CloseableCoroutineDispatcher) {
        dispatcher.close()
      }
    }
  }

  /**
   * Multiple readers model - allows concurrent read operations only.
   *
   * This model creates a pool of dedicated reader connections for read-only access.
   * **No write operations should be performed** when using this model.
   *
   * **Use cases:**
   * - Read-only applications (analytics dashboards, reporting tools)
   * - Data visualization and content browsing applications
   * - Scenarios where all writes happen externally (e.g., data imports)
   * - Applications that only query pre-populated databases
   *
   * **Performance characteristics:**
   * - Excellent read concurrency
   * - Higher memory overhead due to connection pooling
   * - No write capability - reads only
   * - Optimal for read-heavy workloads with no database modifications
   *
   * **Important:** This model is designed for read-only access. If your application
   * needs to perform any write operations (INSERT, UPDATE, DELETE, schema changes),
   * use `MultipleReadersSingleWriter` in WAL mode instead.
   *
   * @param readerCount Number of reader connections to maintain (typically 2-8)
   */
  public class MultipleReaders(
    override val readerCount: Int,
    dispatcherProvider: (Int, String) -> CoroutineDispatcher = MemoryOptimizedProvider,
  ) : AndroidxSqliteConcurrencyModel {
    init {
      require(readerCount > 0) {
        "readerCount must be greater than 0"
      }
    }

    override val dispatcher: CoroutineDispatcher = dispatcherProvider(readerCount, DISPATCHER_NAME)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun close() {
      if(dispatcher is CloseableCoroutineDispatcher) {
        dispatcher.close()
      }
    }
  }

  /**
   * Multiple readers with single writer model - optimized for different journal modes.
   *
   * This is the most flexible model that adapts its behavior based on whether
   * Write-Ahead Logging (WAL) mode is enabled:
   *
   * **WAL Mode (isWal = true):**
   * - Enables true concurrent reads and writes
   * - Readers don't block writers and vice versa
   * - Best performance for mixed read/write workloads
   * - Uses `walCount` reader connections
   *
   * **Non-WAL Mode (isWal = false):**
   * - Falls back to traditional SQLite locking
   * - Reads and writes are still serialized
   * - Uses `nonWalCount` reader connections (typically 0)
   *
   * **Recommended configuration:**
   * ```kotlin
   * // For WAL mode
   * MultipleReadersSingleWriter(
   *   isWal = true,
   *   walCount = 3  // Good default for most applications
   * )
   *
   * // For non-WAL mode
   * MultipleReadersSingleWriter(
   *   isWal = false,
   *   nonWalCount = 0  // Single connection is often sufficient
   * )
   * ```
   *
   * @param isWal Whether WAL (Write-Ahead Logging) journal mode is enabled
   * @param nonWalCount Number of reader connections when WAL is disabled (default: 0)
   * @param walCount Number of reader connections when WAL is enabled (default: 3)
   */
  public data class MultipleReadersSingleWriter(
    public val isWal: Boolean,
    public val nonWalCount: Int = 0,
    public val walCount: Int = 3,
    public val dispatcherProvider: (Int, String) -> CoroutineDispatcher = MemoryOptimizedProvider,
  ) : AndroidxSqliteConcurrencyModel {
    override val readerCount: Int = when {
      isWal -> walCount
      else -> nonWalCount
    }

    override val dispatcher: CoroutineDispatcher = dispatcherProvider(readerCount + 1, DISPATCHER_NAME)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun close() {
      if(dispatcher is CloseableCoroutineDispatcher) {
        dispatcher.close()
      }
    }
  }
}
