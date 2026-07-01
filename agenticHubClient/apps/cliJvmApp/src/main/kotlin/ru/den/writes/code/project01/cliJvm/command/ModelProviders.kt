package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.MODEL
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.PROVIDER
import ru.den.writes.code.project01.cliJvm.cliargs.ParsedArg
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.llm.gemini.GeminiModel
import ru.den.writes.code.project01.shared.llm.huggingface.HuggingFaceModel
import ru.den.writes.code.project01.shared.llm.openrouter.OpenRouterModel

/** Provider API keys, injected into a [ModelProviderFactory] at construction. */
internal data class ApiKeys(
    val gemini: String = "",
    val openRouter: String = "",
    val huggingFace: String = "",
)

/** Gemini API limit on the number of stop sequences. */
internal const val MAX_STOP_SEQUENCES: Int = 5

/**
 * The one place the provider API keys live after startup. Resolves a parsed
 * `-agent provider/model` (its `provider`/`model` sub-values) into a typed
 * [ModelProvider], injecting the keys so nothing downstream has to carry them.
 * Built once in `main` from [ApiKeys] and handed to the arg mapper.
 */
internal class ModelProviderFactory(private val keys: ApiKeys) {
    fun buildProvider(agent: ParsedArg?): ModelProvider =
        buildModelProvider(
            agent?.subValue(PROVIDER) ?: PROVIDER_GEMINI,
            agent?.subValue(MODEL),
            keys.gemini, keys.openRouter, keys.huggingFace,
        )
}

internal const val PROVIDER_GEMINI = "gemini"
internal const val PROVIDER_OPENROUTER = "openrouter"
internal const val PROVIDER_HUGGINGFACE = "huggingface"

/**
 * Resolve a `<provider> [<model>]` pair plus the matching API key into a typed
 * [ModelProvider], defaulting the model per provider. Throws [CliArgsException]
 * on an unknown provider or a blank key for the chosen one.
 */
internal fun buildModelProvider(
    providerRaw: String,
    modelRaw: String?,
    geminiApiKey: String,
    openRouterApiKey: String,
    huggingFaceApiKey: String,
): ModelProvider = when (providerRaw) {
    PROVIDER_GEMINI -> {
        if (geminiApiKey.isBlank()) throw CliArgsException.MissingRequiredArgument(
            "GEMINI_API_KEY", "set GEMINI_API_KEY in local.properties or as an env var",
        )
        ModelProvider.Gemini(
            model = modelRaw?.let(GeminiModel.Companion::fromId) ?: GeminiModel.Default,
            apiKey = geminiApiKey,
        )
    }
    PROVIDER_OPENROUTER -> {
        if (openRouterApiKey.isBlank()) throw CliArgsException.MissingRequiredArgument(
            "OPENROUTER_API_KEY", "set OPENROUTER_API_KEY in local.properties or as an env var",
        )
        ModelProvider.OpenRouter(
            model = modelRaw?.let(OpenRouterModel.Companion::fromId) ?: OpenRouterModel.Default,
            apiKey = openRouterApiKey,
        )
    }
    PROVIDER_HUGGINGFACE -> {
        if (huggingFaceApiKey.isBlank()) throw CliArgsException.MissingRequiredArgument(
            "HUGGINGFACE_API_KEY", "set HUGGINGFACE_API_KEY in local.properties or as an env var",
        )
        ModelProvider.HuggingFace(
            model = modelRaw?.let(HuggingFaceModel.Companion::fromId) ?: HuggingFaceModel.Default,
            apiKey = huggingFaceApiKey,
        )
    }
    else -> throw CliArgsException.InvalidArgumentValue(
        "-provider", providerRaw, "one of: $PROVIDER_GEMINI, $PROVIDER_OPENROUTER, $PROVIDER_HUGGINGFACE",
    )
}
