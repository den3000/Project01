package ru.den.writes.code.agenticHub.platform.database.di

import org.koin.core.module.Module

/**
 * Koin module binding the Room [AppDatabase][ru.den.writes.code.agenticHub.platform.database.AppDatabase]
 * and its [MessageDao][ru.den.writes.code.agenticHub.platform.database.MessageDao].
 *
 * `actual` per target: the JVM/iOS builders take a file path, Android takes a
 * `Context` — hidden inside the platform binding instead of a shared signature.
 */
internal expect fun databaseModule(): Module

/** The platform's database Koin module. */
public val databaseModule: Module = databaseModule()
