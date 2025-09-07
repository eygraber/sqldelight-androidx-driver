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
  public val isForeignKeyConstraintsEnabled: Boolean = false,
  /**
   * When true, a `PRAGMA foreign_key_check` is performed after the schema is created or migrated.
   *
   * This is only useful when [isForeignKeyConstraintsEnabled] is true.
   *
   * During schema creation and migration, foreign key constraints are temporarily disabled.
   * This check ensures that after the schema operations are complete, all foreign key constraints are satisfied.
   * If any violations are found, a [AndroidxSqliteDriver.ForeignKeyConstraintCheckException]
   * is thrown with details about the violations.
   *
   * Default is true.
   */
  public val isForeignKeyConstraintsCheckedAfterCreateOrUpdate: Boolean = true,
  /**
   * Journal mode to use.
   *
   * Default is [SqliteJournalMode.WAL].
   */
  public val journalMode: SqliteJournalMode = SqliteJournalMode.WAL,
  /**
   * Synchronous mode to use.
   *
   * Default is [SqliteSync.Full] unless [journalMode] is set to [SqliteJournalMode.WAL] in which case it is [SqliteSync.Normal].
   */
  public val sync: SqliteSync = when(journalMode) {
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
  /**
   * The maximum number of foreign key constraint violations to report when
   * [isForeignKeyConstraintsCheckedAfterCreateOrUpdate] is `true` and `PRAGMA foreign_key_check` fails.
   *
   * Defaults to 100.
   */
  public val maxMigrationForeignKeyConstraintViolationsToReport: Int = 100,
) {
  public fun copy(
    isForeignKeyConstraintsEnabled: Boolean = this.isForeignKeyConstraintsEnabled,
    journalMode: SqliteJournalMode = this.journalMode,
    sync: SqliteSync = this.sync,
  ): AndroidxSqliteConfiguration =
    AndroidxSqliteConfiguration(
      cacheSize = cacheSize,
      isForeignKeyConstraintsEnabled = isForeignKeyConstraintsEnabled,
      isForeignKeyConstraintsCheckedAfterCreateOrUpdate = isForeignKeyConstraintsCheckedAfterCreateOrUpdate,
      journalMode = journalMode,
      sync = sync,
      readerConnectionsCount = readerConnectionsCount,
      maxMigrationForeignKeyConstraintViolationsToReport = maxMigrationForeignKeyConstraintViolationsToReport,
    )
}
