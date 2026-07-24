package ru.den.writes.code.agenticHub.cliJvm.di

import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.BuildKonfig
import ru.den.writes.code.agenticHub.cliJvm.ModelProviderFactory
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArgsParser
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderKind
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderSelector
import ru.den.writes.code.agenticHub.features.rag.embedding.GeminiEmbedder
import ru.den.writes.code.agenticHub.features.rag.embedding.OllamaEmbedder
import ru.den.writes.code.agenticHub.platform.config.ApiKey
import ru.den.writes.code.agenticHub.platform.config.ApiKeys
import ru.den.writes.code.agenticHub.platform.config.resolveKey

/**
 * App-owned bindings for the CLI composition root: the provider keys ([resolveKey] over
 * the environment and [BuildKonfig]), the [ModelProviderFactory] over them, and the
 * [CliArgsParser]. The shared [io.ktor.client.HttpClient] now comes from `networkModule`
 * (platform:network, single + onClose); [ru.den.writes.code.agenticHub.features.llm.ModelProvider]
 * is not bound here — it's runtime-derived and passed as a factory parameter.
 */
internal val appModule: Module = module {
    single {
        ApiKeys(
            gemini = resolveKey(ApiKey.GEMINI, BuildKonfig.GEMINI_API_KEY, System::getenv),
            openRouter = resolveKey(ApiKey.OPEN_ROUTER, BuildKonfig.OPENROUTER_API_KEY, System::getenv),
            huggingFace = resolveKey(ApiKey.HUGGING_FACE, BuildKonfig.HUGGINGFACE_API_KEY, System::getenv),
        )
    }
    single { ModelProviderFactory(get()) }
    single { CliArgsParser() }
    // The one place RAG's embedder backends are built with credentials: Ollama (local,
    // keyless) or Gemini (the config key). rag stays credential-free — the key is here.
    single<EmbedderSelector> {
        val http = get<HttpClient>()
        val keys = get<ApiKeys>()
        EmbedderSelector { kind ->
            when (kind) {
                EmbedderKind.OLLAMA -> OllamaEmbedder(http)
                EmbedderKind.GEMINI -> GeminiEmbedder(http, keys.gemini)
            }
        }
    }
}
