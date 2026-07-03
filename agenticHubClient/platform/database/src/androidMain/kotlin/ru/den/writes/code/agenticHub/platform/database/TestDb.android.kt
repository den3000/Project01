package ru.den.writes.code.agenticHub.platform.database

import androidx.room.RoomDatabase

internal actual fun tempDatabaseFilePath(): String =
    // Android would put the temp db under Context.cacheDir. Wire when Android
    // grows real persistence (same Context dependency as databaseBuilder).
    TODO("Android TestDb temp path not implemented yet")

internal actual fun testDatabaseBuilder(path: String): RoomDatabase.Builder<AppDatabase> =
    // Android's Room.databaseBuilder needs a Context; wire with real persistence.
    TODO("Android TestDb builder not implemented yet")

internal actual fun deleteDatabaseFile(path: String): Unit =
    TODO("Android TestDb cleanup not implemented yet")
