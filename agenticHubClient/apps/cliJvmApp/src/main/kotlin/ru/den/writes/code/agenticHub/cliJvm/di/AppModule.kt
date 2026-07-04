package ru.den.writes.code.agenticHub.cliJvm.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.BuildKonfig
import ru.den.writes.code.agenticHub.cliJvm.ApiKeys
import ru.den.writes.code.agenticHub.cliJvm.ModelProviderFactory
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArgsParser

/**
 * App-owned bindings for the CLI composition root: the provider keys read from
 * [BuildKonfig], the [ModelProviderFactory] over them, and the [CliArgsParser].
 * The shared [io.ktor.client.HttpClient] now comes from `networkModule`
 * (platform:network, single + onClose); [ru.den.writes.code.agenticHub.features.llm.ModelProvider]
 * is not bound here — it's runtime-derived and passed as a factory parameter.
 */
internal val appModule: Module = module {
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
