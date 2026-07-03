package ru.den.writes.code.agenticHub.platform.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/**
 * One-shot Room database backed by a fresh tmp file. Use inside
 * `.use { … }` so the file (plus the WAL/SHM siblings SQLite creates
 * alongside it) gets removed when the block exits.
 *
 * Lives next to the real DB (not in `:testing`) so it can back the DI
 * test module: `databaseTestModule(TestDb().db)` hands this real DB into a
 * Koin graph. Why file-based instead of `:memory:`: Room 2.8's KMP-style
 * `databaseBuilder<T>(name = …)` expects a real path; the temp file lives
 * for one test, so any "real disk I/O" overhead is irrelevant in practice.
 */
public class TestDb : AutoCloseable {
    private val dbFile: File = File.createTempFile("project01-test-", ".db")

    public val db: AppDatabase = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .build()

    override fun close() {
        db.close()
        dbFile.delete()
        // SQLite in WAL mode (Room default in 2.8+) creates these companion
        // files; remove them too so the temp dir doesn't accrete junk.
        File("${dbFile.absolutePath}-shm").delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-journal").delete()
    }
}
