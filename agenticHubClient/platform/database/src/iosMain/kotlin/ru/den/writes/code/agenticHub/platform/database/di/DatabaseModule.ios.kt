package ru.den.writes.code.agenticHub.platform.database.di

import org.koin.core.module.Module

internal actual fun databaseModule(): Module =
    // iOS resolves the DB path under NSDocumentDirectory; wire when the iOS app
    // grows real persistence.
    TODO("iOS database module not implemented yet")
