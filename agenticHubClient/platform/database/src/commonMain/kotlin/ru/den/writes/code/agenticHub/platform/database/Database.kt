package ru.den.writes.code.agenticHub.platform.database

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Platform-specific acquisition of the Room [AppDatabase] builder. The builder
 * differs per target — JVM/iOS take a file path, Android takes a `Context` —
 * so only this step is `expect`/`actual`; everything else ([buildDatabase]) is
 * common.
 */
internal expect fun databaseBuilder(): RoomDatabase.Builder<AppDatabase>

/**
 * Open the session-history database: platform builder + the multiplatform
 * bundled SQLite driver + WAL journal + the hand-written v1→v4 migrations.
 *
 * WAL lets parallel processes open the same file safely (one writer + many
 * readers, no blocking); with the `session_id` discriminator distinct sessions
 * touch disjoint rows. The migrations (`MIGRATION_1_2`/`_2_3`/`_3_4`) upgrade
 * older DB files in place — without them, opening a pre-v4 DB throws.
 */
public fun buildDatabase(): AppDatabase =
    databaseBuilder()
        .setDriver(BundledSQLiteDriver())
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()
