package ru.den.writes.code.agenticHub.features.fsm

/**
 * A task and everything the FSM needs to keep running it: where it is ([stage]),
 * what it is for ([goal] / [notes]), and how much failure it may still absorb
 * (the three budgets).
 *
 * State only — every decision about it lives in [TaskStateMachine]. Reading a
 * field tells you where the task stands; it never tells you what happens next.
 *
 * The retry budgets live here rather than in the engine because they must survive
 * a process restart: a task resumed tomorrow has already spent what it spent
 * today, and an engine that forgets that gets a fresh five attempts every time
 * the CLI is relaunched.
 */
data class Task(
    val taskId: String,
    /**
     * Where the task stands. Never absent: a task is always somewhere in the
     * machine, and a stored task with no recorded stage is a parsing problem the
     * loader settles by starting it at [Stage.INITIAL].
     */
    val stage: Stage = Stage.INITIAL,
    val goal: String? = null,
    val notes: List<String> = emptyList(),
    /** Restarts of the whole task; running out ends the run. */
    val taskRetryState: RetryState = RetryState.task(),
    /** Turns this stage may cost without moving on; running out restarts the task. */
    val stageRetryState: RetryState = RetryState.stage(),
    /** Unreachable-provider failures for the whole task; running out ends the run. */
    val transportRetryState: RetryState = RetryState.transport(),
)
