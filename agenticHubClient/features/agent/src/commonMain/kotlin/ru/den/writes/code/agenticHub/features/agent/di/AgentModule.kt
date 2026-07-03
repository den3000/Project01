package ru.den.writes.code.agenticHub.features.agent.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.agent.StageAgentSpec
import ru.den.writes.code.agenticHub.features.agent.StageJudgeSpec
import ru.den.writes.code.agenticHub.features.agent.buildJudges
import ru.den.writes.code.agenticHub.features.agent.buildRoutedAgents
import ru.den.writes.code.agenticHub.features.llm.GenerationParams

/**
 * Koin module for the multi-agent layer. Specs (+ [GenerationParams]) are
 * runtime; the shared [io.ktor.client.HttpClient] comes from the graph
 * ([buildRoutedAgents]/[buildJudges] build one [LlmApi][ru.den.writes.code.agenticHub.features.llm.LlmApi]
 * per spec internally).
 */
public val agentModule: Module = module {
    factory<List<RoutedAgent>> { (specs: List<StageAgentSpec>, params: GenerationParams) ->
        buildRoutedAgents(specs, get(), params)
    }
    factory<List<RoutedJudge>> { (specs: List<StageJudgeSpec>) ->
        buildJudges(specs, get())
    }
}
