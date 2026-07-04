package ru.den.writes.code.agenticHub.platform.network.di

import org.koin.core.module.Module

internal actual fun networkModule(): Module =
    // Android would back HttpClient with the OkHttp/Android engine. Wire when the
    // Android app makes HTTP calls.
    TODO("Android HTTP engine not implemented yet")
