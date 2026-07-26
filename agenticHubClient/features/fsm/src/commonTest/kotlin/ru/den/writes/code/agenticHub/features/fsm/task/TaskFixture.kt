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
    stage: Stage = Stage.EXECUTION,
    paused: Boolean = false,
    notes: List<String> = emptyList(),
    taskRetryState: RetryState = RetryState.task(),
    stageRetryState: RetryState = RetryState.stage(),
    transportRetryState: RetryState = RetryState.transport(),
): Task = Task(
    taskId = TASK_ID,
    stage = stage,
    paused = paused,
    goal = GOAL,
    notes = notes,
    taskRetryState = taskRetryState,
    stageRetryState = stageRetryState,
    transportRetryState = transportRetryState,
)

internal const val TASK_ID = "task-42"
internal const val GOAL = "ship the import script"
