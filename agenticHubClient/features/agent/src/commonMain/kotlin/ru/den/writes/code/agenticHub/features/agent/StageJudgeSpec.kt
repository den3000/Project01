package ru.den.writes.code.agenticHub.features.agent

import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.memory.TaskBinding

/**
 * Parsed `-judgeAgent <from..to>=<provider>:<model>` spec: a [provider] (model
 * + transport) bound to a [binding] span of FSM stages. Mirrors [StageAgentSpec]
 * minus the profile — a judge has no persona, only a model + a stage span.
 * The host wires each into a [RoutedJudge] wrapping an `LlmInvariantJudge`.
 */
public data class StageJudgeSpec(
    val binding: TaskBinding,
    val provider: ModelProvider,
)
