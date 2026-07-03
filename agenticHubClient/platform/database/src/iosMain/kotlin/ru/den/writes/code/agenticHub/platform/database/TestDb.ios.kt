package ru.den.writes.code.agenticHub.platform.database

import androidx.room.RoomDatabase

internal actual fun tempDatabaseFilePath(): String =
    // iOS would put the temp db under NSTemporaryDirectory (via NSFileManager).
    // Wire when iOS grows runnable DB tests.
    TODO("iOS TestDb temp path not implemented yet")

internal actual fun testDatabaseBuilder(path: String): RoomDatabase.Builder<AppDatabase> =
    TODO("iOS TestDb builder not implemented yet")

internal actual fun deleteDatabaseFile(path: String): Unit =
    TODO("iOS TestDb cleanup not implemented yet")
