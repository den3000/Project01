package ru.den.writes.code.agenticHub.features.agent

import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.memory.TaskBinding

/**
 * One parsed `-stageAgent <from..to>=<provider>:<model>[@<profile>]` entry: the
 * FSM stage span this agent owns, the provider/model it runs, and its fixed
 * memory profile (null = the session's active profile). `main.kt` turns each
 * spec into a [RoutedAgent] with its own `LlmApi`.
 */
public data class StageAgentSpec(
    val binding: TaskBinding,
    val provider: ModelProvider,
    val profileName: String?,
)

/**
 * Holder wrapping the stage-agent spec list for Koin injection. A bare `List` passed as a
 * `parametersOf(...)` value collides with the factory's `List<RoutedAgent>` return type —
 * Koin resolves the injected `List` as the result and skips the factory entirely — so the
 * specs travel wrapped in a distinct type. See [ru.den.writes.code.agenticHub.features.agent.di.agentModule].
 */
public data class StageAgentSpecs(val value: List<StageAgentSpec>)
