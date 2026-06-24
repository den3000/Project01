package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.agent.ExecutedToolCall
import ru.den.writes.code.project01.shared.invariant.InvariantVerdict
import ru.den.writes.code.project01.shared.llm.Usage
import ru.den.writes.code.project01.shared.memory.TaskStage

/**
 * The outcome of one turn, as computed by `TurnEngine` WITHOUT any direct I/O.
 * A view renders this; the engine never prints.
 *
 * Everything here is an immutable snapshot — in particular [Ok.session] is a
 * copy, never a live [SessionStats] reference. An async TUI renderer must not
 * be able to race the next turn's accumulation through a shared object (see
 * cliTui/INTEGRATION.md, "UiState — снимки").
 */
internal sealed interface TurnResult {

    /**
     * A successful turn. [reply] is the model text; [modelId] / [profileName]
     * identify the answering agent (for the `[[AGENT:]]` tag in multi-agent
     * sessions and the footer's pricing lookup); [usage] is this turn's token
     * snapshot (null when the provider returned text without counts);
     * [durationMs] is the measured call time; [session] is the running-total
     * snapshot at this turn (null in OneShot — no history to accumulate into,
     * so the footer prints no `session:` line); [stageAdvance] is what
     * happened to the task FSM; [verdict] is the per-stage invariant judge's
     * verdict — [InvariantVerdict.CLEAN] unless a judge flagged a breach, in
     * which case the turn was shown but NOT persisted and the stage was held;
     * [judgeModelId] is the model of the judge that ran (null when none did) —
     * the TUI tags the breach block with it.
     */
    data class Ok(
        val reply: String,
        val modelId: String,
        val profileName: String?,
        val usage: Usage?,
        val durationMs: Long,
        val session: SessionStatsSnapshot?,
        val stageAdvance: StageAdvance,
        val verdict: InvariantVerdict = InvariantVerdict.CLEAN,
        val judgeModelId: String? = null,
        /** Tool calls the agent ran this turn (empty for a plain turn). */
        val executedToolCalls: List<ExecutedToolCall> = emptyList(),
    ) : TurnResult

    /** The turn failed (provider error or empty response). [reason] is the message. */
    data class Failed(val reason: String) : TurnResult
}

/**
 * Immutable copy of a session's running totals at the moment a turn finished.
 * Decouples the footer/summary rendering from the live, still-mutating
 * [SessionStats].
 */
internal data class SessionStatsSnapshot(
    val turns: Int,
    val promptTokens: Int,
    val outputTokens: Int,
    val thoughtsTokens: Int,
    val totalTokens: Int,
    val costUsd: Double,
)

/** Snapshot the live counters into an immutable [SessionStatsSnapshot]. */
internal fun SessionStats.snapshot(): SessionStatsSnapshot = SessionStatsSnapshot(
    turns = turns,
    promptTokens = totalPromptTokens,
    outputTokens = totalOutputTokens,
    thoughtsTokens = totalThoughtsTokens,
    totalTokens = totalTokens,
    costUsd = totalCostUsd,
)

/**
 * What the turn engine did with the model's `[[stage:<next>]]` signal — for a
 * view to render into a `[task]` line (or nothing). Mirrors the branches of
 * the current `maybeAdvanceTaskStage`, lifted out of the I/O.
 */
internal sealed interface StageAdvance {
    /** Nothing to report: no memory, no active task, no signal, paused, or already there. */
    data object None : StageAdvance

    /** The proposed move was legal and applied: `[task] stage: <from> → <to> (auto)`. */
    data class Advanced(val from: TaskStage?, val to: TaskStage) : StageAdvance

    /** The model proposed an illegal move; it was ignored and reported. */
    data class Rejected(
        val from: TaskStage?,
        val proposed: TaskStage,
        val allowed: Set<TaskStage>,
    ) : StageAdvance
}
