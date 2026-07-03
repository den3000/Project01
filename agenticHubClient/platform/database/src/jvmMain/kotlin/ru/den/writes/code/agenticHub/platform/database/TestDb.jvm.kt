package ru.den.writes.code.agenticHub.platform.database

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

internal actual fun tempDatabaseFilePath(): String =
    File.createTempFile("project01-test-", ".db").absolutePath

internal actual fun testDatabaseBuilder(path: String): RoomDatabase.Builder<AppDatabase> =
    Room.databaseBuilder<AppDatabase>(name = path)

internal actual fun deleteDatabaseFile(path: String) {
    File(path).delete()
    File("$path-shm").delete()
    File("$path-wal").delete()
    File("$path-journal").delete()
}
