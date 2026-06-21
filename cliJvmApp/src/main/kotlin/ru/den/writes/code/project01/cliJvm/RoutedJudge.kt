package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.invariant.InvariantChecker
import ru.den.writes.code.project01.shared.memory.TaskBinding

/**
 * One per-stage invariant judge: a [checker] bound to a [binding] span of FSM
 * stages. The host routes a turn's reply to the judge whose span covers the
 * active task's stage (see [TurnEngine.judgeFor]). Mirrors [RoutedAgent] minus
 * the profile — a judge speaks with no persona, only a model + a stage span.
 */
internal class RoutedJudge(
    val binding: TaskBinding,
    val checker: InvariantChecker,
)
