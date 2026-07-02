package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.MODEL
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.PROVIDER
import ru.den.writes.code.project01.cliJvm.cliargs.ParsedArg
import ru.den.writes.code.project01.cliJvm.cliargs.subValue
import ru.den.writes.code.project01.cliJvm.commandMappers.bailInvalid
import ru.den.writes.code.project01.cliJvm.commandMappers.bailMissing
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.ModelProviderError
import ru.den.writes.code.agenticHub.features.llm.PROVIDER_GEMINI
import ru.den.writes.code.agenticHub.features.llm.PROVIDER_HUGGINGFACE
import ru.den.writes.code.agenticHub.features.llm.PROVIDER_OPENROUTER
import ru.den.writes.code.agenticHub.features.llm.buildModelProvider

/** Provider API keys, injected into a [ModelProviderFactory] at construction. */
internal data class ApiKeys(
    val gemini: String = "",
    val openRouter: String = "",
    val huggingFace: String = "",
)

/**
 * The one place the provider API keys live after startup. Resolves a parsed
 * `-agent provider/model` (its `provider`/`model` sub-values) into a typed
 * [ModelProvider] via the neutral `buildModelProvider` (features:llm), injecting
 * the keys. Translates its [ModelProviderError] into a [ParseError] (via the
 * mapper's bail helpers) so `main` renders it + USAGE on a missing key. Built once
 * in `main` from [ApiKeys]; `buildProvider` runs inside the mapper, so its bail
 * propagates to `parse()`'s [ParsedStartCommand.Err].
 */
internal class ModelProviderFactory(private val keys: ApiKeys) {
    fun buildProvider(agent: ParsedArg?): ModelProvider =
        try {
            buildModelProvider(
                agent?.subValue(PROVIDER) ?: PROVIDER_GEMINI,
                agent?.subValue(MODEL),
                keys.gemini, keys.openRouter, keys.huggingFace,
            )
        } catch (e: ModelProviderError.MissingApiKey) {
            bailMissing(e.keyName, "set ${e.keyName} in local.properties or as an env var")
        } catch (e: ModelProviderError.UnknownProvider) {
            bailInvalid(
                "-provider", e.providerRaw,
                "one of: $PROVIDER_GEMINI, $PROVIDER_OPENROUTER, $PROVIDER_HUGGINGFACE",
            )
        }
}
