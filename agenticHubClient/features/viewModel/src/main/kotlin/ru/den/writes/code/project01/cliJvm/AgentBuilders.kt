package ru.den.writes.code.project01.cliJvm

import io.ktor.client.HttpClient
import ru.den.writes.code.project01.cliJvm.command.StartCommand
import ru.den.writes.code.agenticHub.features.agent.AgentConfig
import ru.den.writes.code.agenticHub.features.agent.AgentResponder
import ru.den.writes.code.agenticHub.features.agent.invariant.LlmInvariantJudge
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.buildLlmApi
import java.util.UUID

/**
 * Builders assembling the per-stage runtime collaborators from a
 * [StartCommand.RunChat] config + the shared [HttpClient]. Portable across apps
 * (no CLI/render dependency) — the concrete HTTP client and MCP wiring stay in
 * the composition root.
 */

/**
 * Per-stage agents: one [RoutedAgent] per spec, each its own `LlmApi` + fixed
 * profile, sharing [params]. Empty for single-agent sessions.
 */
public fun buildRoutedAgents(
    chat: StartCommand.RunChat?,
    client: HttpClient,
    params: GenerationParams,
): List<RoutedAgent> = chat?.config?.stageAgents.orEmpty().map { spec ->
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
    chat: StartCommand.RunChat?,
    client: HttpClient,
): List<RoutedJudge> = chat?.config?.judgeAgents.orEmpty().map { spec ->
    RoutedJudge(
        binding = spec.binding,
        checker = LlmInvariantJudge(buildLlmApi(spec.provider, client)),
        modelId = spec.provider.modelId,
    )
}

/**
 * Eight-char hex slice off a random UUID — readable, easy to retype, ~4 billion
 * values. Matches `^[a-zA-Z0-9_-]+$`, so it's a valid `-session` to resume.
 */
public fun generateSessionId(): String = UUID.randomUUID().toString().take(8)
