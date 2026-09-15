package com.eygraber.sqldelight.androidx.driver

import kotlinx.coroutines.CoroutineDispatcher
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AndroidxSqliteConcurrencyModelBinaryCompatibilityTest {
  @Test
  fun cpuCacheHitOptimizedProviderIsACompanionMember() {
    val getter = AndroidxSqliteConcurrencyModel.Companion::class.java.getMethod("getCpuCacheHitOptimizedProvider")

    assertFalse(Modifier.isStatic(getter.modifiers))
    assertEquals(Function2::class.java, getter.returnType)

    @Suppress("UNCHECKED_CAST")
    val provider = getter.invoke(AndroidxSqliteConcurrencyModel) as (Int, String) -> CoroutineDispatcher

    val dispatcher = provider(1, "binary-compat")
    try {
      assertIs<CoroutineDispatcher>(dispatcher)
    }
    finally {
      (dispatcher as? AutoCloseable)?.close()
    }
  }
}
