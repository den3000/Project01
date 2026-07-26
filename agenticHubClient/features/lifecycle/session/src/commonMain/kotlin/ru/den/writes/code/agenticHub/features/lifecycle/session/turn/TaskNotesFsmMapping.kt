package ru.den.writes.code.agenticHub.features.lifecycle.session.turn

import ru.den.writes.code.agenticHub.features.fsm.RetryState
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.Task
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage

/**
 * The boundary between what is stored ([TaskNotes], written by `features:memory`
 * into `tasks/<id>.md`) and what decides ([Task], the FSM's own value type).
 *
 * A mapping rather than a shared type on purpose: `features:memory` knows nothing
 * about the FSM and must not — it persists numbers and a keyword, and the meaning
 * of both lives here. That also keeps the FSM module free of any storage shape,
 * which is what let it stay dependency-free.
 *
 * Deliberately a stop-gap. Storing spent counters beside the notes is what makes
 * the new engine possible without touching the storage layer; when the two stage
 * machines are finally merged, this file is the seam to cut.
 */
internal fun TaskNotes.toFsmTask(): Task = Task(
    taskId = taskId,
    // A file with no stage (legacy or hand-edited) starts the task at the
    // beginning: the FSM has no "unknown" position, and treating it as the first
    // stage is the only reading that cannot hand out a free jump to done.
    stage = stage?.toFsmStage() ?: Stage.INITIAL,
    goal = goal,
    notes = notes,
    // Only the spent side is stored; the ceilings come from the FSM, so tuning a
    // ceiling applies to tasks that are already on disk instead of freezing
    // yesterday's value into them.
    taskRetryState = RetryState(attempt = taskRetriesSpent, max = RetryState.TASK_MAX),
    stageRetryState = RetryState(attempt = stageRetriesSpent, max = RetryState.STAGE_MAX),
    transportRetryState = RetryState(attempt = transportRetriesSpent, max = RetryState.TRANSPORT_MAX),
)

/**
 * Fold an FSM decision back into the stored shape, keeping everything the FSM has
 * no opinion about — here `paused`, which the machine deliberately does not model.
 * Written this way round (patch the notes, don't rebuild them) so a field added to
 * [TaskNotes] tomorrow survives a stage advance without anyone remembering to
 * carry it across.
 */
internal fun TaskNotes.withFsmTask(task: Task): TaskNotes = copy(
    goal = task.goal,
    stage = task.stage.toTaskStage(),
    notes = task.notes,
    taskRetriesSpent = task.taskRetryState.attempt,
    stageRetriesSpent = task.stageRetryState.attempt,
    transportRetriesSpent = task.transportRetryState.attempt,
)

/**
 * The two stage enums are the same five stages under two names, and they are
 * matched by [TaskStage.keyword] / [Stage.keyword] rather than by ordinal: the
 * keyword is what the wire, the markdown and the model all use, so it is the only
 * identity that cannot drift when someone reorders a constant.
 */
internal fun TaskStage.toFsmStage(): Stage =
    Stage.byKeyword(keyword) ?: error("no FSM stage for keyword '$keyword'")

internal fun Stage.toTaskStage(): TaskStage =
    TaskStage.byKeyword(keyword) ?: error("no stored stage for keyword '$keyword'")
