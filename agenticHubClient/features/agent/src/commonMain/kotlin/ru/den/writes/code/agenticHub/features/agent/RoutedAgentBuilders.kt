package ru.den.writes.code.agenticHub.features.agent

import io.ktor.client.HttpClient
import ru.den.writes.code.agenticHub.features.agent.invariant.LlmInvariantJudge
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.buildLlmApi

/**
 * Builders assembling the per-stage runtime collaborators from parsed stage specs
 * + the shared [HttpClient]. Portable across apps (no CLI/render dependency) — the
 * concrete HTTP client and MCP wiring stay in the composition root. Takes the spec
 * lists (not the command config) so this stays in features:agent without depending
 * on the command module.
 */

/**
 * Per-stage agents: one [RoutedAgent] per spec, each its own `LlmApi` + fixed
 * profile, sharing [params]. Empty list for single-agent sessions.
 */
public fun buildRoutedAgents(
    stageAgents: List<StageAgentSpec>,
    client: HttpClient,
    params: GenerationParams,
): List<RoutedAgent> = stageAgents.map { spec ->
    RoutedAgent(
        binding = spec.binding,
        responder = AgentResponder(
            AgentConfig(buildLlmApi(spec.provider, client), params, spec.profileName),
        ),
        profileName = spec.profileName,
        modelId = spec.provider.modelId,
    )
}

/** Per-stage invariant judges: one [RoutedJudge] per spec on its own model. */
public fun buildJudges(
    judgeAgents: List<StageJudgeSpec>,
    client: HttpClient,
): List<RoutedJudge> = judgeAgents.map { spec ->
    RoutedJudge(
        binding = spec.binding,
        checker = LlmInvariantJudge(buildLlmApi(spec.provider, client)),
        modelId = spec.provider.modelId,
    )
}
