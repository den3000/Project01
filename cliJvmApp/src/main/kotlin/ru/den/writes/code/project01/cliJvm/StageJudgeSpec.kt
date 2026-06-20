package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.memory.TaskBinding

/**
 * Parsed `-judgeAgent <from..to>=<provider>:<model>` spec: a [provider] (model
 * + transport) bound to a [binding] span of FSM stages. Mirrors [StageAgentSpec]
 * minus the profile — a judge has no persona, only a model + a stage span.
 * `main` turns each into a [RoutedJudge] wrapping an `LlmInvariantJudge`.
 */
internal data class StageJudgeSpec(
    val binding: TaskBinding,
    val provider: ModelProvider,
)
