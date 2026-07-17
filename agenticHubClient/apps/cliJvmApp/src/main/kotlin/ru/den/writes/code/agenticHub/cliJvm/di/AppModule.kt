package ru.den.writes.code.agenticHub.cliJvm.di

import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.BuildKonfig
import ru.den.writes.code.agenticHub.cliJvm.ApiKeys
import ru.den.writes.code.agenticHub.cliJvm.ModelProviderFactory
import ru.den.writes.code.agenticHub.cliJvm.cliargs.CliArgsParser
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderKind
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderSelector
import ru.den.writes.code.agenticHub.features.rag.embedding.GeminiEmbedder
import ru.den.writes.code.agenticHub.features.rag.embedding.OllamaEmbedder

/**
 * Resolve one provider key: the process environment wins over the [baked] value
 * [BuildKonfig] compiled in, and a blank env var counts as absent.
 *
 * [BuildKonfig] reads `local.properties` (or the env) at Gradle *configuration* time and
 * bakes the result into the binary — fine for a developer machine, useless for a binary
 * built somewhere that must not see the secret. Reading the env at *runtime* lets a build
 * carry no credentials at all and a deployment (CI, a container) supply them per run;
 * with no env set, local development keeps working off `local.properties` exactly as before.
 *
 * [env] is injectable so the precedence is unit-testable without touching the real process.
 */
internal fun resolveKey(name: String, baked: String, env: (String) -> String? = System::getenv): String =
    env(name)?.takeIf { it.isNotBlank() } ?: baked

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
            gemini = resolveKey("GEMINI_API_KEY", BuildKonfig.GEMINI_API_KEY),
            openRouter = resolveKey("OPENROUTER_API_KEY", BuildKonfig.OPENROUTER_API_KEY),
            huggingFace = resolveKey("HUGGINGFACE_API_KEY", BuildKonfig.HUGGINGFACE_API_KEY),
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
