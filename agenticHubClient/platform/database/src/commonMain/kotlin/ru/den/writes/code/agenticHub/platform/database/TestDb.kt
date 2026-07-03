package ru.den.writes.code.agenticHub.platform.database

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * One-shot Room database backed by a fresh temp file. Use inside
 * `.use { … }` so the file (plus the WAL/SHM siblings SQLite creates
 * alongside it) gets removed when the block exits.
 *
 * Lives next to the real DB (not in `:testing`) so it can back the DI
 * test module: `databaseTestModule(TestDb().db)` hands this real DB into a
 * Koin graph. Why file-based instead of `:memory:`: Room 2.8's KMP-style
 * `databaseBuilder<T>(name = …)` expects a real path; the temp file lives
 * for one test, so any "real disk I/O" overhead is irrelevant in practice.
 *
 * Platform-specific are only the temp path, the builder acquisition and the
 * cleanup ([tempDatabaseFilePath] / [testDatabaseBuilder] / [deleteDatabaseFile]);
 * the driver + `build()` stay common. The builder is `expect`/`actual` for the
 * same reason the production [databaseBuilder] is — Android's `Room.databaseBuilder`
 * needs a `Context`, JVM/iOS take a path. The JVM actual is real; iOS/Android are
 * `TODO()`, so a common test that opens a [TestDb] on those targets throws and
 * must be skipped with `@IgnoreIos` / `@IgnoreAndroid`.
 */
public class TestDb : AutoCloseable {
    private val dbPath: String = tempDatabaseFilePath()

    public val db: AppDatabase = testDatabaseBuilder(dbPath)
        .setDriver(BundledSQLiteDriver())
        .build()

    override fun close() {
        db.close()
        deleteDatabaseFile(dbPath)
    }
}

/** A fresh, unique temp-file path for a one-shot test database. */
internal expect fun tempDatabaseFilePath(): String

/** Platform Room builder over the test db at [path] (Android needs a `Context`). */
internal expect fun testDatabaseBuilder(path: String): RoomDatabase.Builder<AppDatabase>

/**
 * Delete the test database [path] and the `-shm` / `-wal` / `-journal`
 * companion files SQLite (WAL is Room's default in 2.8+) creates next to it,
 * so the temp dir doesn't accrete junk.
 */
internal expect fun deleteDatabaseFile(path: String)
