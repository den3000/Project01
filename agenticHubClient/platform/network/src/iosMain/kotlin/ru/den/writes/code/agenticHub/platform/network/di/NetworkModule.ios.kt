package ru.den.writes.code.agenticHub.platform.network.di

import org.koin.core.module.Module

internal actual fun networkModule(): Module =
    // iOS would back HttpClient with the Darwin engine. Wire when the iOS app makes
    // HTTP calls.
    TODO("iOS HTTP engine not implemented yet")
