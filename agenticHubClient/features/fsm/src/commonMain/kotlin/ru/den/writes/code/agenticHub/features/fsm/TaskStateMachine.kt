package ru.den.writes.code.agenticHub.features.fsm

/**
 * The task FSM as everyone outside the module sees it: one question, one answer.
 *
 * Hand it what a turn ended as ([UpdateReason]) and it says what became of the task
 * ([UpdateDecision]) — where it stands, what the move was, what the turn cost, and
 * whether the model is told. Everything behind that — the transition table, the two
 * halves of a turn, the three budgets — is the implementation's and is not exposed:
 * a caller that can reach the halves is a caller that has to join them, and joining
 * them is exactly the set of rules this module exists to own.
 *
 * An interface rather than a class so the rules can be stood aside in a test —
 * `lifecycle:session` proves that a turn's decisions leave the engine by handing it
 * a machine that records what it was asked. The rules themselves live in
 * [TaskStateMachineImpl], which the graph binds as a `single`.
 */
public interface TaskStateMachine {

    /**
     * Everything one turn does to [task], from what the caller observed ([reason])
     * to what the task becomes.
     *
     * No I/O: the decision is returned, not applied. Persisting the task, branching
     * the conversation and wording the feedback stay outside, where they can happen;
     * here they cannot even be named.
     */
    public fun update(task: Task, reason: UpdateReason): UpdateDecision
}
