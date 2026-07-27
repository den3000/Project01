package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.TestScope
import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.StageAdvance
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnResult
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.buildLlmApi
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.fail

/**
 * What a run of the engine amounts to, and what a batch of runs is reported as.
 *
 * The records ([TurnLog] / [RunLog]) are filled in by the driver in `TurnEngineFixture.kt`
 * and serve both suites; everything around them is the live stand's, and only the stand
 * needs it: a run there is many turns, the model is nondeterministic, and the question is
 * never "did this turn pass" but "how often, and where did it get stuck". So nothing here
 * asserts — it measures, and the tables are the output.
 */

//region прогон

/**
 * Run one task to completion (or to [MAX_TURNS]) against [modelProvider], and log every turn.
 *
 * The live face of the driver in `TurnEngineFixture.kt`: same run, only the [LlmApi] is the
 * real provider built off the graph, the turn budget is the stand's, and every turn is
 * printed as it lands.
 */
internal suspend fun TestScope.runTurnEngineWith(
    modelProvider: ModelProvider,
    sessionName: String,
    taskNotes: TaskNotes?,
    stallHint: Boolean = false,
    engineUnderTest: EngineUnderTest = INLINE_ENGINE,
    turns: Int = MAX_TURNS,
): RunLog = runTurnEngineWith(
    llmApi = { koin -> buildLlmApi(modelProvider, koin.get()) },
    engineUnderTest = engineUnderTest,
    task = taskNotes,
    turns = turns,
    stallHint = stallHint,
    modelProvider = modelProvider,
    sessionName = sessionName,
    logTurns = true,
)

//endregion

//region логи прогона

/**
 * One turn, as the run left it: what it was asked, where the task stood, and the raw
 * [TurnResult] it came back with. Everything else is read off that result rather than
 * copied out of it — an offline test asserting on `result` and a table counting outcomes
 * are then looking at the same thing, with no mapping in between to disagree.
 */
internal data class TurnLog(
    val index: Int,
    val stageBefore: TaskStage?,
    val prompt: String,
    val result: TurnResult,
) {
    private val ok: TurnResult.Ok? get() = result as? TurnResult.Ok
    private val advance: StageAdvance? get() = ok?.stageAdvance

    val reply: String get() = ok?.reply.orEmpty()
    val judge: String get() = ok?.judge?.let { it::class.simpleName }.orEmpty()
    val outputTokens: Int? get() = ok?.usage?.outputTokens
    val thoughtsTokens: Int? get() = ok?.usage?.thoughtsTokens
    val durationMs: Long get() = ok?.durationMs ?: 0
    val replayErrorReason: String get() = (result as? TurnResult.Failed)?.reason.orEmpty()
    val advanceErrorReason: String get() = if (rejected) "NOT ALLOWED" else ""

    /** The stage the model asked for, legal or not; null when it named none. */
    val suggestedNextStage: TaskStage?
        get() = when (val moved = advance) {
            is StageAdvance.Advanced -> moved.to
            is StageAdvance.Rejected -> moved.proposed
            else -> null
        }

    /** What the turn did to the FSM. */
    val failed: Boolean get() = result is TurnResult.Failed
    val rejected: Boolean get() = advance is StageAdvance.Rejected
    val advanced: Boolean get() = advance is StageAdvance.Advanced

    /** The model named the stage it was already in — a no-move it can be told about. */
    val repeated: Boolean get() = advance is StageAdvance.Repeated

    /** Left the stage put, whichever way — what the engine counts toward a stall. */
    val noMove: Boolean get() = !advanced && !rejected && !failed

    /** What the FSM charged for this turn; empty from an engine that keeps its own. */
    private val fsm: RetryOutcome? get() = result.retryOutcome

    val fsmOutcome: String
        get() = when (fsm) {
            null -> ""
            is RetryOutcome.Retried -> "RETRIED"
            is RetryOutcome.Restarted -> "RESTARTED"
            is RetryOutcome.GaveUp -> "GAVE_UP"
        }

    /** Why the run was abandoned; only a give-up names it. */
    val gaveUpReason: String get() = (fsm as? RetryOutcome.GaveUp)?.reason?.name.orEmpty()

    /** Budgets as they stood after this turn — the counters ticking, turn by turn. */
    val stageSpent: Int? get() = fsm?.task?.stageRetryState?.attempt
    val taskSpent: Int? get() = fsm?.task?.taskRetryState?.attempt
    val transportSpent: Int? get() = fsm?.task?.transportRetryState?.attempt

    val outcome: String
        get() = when {
            failed -> "FAILED"
            rejected -> "REJECTED"
            advanced -> "ADVANCED"
            repeated -> "REPEATED"
            else -> "NO_MOVE"
        }

    fun formatted(): String = buildString {
        append("================================= turn $index ================================")
        append("\nstageBefore: ${stageBefore ?: "NO_STAGE"}")
        append(" suggestedNextStage: ${suggestedNextStage ?: "NO_STAGE"}")
        append(" outcome: $outcome")
        append("\ntokens: out=${outputTokens ?: "-"} thoughts=${thoughtsTokens ?: "-"}")
        append(" durationMs=$durationMs judge=${judge.ifEmpty { "-" }}")
        fsmOutcome.takeIf { it.isNotEmpty() }?.let {
            append("\nfsm: $it spent[stage=${stageSpent} task=${taskSpent} transport=${transportSpent}]")
            gaveUpReason.takeIf { r -> r.isNotEmpty() }?.let { r -> append(" reason=$r") }
        }
        replayErrorReason.takeIf { it.isNotEmpty() }?.let { append("\nreplayErrorReason: $it") }
        advanceErrorReason.takeIf { it.isNotEmpty() }?.let { append("\nadvanceErrorReason: $it") }
        append("\n- prompt: $prompt")
        reply.takeIf { it.isNotEmpty() }?.let { append("\n- reply: ${it.take(300)}...") }
    }
}

/** One maximal span of consecutive NO_MOVE turns: how long, in which stage, and how it ended. */
private data class StallSpan(val length: Int, val stage: TaskStage?, val brokeOut: Boolean)

/**
 * One finished run: every turn it took, the stage it left the task at, and the history it
 * persisted. The stand reads the counters below; an offline test reads the raw results and
 * the persisted side through [ok] / [results] / [persistedMessages].
 */
internal data class RunLog(
    val modelId: String,
    val sessionName: String,
    val taskId: String?,
    val finalStage: TaskStage?,
    val turnLogs: List<TurnLog>,
    /** History as the next session would load it: both sides of every persisted turn. */
    val persistedMessages: List<Message>,
    /** Rows in the message table — 0 proves a failed turn persisted nothing. */
    val persistedCount: Int,
) {
    /** What each turn came back with, in order. */
    val results: List<TurnResult> get() = turnLogs.map { it.result }

    /** Turn [index]'s result as [TurnResult.Ok] — a turn that failed fails the test here. */
    fun ok(index: Int = 0): TurnResult.Ok =
        results[index] as? TurnResult.Ok ?: fail("turn $index did not pass: ${results[index]}")

    /** What turn [index] did to the task stage. */
    fun advance(index: Int = 0): StageAdvance = ok(index).stageAdvance

    val reachedDone: Boolean get() = finalStage == TaskStage.DONE
    val advances: Int get() = turnLogs.count { it.advanced }
    val rejects: Int get() = turnLogs.count { it.rejected }
    val noMoves: Int get() = turnLogs.count { it.noMove }

    /** How much of [noMoves] was the model re-signalling its stage rather than silence. */
    val repeats: Int get() = turnLogs.count { it.repeated }
    val failures: Int get() = turnLogs.count { it.failed }
    /** Turns the FSM charged as an ordinary retry — the task carried on. */
    val fsmRetries: Int get() = turnLogs.count { it.fsmOutcome == "RETRIED" }

    /** Turns that escalated into a restart. The stand only records it: nobody executes it here. */
    val fsmRestarts: Int get() = turnLogs.count { it.fsmOutcome == "RESTARTED" }

    /** The run the machine abandoned, with the reason that finally broke it. */
    val fsmGaveUp: Boolean get() = turnLogs.any { it.fsmOutcome == "GAVE_UP" }
    val fsmGaveUpReason: String get() = turnLogs.lastOrNull { it.gaveUpReason.isNotEmpty() }?.gaveUpReason.orEmpty()

    /** Budgets as the run left them; null from an engine that charges nothing. */
    val stageSpent: Int? get() = turnLogs.lastOrNull { it.stageSpent != null }?.stageSpent
    val taskSpent: Int? get() = turnLogs.lastOrNull { it.taskSpent != null }?.taskSpent
    val transportSpent: Int? get() = turnLogs.lastOrNull { it.transportSpent != null }?.transportSpent

    val outputTokens: Int get() = turnLogs.sumOf { it.outputTokens ?: 0 }
    val thoughtsTokens: Int get() = turnLogs.sumOf { it.thoughtsTokens ?: 0 }
    val durationMs: Long get() = turnLogs.sumOf { it.durationMs }

    /** Every maximal span of consecutive NO_MOVE turns, in order. */
    private val noMoveSpans: List<StallSpan>
        get() {
            val spans = mutableListOf<StallSpan>()
            var len = 0
            var stage: TaskStage? = null
            turnLogs.forEach { turn ->
                if (turn.noMove) {
                    // The stage cannot change across a NO_MOVE span, so the first turn's
                    // `stageBefore` names the stage the whole span sat in.
                    if (len == 0) stage = turn.stageBefore
                    len++
                } else {
                    if (len > 0) spans += StallSpan(len, stage, brokeOut = turn.advanced)
                    len = 0
                }
            }
            if (len > 0) spans += StallSpan(len, stage, brokeOut = false)
            return spans
        }

    /**
     * Spans long enough to count as a stall episode. [STALL_STREAK_LIMIT] NO_MOVE turns
     * in a row inside a stage is exactly what arms the engine's stall nudge, so these
     * runs — and only these — are the population an A/B over that nudge is about. The
     * stage check is not redundant: a task-less run is all NO_MOVE and never nudged.
     */
    private val stallSpans: List<StallSpan>
        get() = noMoveSpans.filter { it.length >= STALL_STREAK_LIMIT && it.stage != null }

    /** Longest NO_MOVE streak of any length; 0 when the stage never repeated. */
    val longestStall: Int get() = noMoveSpans.maxOfOrNull { it.length } ?: 0

    /** Sat through at least one stall episode — the nudge had something to fix here. */
    val stalled: Boolean get() = stallSpans.isNotEmpty()

    /** Broke out of at least one stall episode with a real stage advance. */
    val recovered: Boolean get() = stallSpans.any { it.brokeOut }

    /**
     * The stage the run actually got stuck in — the longest episode's, when there were
     * several. Not derivable from [finalStage]: a run that broke out and finished ends
     * at DONE, which hides where it had been sitting.
     */
    val stalledAt: TaskStage? get() = stallSpans.maxByOrNull { it.length }?.stage

    fun formatted(): String = buildString {
        append("---- run [$modelId] task=$taskId ----")
        append("\n  reachedDone=$reachedDone finalStage=${finalStage ?: "NO_STAGE"} turns=${turnLogs.size}")
        append("\n  advances=$advances rejects=$rejects noMoves=$noMoves failures=$failures")
        append("\n  fsm: retries=$fsmRetries restarts=$fsmRestarts gaveUp=$fsmGaveUp")
        append(" spent[stage=${stageSpent ?: "-"} task=${taskSpent ?: "-"} transport=${transportSpent ?: "-"}]")
        append("\n  tokens: out=$outputTokens thoughts=$thoughtsTokens durationMs=$durationMs")
    }
}

//endregion

//region отчёт

/** A labelled batch of runs — one model, or one arm of an A/B (baseline vs hint). */
internal data class RunGroup(val label: String, val runs: List<RunLog>)

/**
 * The one report every grouped live test prints, in four widening zooms:
 *   1. per-group detail — the turn-by-turn block for each run;
 *   2. PER-RUN table — one row per run, raw counts, so every run is visible/comparable;
 *   3. PER-GROUP table — one row per group, counts averaged per run;
 *   4. PER-STALL-STAGE table — stalls and break-outs split by the stage they happened in.
 * Takes any number of groups, so new grouped tests reuse it as-is.
 *
 * `stalled`/`recov` carry the conditional read: a run that never sits still is one the
 * stall nudge never touches, so an overall `done` rate mixes those in and dilutes
 * whatever the nudge did. `recov` is scored over the stalled runs alone.
 */
internal fun reportGroups(groups: List<RunGroup>) {
    groups.forEach { printGroupDetail(it) }
    printTable(
        "PER-RUN (raw, every run a row)",
        listOf(
            "group", "run", "done", "turns", "adv", "rej", "noMv", "rep", "fail",
            "stall", "recov", "stalledAt", "rtry", "rstrt", "gvUp", "spent(s/t/x)",
            "out", "thghts", "ms", "stage",
        ),
        groups.flatMap { g ->
            g.runs.mapIndexed { i, r ->
                listOf(
                    g.label,
                    "${i + 1}",
                    if (r.reachedDone) "Y" else "-",
                    "${r.turnLogs.size}",
                    "${r.advances}",
                    "${r.rejects}",
                    "${r.noMoves}",
                    "${r.repeats}",
                    "${r.failures}",
                    "${r.longestStall}",
                    if (!r.stalled) "-" else if (r.recovered) "Y" else "N",
                    r.stalledAt?.name ?: "-",
                    "${r.fsmRetries}",
                    "${r.fsmRestarts}",
                    if (r.fsmGaveUp) r.fsmGaveUpReason else "-",
                    "${r.stageSpent ?: "-"}/${r.taskSpent ?: "-"}/${r.transportSpent ?: "-"}",
                    "${r.outputTokens}",
                    "${r.thoughtsTokens}",
                    "${r.durationMs}",
                    r.finalStage?.name ?: "-",
                )
            }
        },
    )
    printTable(
        "PER-GROUP (averaged per run)",
        listOf(
            "group", "done", "stalled", "recov", "turns", "adv", "rej", "noMv", "rep",
            "fail", "rtry", "rstrt", "gvUp", "out", "thghts", "ms",
        ),
        groups.map { g ->
            val n = g.runs.size.coerceAtLeast(1)
            val stalledRuns = g.runs.count { it.stalled }
            fun avg(sel: (RunLog) -> Number) = g.runs.sumOf { sel(it).toDouble() } / n
            listOf(
                g.label,
                "${g.runs.count { it.reachedDone }}/${g.runs.size}",
                "$stalledRuns/${g.runs.size}",
                if (stalledRuns == 0) "-" else "${g.runs.count { it.recovered }}/$stalledRuns",
                "%.1f".format(avg { it.turnLogs.size }),
                "%.1f".format(avg { it.advances }),
                "%.1f".format(avg { it.rejects }),
                "%.1f".format(avg { it.noMoves }),
                "%.1f".format(avg { it.repeats }),
                "%.1f".format(avg { it.failures }),
                "%.1f".format(avg { it.fsmRetries }),
                "%.1f".format(avg { it.fsmRestarts }),
                "${g.runs.count { it.fsmGaveUp }}/${g.runs.size}",
                "%.0f".format(avg { it.outputTokens }),
                "%.0f".format(avg { it.thoughtsTokens }),
                "%.0f".format(avg { it.durationMs }),
            )
        },
    )
    printStallStageTable(groups)
}

/**
 * Where the runs actually got stuck, and whether the nudge got them out of each stage.
 * The question is per stage — a nudge worded for one stage cannot be judged by a rate
 * that pools every stage together, and [RunLog.finalStage] hides the answer precisely
 * for the runs that broke out (they end at DONE). Skipped when nothing stalled.
 */
private fun printStallStageTable(groups: List<RunGroup>) {
    val rows = groups.flatMap { g ->
        g.runs.filter { it.stalled }
            .groupBy { it.stalledAt }
            .toList()
            .sortedBy { (stage, _) -> stage?.ordinal ?: -1 }
            .map { (stage, runs) ->
                listOf(
                    g.label,
                    stage?.name ?: "NO_STAGE",
                    "${runs.size}",
                    "${runs.count { it.recovered }}/${runs.size}",
                    "%.1f".format(runs.sumOf { it.longestStall.toDouble() } / runs.size),
                )
            }
    }
    if (rows.isEmpty()) return
    printTable(
        "PER-STALL-STAGE (where runs got stuck)",
        listOf("group", "stalledAt", "stalled", "recov", "avgLen"),
        rows,
    )
}

private fun printGroupDetail(group: RunGroup) {
    val reached = group.runs.count { it.reachedDone }
    val stalledRuns = group.runs.count { it.stalled }
    val turns = group.runs.map { it.turnLogs.size }
    println("========================= SUMMARY: ${group.label} =========================")
    println("reachedDone $reached/${group.runs.size} — turns per run $turns")
    val recovered = if (stalledRuns == 0) "-" else "${group.runs.count { it.recovered }}/$stalledRuns"
    println("stalled $stalledRuns/${group.runs.size} — recovered $recovered")
    group.runs.forEach { println(it.formatted()) }
}

/** Print a titled table: header + rows, label column left-aligned, numeric columns right. */
private fun printTable(title: String, header: List<String>, rows: List<List<String>>) {
    val all = listOf(header) + rows
    val widths = header.indices.map { c -> all.maxOf { it[c].length } }
    fun line(row: List<String>) =
        row.mapIndexed { c, v -> if (c == 0) v.padEnd(widths[c]) else v.padStart(widths[c]) }.joinToString("  ")
    println("\n===== $title =====")
    println(line(header))
    rows.forEach { println(line(it)) }
}

//endregion
