package ru.den.writes.code.agenticHub.features.fsm.task

import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.Task

/**
 * A task in mid-flight, with every budget untouched unless the test says
 * otherwise. Defaults to [Stage.EXECUTION] — the middle of the FSM, where the
 * measured stalls actually happen.
 */
internal fun task(
    stage: Stage? = Stage.EXECUTION,
    paused: Boolean = false,
    notes: List<String> = emptyList(),
    taskRetryState: RetryState = RetryState.task(),
    stageRetryState: RetryState = RetryState.stage(),
    turnRetryState: RetryState = RetryState.turn(),
    transportRetryState: RetryState = RetryState.transport(),
): Task = Task(
    taskId = TASK_ID,
    stage = stage,
    paused = paused,
    goal = GOAL,
    notes = notes,
    taskRetryState = taskRetryState,
    stageRetryState = stageRetryState,
    turnRetryState = turnRetryState,
    transportRetryState = transportRetryState,
)

/** The same budget with every attempt already taken — the next spend fails. */
internal fun spentToTheLast(state: RetryState): RetryState = state.copy(attempt = state.max)

internal const val TASK_ID = "task-42"
internal const val GOAL = "ship the import script"

/** Pinned here rather than read off [RetryState]: the numbers are the contract. */
internal const val TURN_MAX = 15
internal const val TRANSPORT_MAX = 15
internal const val STAGE_MAX = 10
internal const val TASK_MAX = 5
