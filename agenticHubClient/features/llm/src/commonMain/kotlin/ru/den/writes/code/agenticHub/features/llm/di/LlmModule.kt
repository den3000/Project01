package ru.den.writes.code.agenticHub.features.llm.di

import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.ScriptedLlmApi
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
 * touches the network. Compose it in place of [llmModule] in an integration
 * graph (the fake ignores any `parametersOf` provider, so it's a drop-in for
 * `get<LlmApi> { parametersOf(mp) }`). Because Koin hands the fake out under the
 * [LlmApi] interface — too thin to script through — the queue lives in a public
 * [FakeLlmScript] `single`: configure it cross-module via
 * `koin.get<FakeLlmScript>().queueText(...)`. Plain `common` module → runs on
 * every target. The fake ([ScriptedLlmApi]) and its holder live next to the real
 * `*Api` impls.
 *
 * A **function**, not a `val`: a reused module value would share its `single`
 * [FakeLlmScript] across every test's `koinApplication` (Koin caches the
 * singleton in the module's factory); a fresh module per call keeps each test's
 * script isolated. See agenticHubClient/DI.md.
 */
public val llmTestModule: Module = module {
    factory { FakeLlmScript() }
    factory<LlmApi> { (script: FakeLlmScript?) ->
        ScriptedLlmApi(script ?: get())
    }
}
