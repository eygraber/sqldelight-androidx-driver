package com.eygraber.sqldelight.androidx.driver.integration

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.coroutines.asFlow
import com.eygraber.sqldelight.androidx.driver.coroutines.mapToOne
import com.eygraber.sqldelight.androidx.driver.coroutines.mapToOneNotNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidxSqliteConcurrencyIntegrationTest : AndroidxSqliteIntegrationTest() {
  override var type: AndroidxSqliteDatabaseType =
    AndroidxSqliteDatabaseType.File("concurrency_integration-${Random.nextULong()}.db")

  // having 2 readers instead of the default 4 makes it more
  // likely to have concurrent readers using the same cached statement
  override fun createConfiguration() =
    AndroidxSqliteConfiguration(
      concurrencyModel = MultipleReadersSingleWriter(
        isWal = true,
        walCount = 2,
      ),
    )

  @Test
  fun concurrentQueriesWithMultipleReadersDoNotShareCachedStatementsAcrossConnections() = runTest {
    val insertCount = 50
    coroutineScope {
      val deleteJob = launch {
        database
          .recordQueries
          .top()
          .asFlow()
          .mapToOneNotNull()
          .distinctUntilChangedBy { it.id }
          .collectLatest { top ->
            database.withTransaction {
              database
                .recordQueries
                .delete(whereId = top.id)
            }
          }
      }

      val concurrentTopObserverJob = launch {
        database
          .recordQueries
          .top()
          .asFlow()
          .mapToOneNotNull()
          .distinctUntilChangedBy { it.id }
          .collect()
      }

      val insertJob = launch {
        repeat(insertCount) {
          database.withTransaction {
            database
              .recordQueries
              .insert(
                userId = "1",
                withRecord = Random.nextBytes(1_024),
              )
          }
        }
      }

      insertJob.join()

      // Wait for the deleter to drain everything the inserter put in.
      database
        .recordQueries
        .countForUser(whereUserId = "1")
        .asFlow()
        .mapToOne()
        .firstOrNull { it == 0L }

      deleteJob.cancel()
      concurrentTopObserverJob.cancel()
    }

    // Once all inserts are done and the drain observer sees 0, the deleter must have removed
    // every inserted row without crashing on a shared/stale cached statement across reader
    // connections.
    val remaining = database.recordQueries.countForUser(whereUserId = "1").awaitAsOne()
    assertEquals(
      0L,
      remaining,
      "concurrent reader connections must not share cached statements — every inserted row should have been deleted",
    )
  }
}
