package ru.den.writes.code.project01.cliJvm

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import ru.den.writes.code.project01.cliJvm.db.AppDatabase
import java.io.File

/**
 * One-shot Room database backed by a fresh tmp file. Use inside
 * `.use { … }` so the file (plus the WAL/SHM siblings SQLite creates
 * alongside it) gets removed when the block exits.
 *
 * Why file-based instead of `:memory:`: Room 2.8's KMP-style
 * `databaseBuilder<T>(name = …)` expects a real path; bundled-driver
 * in-memory support exists but is fiddlier than just dropping into
 * `File.createTempFile`. The temp file lives for one test, so any
 * "real disk I/O" overhead is irrelevant in practice.
 */
internal class TestDb : AutoCloseable {
    private val dbFile: File = File.createTempFile("project01-test-", ".db")

    val db: AppDatabase = Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
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
