package ru.den.writes.code.agenticHub.features.llm.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
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
