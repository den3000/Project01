package ru.den.writes.code.agenticHub.features.agent.di

import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.agent.StageAgentSpecs
import ru.den.writes.code.agenticHub.features.agent.StageJudgeSpecs
import ru.den.writes.code.agenticHub.features.agent.buildJudges
import ru.den.writes.code.agenticHub.features.agent.buildRoutedAgents
import ru.den.writes.code.agenticHub.features.llm.GenerationParams

/** Koin qualifier for the routed stage-agents binding — see [agentModule]. */
public const val ROUTED_AGENTS: String = "routedAgents"

/** Koin qualifier for the routed judges binding — see [agentModule]. */
public const val ROUTED_JUDGES: String = "routedJudges"

/**
 * Koin module for the multi-agent layer. Specs (+ [GenerationParams]) are
 * runtime; the shared [io.ktor.client.HttpClient] comes from the graph
 * ([buildRoutedAgents]/[buildJudges] build one [LlmApi][ru.den.writes.code.agenticHub.features.llm.LlmApi]
 * per spec internally).
 *
 * Two type erasures to work around, both hidden by the raw `List` type:
 * - The specs arrive wrapped in [StageAgentSpecs]/[StageJudgeSpecs], not as bare lists —
 *   Koin resolves a `List` passed via `parametersOf(...)` as the factory's own
 *   `List<Routed…>` result and skips the builder, so the parameter type must differ from
 *   the return type.
 * - Both factories return `List<…>`, which erases to the same key `java.util.List`, so they
 *   are distinguished by the [ROUTED_AGENTS]/[ROUTED_JUDGES] qualifiers.
 */
public val agentModule: Module = module {
    factory<List<RoutedAgent>>(named(ROUTED_AGENTS)) { (specs: StageAgentSpecs, params: GenerationParams) ->
        buildRoutedAgents(specs.value, get(), params)
    }
    factory<List<RoutedJudge>>(named(ROUTED_JUDGES)) { (specs: StageJudgeSpecs) ->
        buildJudges(specs.value, get())
    }
}
