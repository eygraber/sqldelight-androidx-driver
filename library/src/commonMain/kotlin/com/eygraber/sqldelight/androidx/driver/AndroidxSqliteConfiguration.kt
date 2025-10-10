package com.eygraber.sqldelight.androidx.driver

import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter

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

/**
 * A configuration for an [AndroidxSqliteDriver].
 *
 * @param cacheSize The maximum size of the prepared statement cache for each database connection. Defaults to 25.
 * @param isForeignKeyConstraintsEnabled Whether foreign key constraints are enabled. Defaults to `false`.
 * @param isForeignKeyConstraintsCheckedAfterCreateOrUpdate When true, a `PRAGMA foreign_key_check` is performed
 * after the schema is created or migrated. This is only useful when [isForeignKeyConstraintsEnabled] is true.
 *
 * During schema creation and migration, foreign key constraints are temporarily disabled.
 * This check ensures that after the schema operations are complete, all foreign key constraints are satisfied.
 * If any violations are found, a [AndroidxSqliteDriver.ForeignKeyConstraintCheckException]
 * is thrown with details about the violations.
 *
 * Default is true.
 * @param maxMigrationForeignKeyConstraintViolationsToReport The maximum number of foreign
 * key constraint violations to report when [isForeignKeyConstraintsCheckedAfterCreateOrUpdate] is `true`
 * and `PRAGMA foreign_key_check` fails.
 *
 * Defaults to 100.
 * @param journalMode The journal mode to use. Defaults to [SqliteJournalMode.WAL].
 * @param sync The synchronous mode to use. Defaults to [SqliteSync.Full] unless [journalMode]
 * is set to [SqliteJournalMode.WAL] in which case it is [SqliteSync.Normal].
 * @param concurrencyModel The max amount of read connections that will be kept in the [ConnectionPool].
 * Defaults to 4 when [journalMode] is [SqliteJournalMode.WAL], otherwise 0 (since reads are blocked by writes).
 * The default for [SqliteJournalMode.WAL] may be changed in the future to be based on how many CPUs are available.
 * This value is ignored for [androidx.sqlite.SQLiteDriver] implementations that provide their own connection pool.
 */
public class AndroidxSqliteConfiguration(
  public val cacheSize: Int = 25,
  public val isForeignKeyConstraintsEnabled: Boolean = false,
  public val isForeignKeyConstraintsCheckedAfterCreateOrUpdate: Boolean = true,
  public val maxMigrationForeignKeyConstraintViolationsToReport: Int = 100,
  public val journalMode: SqliteJournalMode = SqliteJournalMode.WAL,
  public val sync: SqliteSync = when(journalMode) {
    SqliteJournalMode.WAL -> SqliteSync.Normal
    SqliteJournalMode.Delete,
    SqliteJournalMode.Truncate,
    SqliteJournalMode.Persist,
    SqliteJournalMode.Memory,
    SqliteJournalMode.Off,
    -> SqliteSync.Full
  },
  public val concurrencyModel: AndroidxSqliteConcurrencyModel = MultipleReadersSingleWriter(
    isWal = journalMode == SqliteJournalMode.WAL,
  ),
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
      concurrencyModel = concurrencyModel,
      maxMigrationForeignKeyConstraintViolationsToReport = maxMigrationForeignKeyConstraintViolationsToReport,
    )
}
