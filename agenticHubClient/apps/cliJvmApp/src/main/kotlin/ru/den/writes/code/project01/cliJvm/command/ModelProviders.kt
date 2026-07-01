package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.MODEL
import ru.den.writes.code.project01.cliJvm.cliargs.CliArg.PROVIDER
import ru.den.writes.code.project01.cliJvm.cliargs.ParsedArg
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.llm.ModelProviderError
import ru.den.writes.code.project01.shared.llm.PROVIDER_GEMINI
import ru.den.writes.code.project01.shared.llm.PROVIDER_HUGGINGFACE
import ru.den.writes.code.project01.shared.llm.PROVIDER_OPENROUTER
import ru.den.writes.code.project01.shared.llm.buildModelProvider

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
 * the keys. Translates its [ModelProviderError] into the CLI's [CliArgsException]
 * so `main` can print USAGE on a missing key. Built once in `main` from [ApiKeys].
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
            throw CliArgsException.MissingRequiredArgument(
                e.keyName, "set ${e.keyName} in local.properties or as an env var",
            )
        } catch (e: ModelProviderError.UnknownProvider) {
            throw CliArgsException.InvalidArgumentValue(
                "-provider", e.providerRaw,
                "one of: $PROVIDER_GEMINI, $PROVIDER_OPENROUTER, $PROVIDER_HUGGINGFACE",
            )
        }
}
