package com.eygraber.sqldelight.androidx.driver.integration

import app.cash.sqldelight.coroutines.asFlow
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test

class AndroidxSqliteConcurrencyIntegrationTest : AndroidxSqliteIntegrationTest() {
  override var type: AndroidxSqliteDatabaseType = AndroidxSqliteDatabaseType.File("concurrency_integration.db")

  @Test
  fun concurrentQueriesWithMultipleReadersDoNotShareCachedStatementsAcrossConnections() = runTest {
    // having 2 readers instead of the default 4 makes it more
    // likely to have concurrent readers using the same cached statement
    configuration = AndroidxSqliteConfiguration(
      readerConnectionsCount = 2,
    )

    launch {
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

      launch {
        delay(1000)
        database
          .recordQueries
          .countForUser(
            whereUserId = "1",
          )
          .asFlow()
          .mapToOne()
          .firstOrNull {
            it == 0L
          }

        deleteJob.cancel()
        concurrentTopObserverJob.cancel()
      }

      launch {
        repeat(50) {
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
    }
  }
}
