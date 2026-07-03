package ru.den.writes.code.agenticHub.platform.database.di

import org.koin.core.module.Module

internal actual fun databaseModule(): Module =
    // Android needs a Context to locate the DB file; inject it here when the
    // Android app grows real persistence.
    TODO("Android database module not implemented yet")
