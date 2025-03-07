package com.eygraber.sqldelight.androidx.driver

/**
 * [sqlite.org journal_mode](https://www.sqlite.org/pragma.html#pragma_journal_mode)
 */
public enum class SqliteJournalMode(internal val value: String) {
  Delete("DELETE"),
  Truncate("TRUNCATE"),
  Persist("PERSIST"),
  Memory("MEMORY"),
  @Suppress("EnumNaming")
  WAL("WAL"),
  Off("OFF"),
}

/**
 * [sqlite.org synchronous](https://www.sqlite.org/pragma.html#pragma_synchronous)
 */
public enum class SqliteSync(internal val value: String) {
  Off("OFF"),
  Normal("NORMAL"),
  Full("FULL"),
  Extra("EXTRA"),
}

public class AndroidxSqliteConfiguration(
  /**
   * The maximum size of the prepared statement cache for each database connection.
   *
   * Default is 25.
   */
  public val cacheSize: Int = 25,
  /**
   * True if foreign key constraints are enabled.
   *
   * Default is false.
   */
  public var isForeignKeyConstraintsEnabled: Boolean = false,
  /**
   * Journal mode to use.
   *
   * Default is [SqliteJournalMode.WAL].
   */
  public var journalMode: SqliteJournalMode = SqliteJournalMode.WAL,
  /**
   * Synchronous mode to use.
   *
   * Default is [SqliteSync.Full] unless [journalMode] is set to [SqliteJournalMode.WAL] in which case it is [SqliteSync.Normal].
   */
  public var sync: SqliteSync = when(journalMode) {
    SqliteJournalMode.WAL -> SqliteSync.Normal
    SqliteJournalMode.Delete,
    SqliteJournalMode.Truncate,
    SqliteJournalMode.Persist,
    SqliteJournalMode.Memory,
    SqliteJournalMode.Off,
    -> SqliteSync.Full
  },
  /**
   * The max amount of read connections that will be kept in the [ConnectionPool].
   *
   * Defaults to 4 when [journalMode] is [SqliteJournalMode.WAL], otherwise 0 (since reads are blocked by writes).
   *
   * The default for [SqliteJournalMode.WAL] may be changed in the future to be based on how many CPUs are available.
   */
  public val readerConnectionsCount: Int = when(journalMode) {
    SqliteJournalMode.WAL -> 4
    else -> 0
  },
)
