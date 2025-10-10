package com.eygraber.sqldelight.androidx.driver

internal object AndroidxSqliteUtils {
  fun findSpecialCase(sql: String): AndroidxSqliteSpecialCase? {
    val prefixIndex = getStatementPrefixIndex(sql)
    val prefix = getStatementPrefix(prefixIndex, sql) ?: return null

    return if(sql.length - prefixIndex >= 6 && prefix.isPragma()) {
      val postKeyword = sql.substring(prefixIndex + 6).dropWhile { !it.isLetter() }.lowercase()

      when {
        postKeyword.startsWith("journal_mode") -> when {
          "=" in postKeyword.substringAfter("journal_mode") -> AndroidxSqliteSpecialCase.SetJournalMode
          else -> null
        }
        postKeyword.startsWith("foreign_keys") -> AndroidxSqliteSpecialCase.ForeignKeys
        postKeyword.startsWith("synchronous") -> AndroidxSqliteSpecialCase.Synchronous
        else -> null
      }
    }
    else {
      null
    }
  }

  fun String.isPragma() = with(this) {
    when(get(0)) {
      'P', 'p' -> when(get(1)) {
        'R', 'r' -> when(get(2)) {
          'A', 'a' -> true
          else -> false
        }

        else -> false
      }

      else -> false
    }
  }

  /**
   * Taken from SupportSQLiteStatement.android.kt
   */
  fun getStatementPrefix(
    index: Int,
    sql: String,
  ): String? {
    if (index < 0 || index > sql.length) {
      // Bad comment syntax or incomplete statement
      return null
    }
    return sql.substring(index, minOf(index + 3, sql.length))
  }

  /**
   * Return the index of the first character past comments and whitespace.
   *
   * Taken from SQLiteDatabase.getSqlStatementPrefixOffset() implementation.
   */
  @Suppress("ReturnCount")
  fun getStatementPrefixIndex(s: String): Int {
    val limit: Int = s.length - 2
    if (limit < 0) return -1
    var i = 0
    while (i < limit) {
      val c = s[i]
      when {
        c <= ' ' -> i++
        c == '-' -> {
          if (s[i + 1] != '-') return i
          i = s.indexOf('\n', i + 2)
          if (i < 0) return -1
          i++
        }
        c == '/' -> {
          if (s[i + 1] != '*') return i
          i++
          do {
            i = s.indexOf('*', i + 1)
            if (i < 0) return -1
          } while (i + 1 < limit && s[i + 1] != '/')
          i += 2
        }
        else -> return i
      }
    }
    return -1
  }
}
