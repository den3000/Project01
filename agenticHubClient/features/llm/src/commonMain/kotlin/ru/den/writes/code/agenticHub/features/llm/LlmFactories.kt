package ru.den.writes.code.agenticHub.features.llm

import io.ktor.client.HttpClient
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiApi
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.features.llm.huggingface.HuggingFaceApi
import ru.den.writes.code.agenticHub.features.llm.huggingface.HuggingFaceModel
import ru.den.writes.code.agenticHub.features.llm.ollama.LocalOllamaApi
import ru.den.writes.code.agenticHub.features.llm.ollama.OllamaModel
import ru.den.writes.code.agenticHub.features.llm.openrouter.OpenRouterApi
import ru.den.writes.code.agenticHub.features.llm.openrouter.OpenRouterModel

public const val PROVIDER_GEMINI: String = "gemini"
public const val PROVIDER_OPENROUTER: String = "openrouter"
public const val PROVIDER_HUGGINGFACE: String = "huggingface"
public const val PROVIDER_OLLAMA: String = "ollama"

/** Gemini API limit on the number of stop sequences. */
public const val MAX_STOP_SEQUENCES: Int = 5

/**
 * Raised by [buildModelProvider] when the request can't be turned into a
 * [ModelProvider]. Neutral (no CLI types) — callers translate it into their own
 * error surface (the CLI maps it to `CliArgsException`).
 */
public sealed class ModelProviderError(message: String) : IllegalArgumentException(message) {
    /** The chosen provider needs an API key [keyName], but it was blank. */
    public class MissingApiKey(public val keyName: String) : ModelProviderError("$keyName is blank")

    /** [providerRaw] is not one of the known providers. */
    public class UnknownProvider(public val providerRaw: String) :
        ModelProviderError("unknown provider: $providerRaw")
}

/** The concrete [LlmApi] for a provider, sharing the one [client]. */
public fun buildLlmApi(mp: ModelProvider, client: HttpClient): LlmApi = when (mp) {
    is ModelProvider.Gemini -> GeminiApi(httpClient = client, apiKey = mp.apiKey, model = mp.model)
    is ModelProvider.OpenRouter -> OpenRouterApi(httpClient = client, apiKey = mp.apiKey, model = mp.model)
    is ModelProvider.HuggingFace -> HuggingFaceApi(httpClient = client, apiKey = mp.apiKey, model = mp.model)
    is ModelProvider.LocalOllama -> LocalOllamaApi(httpClient = client, model = mp.model, baseUrl = mp.baseUrl)
}

/**
 * Resolve a `<provider> [<model>]` pair plus the matching API key into a typed
 * [ModelProvider], defaulting the model per provider. Throws [ModelProviderError]
 * on an unknown provider or a blank key for the chosen one.
 */
public fun buildModelProvider(
    providerRaw: String,
    modelRaw: String?,
    geminiApiKey: String,
    openRouterApiKey: String,
    huggingFaceApiKey: String,
): ModelProvider = when (providerRaw) {
    PROVIDER_GEMINI -> {
        if (geminiApiKey.isBlank()) throw ModelProviderError.MissingApiKey("GEMINI_API_KEY")
        ModelProvider.Gemini(
            model = modelRaw?.let(GeminiModel.Companion::fromId) ?: GeminiModel.Default,
            apiKey = geminiApiKey,
        )
    }
    PROVIDER_OPENROUTER -> {
        if (openRouterApiKey.isBlank()) throw ModelProviderError.MissingApiKey("OPENROUTER_API_KEY")
        ModelProvider.OpenRouter(
            model = modelRaw?.let(OpenRouterModel.Companion::fromId) ?: OpenRouterModel.Default,
            apiKey = openRouterApiKey,
        )
    }
    PROVIDER_HUGGINGFACE -> {
        if (huggingFaceApiKey.isBlank()) throw ModelProviderError.MissingApiKey("HUGGINGFACE_API_KEY")
        ModelProvider.HuggingFace(
            model = modelRaw?.let(HuggingFaceModel.Companion::fromId) ?: HuggingFaceModel.Default,
            apiKey = huggingFaceApiKey,
        )
    }
    PROVIDER_OLLAMA -> ModelProvider.LocalOllama(
        // Local server, no credentials — the api keys above are irrelevant here.
        model = modelRaw?.let(OllamaModel.Companion::fromId) ?: OllamaModel.Default,
    )
    else -> throw ModelProviderError.UnknownProvider(providerRaw)
}
