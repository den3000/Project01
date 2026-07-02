package ru.den.writes.code.agenticHub.cliJvm.di

import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose
import ru.den.writes.code.agenticHub.BuildKonfig
import ru.den.writes.code.agenticHub.cliJvm.ApiKeys
import ru.den.writes.code.agenticHub.cliJvm.ModelProviderFactory
import ru.den.writes.code.agenticHub.cliJvm.buildHttpClient
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArgsParser

/**
 * App-owned bindings for the CLI composition root: the shared [HttpClient]
 * (Java engine, whole-session lifetime → single, closed by Koin on stopKoin()),
 * the provider keys read from [BuildKonfig], the [ModelProviderFactory] over them,
 * and the [CliArgsParser]. [ru.den.writes.code.agenticHub.features.llm.ModelProvider]
 * is not bound here — it's runtime-derived and passed as a factory parameter.
 */
internal val appModule: Module = module {
    single<HttpClient> { buildHttpClient() } onClose { it?.close() }
    single {
        ApiKeys(
            gemini = BuildKonfig.GEMINI_API_KEY,
            openRouter = BuildKonfig.OPENROUTER_API_KEY,
            huggingFace = BuildKonfig.HUGGINGFACE_API_KEY,
        )
    }
    single { ModelProviderFactory(get()) }
    single { CliArgsParser() }
}
