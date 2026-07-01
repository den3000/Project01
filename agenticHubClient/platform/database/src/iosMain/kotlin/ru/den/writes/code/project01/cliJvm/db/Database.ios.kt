package ru.den.writes.code.project01.cliJvm.db

import androidx.room.RoomDatabase

internal actual fun databaseBuilder(): RoomDatabase.Builder<AppDatabase> =
    // iOS needs an NSDocumentDirectory-based db path fed to the name-based
    // Room.databaseBuilder. Wire this when the iOS app grows a real DB.
    TODO("iOS database builder not implemented yet")
