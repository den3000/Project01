package ru.den.writes.code.agenticHub.features.viewmodel

import ru.den.writes.code.agenticHub.features.agent.invariant.InvariantChecker
import ru.den.writes.code.agenticHub.features.agent.memory.TaskBinding

/**
 * One per-stage invariant judge: a [checker] bound to a [binding] span of FSM
 * stages. The host routes a turn's reply to the judge whose span covers the
 * active task's stage (see [TurnEngine.judgeFor]). Mirrors [RoutedAgent] minus
 * the profile — a judge speaks with no persona, only a model + a stage span.
 */
public class RoutedJudge(
    val binding: TaskBinding,
    val checker: InvariantChecker,
    /** The judge's model id — tags the breach banner in the TUI `judge` column. */
    val modelId: String,
)
