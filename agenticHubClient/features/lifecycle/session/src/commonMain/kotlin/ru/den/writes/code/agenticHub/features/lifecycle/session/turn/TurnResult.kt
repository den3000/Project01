package ru.den.writes.code.agenticHub.features.lifecycle.session.turn

import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.memory.SessionStats
import ru.den.writes.code.agenticHub.features.agent.ExecutedToolCall
import ru.den.writes.code.agenticHub.features.agent.invariant.InvariantVerdict
import ru.den.writes.code.agenticHub.features.llm.Usage
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk

/**
 * The outcome of one turn, as computed by `TurnEngine` WITHOUT any direct I/O.
 * A view renders this; the engine never prints.
 *
 * Everything here is an immutable snapshot — in particular [Ok.session] is a
 * copy, never a live [SessionStats] reference. An async TUI renderer must not
 * be able to race the next turn's accumulation through a shared object.
 */
public sealed interface TurnResult {

    /**
     * What the task FSM decided about this turn, when an engine consulted one —
     * null from an engine that keeps its own FSM inline, and from any turn with
     * no active task.
     *
     * This is the one thing a turn cannot finish by itself. [RetryOutcome.Retried]
     * is already done: the task was persisted and the next turn just runs.
     * [RetryOutcome.Restarted] needs the conversation branched and the engine
     * rebuilt — an engine cannot do either to itself — and [RetryOutcome.GaveUp]
     * ends the run. Both are the view-model's to execute, which is why the verdict
     * travels out here instead of being acted on inside.
     */
    public val fsm: RetryOutcome?

    /**
     * A successful turn. [reply] is the model text; [modelId] / [profileName]
     * identify the answering agent (for the `[[AGENT:]]` tag in multi-agent
     * sessions and the footer's pricing lookup); [usage] is this turn's token
     * snapshot (null when the provider returned text without counts);
     * [durationMs] is the measured call time; [session] is the running-total
     * snapshot at this turn (null in OneShot — no history to accumulate into,
     * so the footer prints no `session:` line); [stageAdvance] is what
     * happened to the task FSM; [judge] is what the per-stage invariant judge
     * did — see [JudgeOutcome]; [judgeModelId] is the model of the judge that
     * ran (null when none did) — the TUI tags the breach block with it.
     */
    data class Ok(
        val reply: String,
        val modelId: String,
        val profileName: String?,
        val usage: Usage?,
        val durationMs: Long,
        val session: SessionStatsSnapshot?,
        val stageAdvance: StageAdvance,
        val judge: JudgeOutcome = JudgeOutcome.NotRun,
        val judgeModelId: String? = null,
        /** Tool calls the agent ran this turn (empty for a plain turn). */
        val executedToolCalls: List<ExecutedToolCall> = emptyList(),
        /** RAG chunks retrieved and injected this turn (empty when RAG is off). */
        val retrieval: List<ScoredChunk> = emptyList(),
        override val fsm: RetryOutcome? = null,
    ) : TurnResult

    /**
     * The turn failed (provider error or empty response). [reason] is the message;
     * [fsm] is what the machine charged for it — a dead provider spends the
     * transport budget and can end the run on its own.
     */
    data class Failed(val reason: String, override val fsm: RetryOutcome? = null) : TurnResult
}

/**
 * Immutable copy of a session's running totals at the moment a turn finished.
 * Decouples the footer/summary rendering from the live, still-mutating
 * [SessionStats].
 */
public data class SessionStatsSnapshot(
    val turns: Int,
    val promptTokens: Int,
    val outputTokens: Int,
    val thoughtsTokens: Int,
    val totalTokens: Int,
    val costUsd: Double,
)

/** Snapshot the live counters into an immutable [SessionStatsSnapshot]. */
public fun SessionStats.snapshot(): SessionStatsSnapshot = SessionStatsSnapshot(
    turns = turns,
    promptTokens = totalPromptTokens,
    outputTokens = totalOutputTokens,
    thoughtsTokens = totalThoughtsTokens,
    totalTokens = totalTokens,
    costUsd = totalCostUsd,
)

/**
 * What the invariant judge did with this turn — the vocabulary a view needs to
 * say what happened, rather than assuming one fixed consequence.
 *
 * A bare verdict could not express this: a breach used to mean exactly one
 * thing, "the turn was dropped", and both views hard-coded that sentence. Once
 * a flagged reply can be rewritten, that sentence is a lie half the time.
 */
public sealed interface JudgeOutcome {
    /** No judge covered this stage, or there is no task to route on. */
    public data object NotRun : JudgeOutcome

    /** The judge ran and found nothing. */
    public data object Clean : JudgeOutcome

    /**
     * Earlier replies breached — [rejected] carries each rejected verdict in
     * order — and the agent's rewrite finally satisfied the judge. The turn
     * stands on the rewrite; the objections are still shown, because the user is
     * entitled to know the earlier answers were withdrawn.
     */
    public data class Retried(val rejected: List<InvariantVerdict>) : JudgeOutcome

    /**
     * Every attempt breached; [rejected] carries each verdict in order, newest
     * last. The reply is shown but not persisted, and the task stage is held.
     */
    public data class Blocked(val rejected: List<InvariantVerdict>) : JudgeOutcome
}

/**
 * What the turn engine did with the model's `[[stage:<next>]]` signal — for a
 * view to render into a `[task]` line (or nothing). Mirrors the branches of
 * the current `maybeAdvanceTaskStage`, lifted out of the I/O.
 */
public sealed interface StageAdvance {
    /** Nothing to report: no memory, no active task, no signal, or paused. */
    data object None : StageAdvance

    /** The proposed move was legal and applied: `[task] stage: <from> → <to> (auto)`. */
    data class Advanced(val from: TaskStage?, val to: TaskStage) : StageAdvance

    /**
     * The model named the stage it is already in, so nothing moved. Its own variant
     * rather than [None] because it is a specific, correctable mistake — the marker
     * names a destination, and the model used it to label where it already was. Left
     * silent, this is the FSM's main lock: the model repeats the marker, the engine
     * keeps swallowing it, and neither side learns anything.
     */
    data class Repeated(val stage: TaskStage, val allowed: Set<TaskStage>) : StageAdvance

    /** The model proposed an illegal move; it was ignored and reported. */
    data class Rejected(
        val from: TaskStage?,
        val proposed: TaskStage,
        val allowed: Set<TaskStage>,
    ) : StageAdvance
}
