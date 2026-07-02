package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.agent.memory.TaskBinding

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
