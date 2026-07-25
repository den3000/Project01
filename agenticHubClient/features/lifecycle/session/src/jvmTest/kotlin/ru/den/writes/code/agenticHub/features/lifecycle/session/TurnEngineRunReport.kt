package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.test.TestScope
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.StageAdvance
import ru.den.writes.code.agenticHub.features.lifecycle.session.turn.TurnResult
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.buildLlmApi
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage

/**
 * Driving a whole task to its end against a real model, and reporting what happened.
 *
 * Only the live stand needs this: a run is many turns, the model is nondeterministic, and
 * the question is never "did this turn pass" but "how often, and where did it get stuck".
 * So nothing here asserts — it measures, and the tables are the output. The engine under
 * it is built by `TurnEngineFixture.kt`.
 */

//region прогон

/** Run one task to completion (or to [MAX_TURNS]) against [modelProvider], and log every turn. */
internal suspend fun TestScope.runTurnEngineWith(
    modelProvider: ModelProvider,
    sessionName: String,
    taskNotes: TaskNotes?,
    stallHint: Boolean = false,
): RunLog = withTurnEngine(
    llmApi = { koin -> buildLlmApi(modelProvider, koin.get()) },
    task = taskNotes,
    stallHint = stallHint,
    modelProvider = modelProvider,
    sessionName = sessionName,
    prompt = OPENING_PROMPT,
) {
    val turns = runTurns(taskNotes)
    RunLog(
        modelId = modelProvider.modelId,
        sessionName = sessionName,
        taskId = taskNotes?.taskId,
        finalStage = taskNotes?.let { stageOf(it.taskId) },
        turnLogs = turns,
    )
}

/**
 * Feed the engine turns until the task reaches DONE or [MAX_TURNS] runs out, logging each.
 * The first turn carries [OPENING_PROMPT], the rest [FOLLOW_UP_PROMPT] — the same "continue"
 * a headless demo pipes in, which is what provokes the degeneration this stand measures.
 */
private suspend fun TurnEngineFixture.runTurns(taskNotes: TaskNotes?): List<TurnLog> =
    (0..<MAX_TURNS).mapNotNull { index ->
        val stageBefore = taskNotes?.let { stageOf(it.taskId) }
        if (stageBefore == TaskStage.DONE) return@mapNotNull null

        val prompt = if (index == 0) OPENING_PROMPT else FOLLOW_UP_PROMPT
        val turnLog = when (val result = engine.turn(prompt)) {
            is TurnResult.Failed -> TurnLog(
                index = index,
                stageBefore = stageBefore,
                prompt = prompt,
                reply = "",
                suggestedNextStage = null,
                replayErrorReason = result.reason,
            )

            is TurnResult.Ok -> {
                val base = TurnLog(
                    index = index,
                    stageBefore = stageBefore,
                    prompt = prompt,
                    reply = result.reply,
                    suggestedNextStage = null,
                    judge = result.judge::class.simpleName.orEmpty(),
                    outputTokens = result.usage?.outputTokens,
                    thoughtsTokens = result.usage?.thoughtsTokens,
                    durationMs = result.durationMs,
                )
                when (val advance = result.stageAdvance) {
                    StageAdvance.None -> base
                    is StageAdvance.Advanced -> base.copy(suggestedNextStage = advance.to)
                    is StageAdvance.Rejected ->
                        base.copy(suggestedNextStage = advance.proposed, advanceErrorReason = "NOT ALLOWED")

                    is StageAdvance.Repeated -> base.copy(repeatedStage = advance.stage)
                }
            }
        }

        println(turnLog.formatted())
        return@mapNotNull turnLog
    }

//endregion

//region логи прогона

internal data class TurnLog(
    val index: Int,
    val stageBefore: TaskStage?,
    val prompt: String,
    val reply: String,
    val suggestedNextStage: TaskStage?,
    val judge: String = "",
    val outputTokens: Int? = null,
    val thoughtsTokens: Int? = null,
    val durationMs: Long = 0,
    val replayErrorReason: String = "",
    val advanceErrorReason: String = "",
    val repeatedStage: TaskStage? = null,
) {
    /** What the turn did to the FSM, derived from the fields already captured. */
    val failed: Boolean get() = replayErrorReason.isNotEmpty()
    val rejected: Boolean get() = advanceErrorReason.isNotEmpty()
    val advanced: Boolean get() = suggestedNextStage != null && !rejected && !failed

    /** The model named the stage it was already in — a no-move it can be told about. */
    val repeated: Boolean get() = repeatedStage != null

    /** Left the stage put, whichever way — what the engine counts toward a stall. */
    val noMove: Boolean get() = !advanced && !rejected && !failed

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
        replayErrorReason.takeIf { it.isNotEmpty() }?.let { append("\nreplayErrorReason: $it") }
        advanceErrorReason.takeIf { it.isNotEmpty() }?.let { append("\nadvanceErrorReason: $it") }
        append("\n- prompt: $prompt")
        reply.takeIf { it.isNotEmpty() }?.let { append("\n- reply: ${it.take(300)}...") }
    }
}

/** One maximal span of consecutive NO_MOVE turns: how long, in which stage, and how it ended. */
private data class StallSpan(val length: Int, val stage: TaskStage?, val brokeOut: Boolean)

internal data class RunLog(
    val modelId: String,
    val sessionName: String,
    val taskId: String?,
    val finalStage: TaskStage?,
    val turnLogs: List<TurnLog>,
) {
    val reachedDone: Boolean get() = finalStage == TaskStage.DONE
    val advances: Int get() = turnLogs.count { it.advanced }
    val rejects: Int get() = turnLogs.count { it.rejected }
    val noMoves: Int get() = turnLogs.count { it.noMove }

    /** How much of [noMoves] was the model re-signalling its stage rather than silence. */
    val repeats: Int get() = turnLogs.count { it.repeated }
    val failures: Int get() = turnLogs.count { it.failed }
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
            "stall", "recov", "stalledAt", "out", "thghts", "ms", "stage",
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
            "fail", "out", "thghts", "ms",
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
