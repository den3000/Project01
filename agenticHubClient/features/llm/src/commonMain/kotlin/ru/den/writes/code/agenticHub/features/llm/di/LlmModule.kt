package ru.den.writes.code.agenticHub.features.llm.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.LlmApiFake
import ru.den.writes.code.agenticHub.features.llm.buildLlmApi

/**
 * Koin module for the LLM layer. [ModelProvider] is runtime-derived (picked from
 * CLI args), so it's a factory parameter; the shared [io.ktor.client.HttpClient]
 * is pulled from the graph (its owner is the app — engine is app-specific).
 * A [LlmApi] per provider → factory, not single.
 */
public val llmModule: Module = module {
    factory<LlmApi> { (mp: ModelProvider) -> buildLlmApi(mp, get()) }
}

/**
 * Test counterpart of [llmModule]: binds [LlmApi] to a scripted fake that never
 * touches the network. Compose it in place of [llmModule] in an integration graph.
 *
 * Both bindings are `factory` (fresh per `get()` → tests stay independent). Since
 * the [LlmApi] interface (`send`) is too thin to script through, the queue lives
 * in [FakeLlmScript]. Two ways to drive it:
 * - default — `get<LlmApi>()` builds a [LlmApiFake] over a fresh empty script
 *   (returns synthetic errors; fine when the graph just needs *an* LLM);
 * - scripted — create+configure a [FakeLlmScript] in the test and pass it in:
 *   `get<LlmApi> { parametersOf(script) }`, then assert on `script.calls`.
 *
 * The fake ([LlmApiFake]) and its holder ([FakeLlmScript]) live next to the
 * real `*Api` impls. See agenticHubClient/DI.md.
 */
public val llmTestModule: Module = module {
    factory { FakeLlmScript() }
    factory<LlmApi> { (script: FakeLlmScript?) ->
        LlmApiFake(script ?: get())
    }
}
