package ru.den.writes.code.agenticHub.platform.database

import androidx.room.RoomDatabase

internal actual fun databaseBuilder(): RoomDatabase.Builder<AppDatabase> =
    // Android's Room builder needs a Context (Room.databaseBuilder(context, name))
    // and an app-specific db path. Wire this when the Android app grows a real DB.
    TODO("Android database builder not implemented yet")
