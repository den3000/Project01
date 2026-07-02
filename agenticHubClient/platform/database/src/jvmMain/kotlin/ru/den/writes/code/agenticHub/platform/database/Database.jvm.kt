package ru.den.writes.code.agenticHub.platform.database

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * Where the JVM session-history database lives. One file for all sessions,
 * discriminated by the `session_id` column.
 */
private val DB_FILE: File = File(
    System.getProperty("user.home"),
    ".project01-cli/history.db",
)

internal actual fun databaseBuilder(): RoomDatabase.Builder<AppDatabase> {
    DB_FILE.parentFile.mkdirs()
    return Room.databaseBuilder<AppDatabase>(name = DB_FILE.absolutePath)
}
