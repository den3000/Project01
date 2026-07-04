package ru.den.writes.code.agenticHub.platform.network.di

import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose
import ru.den.writes.code.agenticHub.platform.network.buildHttpClient

// One shared client per session (single) that owns real connections/threads →
// onClose closes it at stopKoin (onClose from org.koin.dsl, not core.module.dsl).
internal actual fun networkModule(): Module = module {
    single<HttpClient> { buildHttpClient() } onClose { it?.close() }
}
