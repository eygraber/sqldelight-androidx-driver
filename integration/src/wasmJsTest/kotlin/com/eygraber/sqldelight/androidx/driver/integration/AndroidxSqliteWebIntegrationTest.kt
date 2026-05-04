package com.eygraber.sqldelight.androidx.driver.integration

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidxSqliteWebIntegrationTest : AndroidxSqliteIntegrationTest() {
  override var type: AndroidxSqliteDatabaseType =
    AndroidxSqliteDatabaseType.File("integration-${Random.nextULong()}.db")

  @AfterTest
  fun terminateWorker() = runTest { terminateAndSettleTestWorkers() }

  @Test
  fun insertedRowsAreVisibleViaSqlDelightGeneratedQueries() = runTest {
    database.withTransaction {
      database.recordQueries.insert(
        userId = "user-1",
        withRecord = byteArrayOf(0x01, 0x02, 0x03),
      )
      database.recordQueries.insert(
        userId = "user-1",
        withRecord = byteArrayOf(0x04, 0x05, 0x06),
      )
    }

    val all = database.recordQueries.countForUser(whereUserId = "user-1").awaitAsOne()
    assertEquals(2L, all)

    val records = database.recordQueries.top().awaitAsList()
    assertEquals(1, records.size)
    assertEquals("user-1", records.first().userId)
  }
}
