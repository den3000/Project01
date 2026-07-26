package ru.den.writes.code.agenticHub.features.lifecycle.session.turn

import ru.den.writes.code.agenticHub.features.memory.TurnContext
import ru.den.writes.code.agenticHub.features.memory.ContextStrategy
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.lifecycle.session.RagControl
import ru.den.writes.code.agenticHub.features.llm.ragChunksToContextMessage
import ru.den.writes.code.agenticHub.features.memory.db.HistoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.ProfileSection
import ru.den.writes.code.agenticHub.features.agent.AgentConfig
import ru.den.writes.code.agenticHub.features.agent.AgentResponder
import ru.den.writes.code.agenticHub.features.agent.ExecutedToolCall
import ru.den.writes.code.agenticHub.features.agent.ToolCallLog
import ru.den.writes.code.agenticHub.features.llm.Usage
import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk
import ru.den.writes.code.agenticHub.features.agent.invariant.InvariantVerdict
import ru.den.writes.code.agenticHub.features.agent.invariant.JudgeInput
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.llm.ToolDefinition
import ru.den.writes.code.agenticHub.features.llm.ToolExecutor
import ru.den.writes.code.agenticHub.features.memory.TaskBinding
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import ru.den.writes.code.agenticHub.features.memory.TaskStateMachine
import kotlin.time.measureTimedValue

/**
 * The pure engine for one turn: wire assembly → LLM call → persistence →
 * task-stage advance, returning a [TurnResult]. Does NO direct I/O — no
 * `println`, no `System.err`, no feed throttle. A view renders the result;
 * the view-model orchestrates the loop around it.
 *
 * This is the per-turn `send` with the printing, the `/reuse` cache hook and
 * the `delay(16s)` removed (the throttle belongs on the feed intent source).
 * Persistence and the FSM write stay here — they aren't stdout/stderr I/O.
 */
public class InlineFsmTurnEngine(
    private val cliArgs: StartCommand.SessionInitialState,
    private val llmApi: LlmApi,
    private val historyStore: HistoryStore?,
    private val strategy: ContextStrategy = ContextStrategy.FullHistory,
    private val memory: MemoryProvider? = null,
    private val routedAgents: List<RoutedAgent> = emptyList(),
    /**
     * Per-stage invariant judges: each owns a [TaskBinding] span and audits the
     * reply of any turn whose active task stage falls in it. Empty (the
     * default) = no judging — byte-identical to before. Needs per-stage agents
     * plus an active task to route on (enforced at parse time, see `CliArgsToStartCommandMapper`).
     */
    private val routedJudges: List<RoutedJudge> = emptyList(),
    /**
     * Tool declarations offered to the default agent (from an MCP server via
     * `-mcpServer`) plus the [ToolExecutor] that runs them. Empty / null
     * (default) = no tools — the agent makes a single LLM call exactly as before.
     */
    private val toolDefs: List<ToolDefinition> = emptyList(),
    private val toolExecutor: ToolExecutor? = null,
    /**
     * Active RAG index for the session (armed by `/rag <name>`). When present, each
     * turn retrieves top-K chunks for the prompt and injects them as grounding
     * context; null / no active index → no retrieval, byte-identical to before.
     */
    private val ragControl: RagControl? = null,
    /**
     * When true, a stage the model refuses to leave for [STALL_STREAK_LIMIT] passed
     * turns running (it keeps signalling the current stage, or none) earns a one-shot
     * `[fsm]` nudge on the following turn (see [stallHintMessage]). Off by default so
     * task-less chat and the existing tests stay byte-identical; the composition root
     * turns it on for real sessions, the stand toggles it per group to measure it.
     */
    private val stallHint: Boolean = false,
    /**
     * Session-wide record of what the tools actually did — the judge's evidence.
     * Injected rather than owned so its eviction rule stays testable on its own;
     * the default is the ordinary per-session log.
     */
    private val toolCallLog: ToolCallLog = ToolCallLog(),
) : TurnEngine {
    /**
     * The default agent: this engine's model surface + generation knobs, no
     * pinned profile. Answers every turn no [routedAgents] binding covers (and
     * when there's no task / stage) — an empty list reproduces single-agent
     * behaviour exactly.
     */
    // Tools are offered per call in [turn] (see [AgentResponder.respond]) so they reach
    // the routed stage agent that actually answers, not just this fallback — hence no
    // tools baked into its config here.
    private val fallbackAgent = RoutedAgent(
        binding = TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE),
        responder = AgentResponder(
            AgentConfig(llmApi = llmApi, params = cliArgs.toGenerationParams()),
        ),
        profileName = null,
        modelId = cliArgs.modelProvider.modelId,
    )

    /** The agent for [stage]: first routed binding that spans it, else the fallback. */
    private fun agentFor(stage: TaskStage?): RoutedAgent =
        stage?.let { s -> routedAgents.firstOrNull { s in it.binding } } ?: fallbackAgent

    /**
     * The per-stage judge whose binding spans [stage], or null when none does.
     * Same first-wins, declaration-order resolution as [agentFor].
     */
    private fun judgeFor(stage: TaskStage): RoutedJudge? =
        routedJudges.firstOrNull { stage in it.binding }

    /**
     * A stage move the engine rejected on the previous turn, held so the NEXT
     * turn can tell the model its signal was refused and the stage stayed put.
     * The rejection is otherwise invisible to the model — the engine holds the
     * stage silently and only the view sees [StageAdvance.Rejected] — so without
     * this the model re-sends the same illegal skip every turn and the task
     * never advances (the observed `planning → done` loop).
     *
     * Deliberately ephemeral (mutable state, like [toolCallLog], not persisted):
     * surfaced once then cleared. It must not reach [historyStore] — a SYSTEM
     * note there would be re-concatenated into the provider's system slot every
     * turn and go stale the moment the stage actually changed; a USER note there
     * would sit two-USER-deep against the next `продолжай`. Safe as a bare var:
     * turns are serialized (`SessionViewModel.drive` runs them one at a time).
     */
    private var pendingStageRejection: StageAdvance.Rejected? = null

    /**
     * The model named the stage it was already in on the previous turn, held so the NEXT
     * turn can say so. Same reasoning as [pendingStageRejection] and the same ephemeral
     * handling — and the same failure without it, only quieter: a re-signal is not even
     * an illegal move, so the engine used to swallow it in silence. The model then had no
     * way to learn that its marker did nothing, and simply sent it again.
     */
    private var pendingStageRepeat: StageAdvance.Repeated? = null

    /**
     * A stage the model has refused to leave for [STALL_STREAK_LIMIT] passed turns
     * running (it keeps signalling the current stage, or emits no marker) — held so
     * the NEXT turn can nudge it toward the next stage. Ephemeral and surfaced-once,
     * exactly like [pendingStageRejection]; only ever armed when [stallHint] is on.
     */
    private var pendingStallHint: TaskStage? = null

    /** Consecutive passed turns that left an active stage put; see [updateStallHint]. */
    private var stallStreak = 0

    /**
     * Run one turn for [prompt]. Builds «memory layer + planned history +
     * user turn», calls the routed agent, persists both sides on success,
     * applies any legal task-stage move, and returns an immutable [TurnResult].
     */
    override suspend fun turn(prompt: String): TurnResult {
        // The agent that answers is picked by the active task's stage; with no
        // routed agents that's always the fallback (single-agent parity). The whole
        // task is loaded (not just the stage) so the stall check can see `paused`.
        val task = memory?.activeTaskId()?.let { memory.store.loadTask(it) }
        val stage = task?.stage
        val agent = agentFor(stage)
        val modelId = agent.modelId
        // Per-turn strategy side effect (rolling-summary compaction, facts
        // extraction, …) runs BEFORE the request is built and before this
        // turn's pair is appended — the strategies rely on an even-length
        // history here. Compaction runs on the default model regardless of the
        // routed agent, so TurnContext keeps the default model id.
        historyStore?.let { strategy.onTurn(TurnContext(it, llmApi, prompt, cliArgs.modelProvider.modelId)) }
        val userTurn = Message(role = Role.USER, text = prompt)
        // RAG: retrieve top-K chunks for this prompt and inject them as a grounding
        // SYSTEM turn ABOVE the planned history. No active index / empty hit → no
        // injection, and `retrieval` stays empty (the view shows no sources).
        val active = ragControl?.active
        val retrieval = active?.retriever?.retrieve(prompt, active.topK).orEmpty()
        val ragContext = if (retrieval.isEmpty()) emptyList() else listOf(ragChunksToContextMessage(retrieval))
        // A stage move rejected on the previous turn is surfaced to the model HERE, as an
        // ephemeral SYSTEM line at the top of this turn's context — consumed once and
        // cleared (see [pendingStageRejection]). Above rag + history so it lands right
        // under the memory layer in the provider's system slot.
        val stageFeedback = listOfNotNull(
            pendingStageRejection?.let(::stageRejectionMessage),
            pendingStageRepeat?.let(::stageRepeatMessage),
            pendingStallHint?.let(::stallHintMessage),
        )
        pendingStageRejection = null
        pendingStageRepeat = null
        pendingStallHint = null
        val baseContext =
            stageFeedback + ragContext + (historyStore?.let { strategy.planContext(it.messages) } ?: emptyList())
        // Memory layer (profile / rules / current task) sits ABOVE the history
        // tail so it stays stable across turns. Empty when no MemoryProvider —
        // byte-identical to the no-memory path.
        val memoryLayer = memory?.memoryLayerFor(agent.profileName) ?: emptyList()
        val mem = memory
        val judge = if (mem != null) stage?.let(::judgeFor) else null

        // The attempt loop. A flagged reply is not thrown away silently: the agent is
        // handed the objections and rewrites, and only a second breach blocks the turn.
        // Dropping it outright taught the agent nothing — it lost the turn and repeated
        // the same answer next time — while a false positive cost the user their work.
        // Everything above the loop (compaction, retrieval, the memory layer) is
        // per-turn, not per-attempt, and stays out of it.
        var attemptContext = baseContext
        var attemptTurn = userTurn
        val rejectedVerdicts = mutableListOf<InvariantVerdict>()
        var lastText = ""
        var totalUsage: Usage? = null
        var totalDuration = 0L
        val allToolCalls = mutableListOf<ExecutedToolCall>()

        repeat(MAX_JUDGE_ATTEMPTS) { attempt ->
            val (outcome, duration) = measureTimedValue {
                agent.responder.respond(
                    baseContext = attemptContext,
                    memoryLayer = memoryLayer,
                    userTurn = attemptTurn,
                    toolDefs = toolDefs.ifEmpty { null },
                    toolExecutor = toolExecutor,
                )
            }
            val result = outcome.result
            totalUsage = totalUsage plus result.usage
            totalDuration += duration.inWholeMilliseconds
            allToolCalls += outcome.executedToolCalls
            // Recorded before the verdict and regardless of it: a tool that ran has
            // already had its effect, and hiding it from the next turn's judge would
            // make a real ticket look invented.
            toolCallLog.record(outcome.executedToolCalls)

            val text = result.text
            if (result.error != null || text == null) {
                // A retry that fails on the wire must not cost the turn its first answer:
                // fall back to blocking on what the judge already said, which is exactly
                // the behaviour before retries existed.
                if (rejectedVerdicts.isEmpty()) {
                    return TurnResult.Failed(result.error ?: "empty response with no usage")
                }
                return blockedTurn(
                    lastText, agent, modelId, judge, rejectedVerdicts.toList(),
                    totalUsage, totalDuration, allToolCalls, retrieval,
                )
            }
            lastText = text

            val verdict = judgeVerdict(judge, mem, agent, text, prompt, stage)
            if (verdict.passed) {
                historyStore?.append(userTurn)
                historyStore?.append(
                    Message(role = Role.ASSISTANT, text = text),
                    usage = totalUsage,
                    modelId = modelId,
                )
                // Re-arm the pending rejection so the next turn can surface it to the model
                // (see [pendingStageRejection]). Any prior value was already consumed at the
                // top of this turn; only a fresh rejection here sets it again.
                val advance = advanceTaskStage(outcome.proposedStage)
                if (advance is StageAdvance.Rejected) pendingStageRejection = advance
                if (advance is StageAdvance.Repeated) pendingStageRepeat = advance
                if (stallHint) updateStallHint(advance, stage, task?.paused == true)
                return TurnResult.Ok(
                    reply = text,
                    modelId = modelId,
                    profileName = agent.profileName,
                    usage = totalUsage,
                    durationMs = totalDuration,
                    session = historyStore?.stats?.snapshot(),
                    stageAdvance = advance,
                    judge = when {
                        judge == null -> JudgeOutcome.NotRun
                        rejectedVerdicts.isNotEmpty() -> JudgeOutcome.Retried(rejectedVerdicts.toList())
                        else -> JudgeOutcome.Clean
                    },
                    judgeModelId = judge?.modelId,
                    executedToolCalls = allToolCalls.toList(),
                    retrieval = retrieval,
                )
            }

            rejectedVerdicts += verdict
            if (attempt == MAX_JUDGE_ATTEMPTS - 1) {
                return blockedTurn(
                    text, agent, modelId, judge, rejectedVerdicts.toList(),
                    totalUsage, totalDuration, allToolCalls, retrieval,
                )
            }
            // Set up the rewrite. The rejected reply joins the context so the agent can
            // see what it wrote, and the objections become the new user turn: the wire
            // reads as ask → answer → auditor objected → rewrite. Feeding the critique
            // as SYSTEM would tear it away from the reply (providers lift SYSTEM into
            // their own slot), and replacing the user turn outright would delete the
            // question, which lives nowhere else until the turn is persisted.
            attemptContext = attemptContext + attemptTurn + Message(
                role = Role.ASSISTANT,
                text = TaskStateMachine.stripStageSignal(text),
            )
            attemptTurn = Message(role = Role.USER, text = judgeFeedback(verdict))
        }
        error("unreachable: the attempt loop always returns")
    }

    /** Ask the judge covering this stage; no judge or no memory means nothing to enforce. */
    private suspend fun judgeVerdict(
        judge: RoutedJudge?,
        mem: MemoryProvider?,
        agent: RoutedAgent,
        text: String,
        prompt: String,
        stage: TaskStage?,
    ): InvariantVerdict {
        if (judge == null || mem == null) return InvariantVerdict.CLEAN
        // One profile read, sliced into what the judge may enforce (constraints) and
        // what only informs its reading (format / style) — `context` stays out by
        // construction, it tells the agent how to work, not what is forbidden.
        val profile = mem.profileDataForAgent(agent.profileName)
        return judge.checker.check(
            JudgeInput(
                assistantReply = text,
                userMessage = prompt,
                toolCalls = toolCallLog.calls,
                droppedToolCalls = toolCallLog.dropped,
                rules = mem.store.listRules(),
                constraints = profile?.items(ProfileSection.CONSTRAINTS).orEmpty(),
                format = profile?.items(ProfileSection.FORMAT).orEmpty(),
                style = profile?.items(ProfileSection.STYLE).orEmpty(),
                stage = stage,
            ),
        )
    }

    /**
     * The turn every attempt failed: the last reply is shown but not persisted, so
     * the breach can't poison later context, and the stage is held.
     */
    private fun blockedTurn(
        reply: String,
        agent: RoutedAgent,
        modelId: String,
        judge: RoutedJudge?,
        rejected: List<InvariantVerdict>,
        usage: Usage?,
        durationMs: Long,
        toolCalls: List<ExecutedToolCall>,
        retrieval: List<ScoredChunk>,
    ): TurnResult.Ok = TurnResult.Ok(
        reply = reply,
        modelId = modelId,
        profileName = agent.profileName,
        usage = usage,
        durationMs = durationMs,
        session = historyStore?.stats?.snapshot(),
        stageAdvance = advanceTaskStage(null),
        judge = JudgeOutcome.Blocked(rejected),
        judgeModelId = judge?.modelId,
        executedToolCalls = toolCalls.toList(),
        retrieval = retrieval,
    )

    /**
     * Apply the model's [proposed] stage move to the active task, returning
     * what happened (for a view to render). Honoured only when a task is
     * active, not paused, and the move is legal per [TaskStateMachine].
     * Rendering the outcome is the view's job — this stays pure.
     */
    private fun advanceTaskStage(proposed: TaskStage?): StageAdvance {
        val mem = memory ?: return StageAdvance.None
        val id = mem.activeTaskId() ?: return StageAdvance.None
        if (proposed == null) return StageAdvance.None
        val task = mem.store.loadTask(id) ?: TaskNotes(id, stage = TaskStage.INITIAL)
        if (task.paused) return StageAdvance.None
        val from = task.stage
        // Not a move, but not an illegal one either: the model used the marker to label
        // where it already is. Reported rather than swallowed, so the next turn can say so.
        if (proposed == from) return StageAdvance.Repeated(proposed, TaskStateMachine.allowedNext(proposed))
        if (!TaskStateMachine.canTransition(from, proposed)) {
            val allowed = from?.let(TaskStateMachine::allowedNext).orEmpty()
            return StageAdvance.Rejected(from, proposed, allowed)
        }
        mem.store.saveTask(task.copy(stage = proposed))
        return StageAdvance.Advanced(from, proposed)
    }

    /**
     * Track a stage that refuses to move. A passed turn that leaves an active,
     * non-terminal, non-[paused] stage put — [StageAdvance.None] (no marker at all) or
     * [StageAdvance.Repeated] (the current stage named again) — is a "stall";
     * [STALL_STREAK_LIMIT] of them in a row arm a one-shot nudge for the next turn
     * (see [stallHintMessage]). A real move ([StageAdvance.Advanced]), a rejected
     * one, or anything off-task resets the streak. Only called when [stallHint] is on.
     */
    private fun updateStallHint(advance: StageAdvance, stage: TaskStage?, paused: Boolean) {
        val stalled = (advance is StageAdvance.None || advance is StageAdvance.Repeated) &&
            stage != null && stage != TaskStage.DONE && !paused
        if (stalled) {
            if (++stallStreak >= STALL_STREAK_LIMIT) {
                pendingStallHint = stage
                stallStreak = 0
            }
        } else {
            stallStreak = 0
        }
    }
}

/**
 * Attempts a turn gets past the judge: the first answer plus up to four rewrites.
 *
 * More than a single rewrite because a flaky worker can miss the objection once
 * and land it on the next pass — the extra tries are how a self-correcting model
 * gets to a clean turn instead of a blocked one. Still capped, because every
 * attempt is a worker call plus a judge call, billed and waited through, and a
 * model that hasn't been talked round in five passes won't be by the sixth.
 */
private const val MAX_JUDGE_ATTEMPTS = 5

/**
 * Passed turns in a row that leave the stage put before the engine nudges the
 * model to move on. Two, not one: a single no-move is normal (this stage's work
 * may still be in progress); a run of them is the degeneration loop the nudge is
 * meant to break.
 */
private const val STALL_STREAK_LIMIT = 2

/**
 * The critique handed back to the agent — what was objected to, and what to do
 * with the objection.
 *
 * The last paragraph exists because both breaches observed live were false
 * positives. Without it a rewrite obediently strips out correct facts to appease
 * an objection that was wrong, which is worse than the original reply.
 */
private fun judgeFeedback(verdict: InvariantVerdict): String = buildString {
    appendLine("[invariant-auditor] An independent auditor rejected your previous reply.")
    appendLine("Rewrite it so it honours every invariant. Do not apologise and do not mention")
    appendLine("this message — produce the corrected reply only.")
    appendLine()
    appendLine("Objections:")
    verdict.violations.forEach { appendLine("- [${it.ruleId ?: "constraint"}] ${it.explanation}") }
    appendLine()
    appendLine("Change only what the objections name; keep everything that was already correct.")
    appendLine("If an objection is factually wrong — it claims you invented something the tool")
    appendLine("results actually returned, say — state that in one short sentence and keep the fact.")
    appendLine()
    // The rejected attempt's tools already ran and their effects are real: a ticket it
    // opened exists. Rewriting is a text edit, and calling them again would duplicate
    // that effect — a second ticket for one complaint.
    append("Any tool you already called has ALREADY run and its effect stands — do not call ")
    append("it again to redo the work. Reuse what the results above returned; rewrite the text only.")
}

/**
 * The SYSTEM line shown to the model on the turn AFTER it proposed an illegal
 * stage move. English, like every other system-slot line (`judgeFeedback`,
 * `TaskStage.expectedAction`, the `MemoryLayer` headings).
 *
 * Addressed to the exact mistake — names [StageAdvance.Rejected.from] /
 * [StageAdvance.Rejected.proposed] / [StageAdvance.Rejected.allowed] verbatim —
 * because the always-present static «Allowed next» line already failed to hold
 * flash; a direct «your last move was refused, here is the only way forward»
 * is the stronger nudge.
 */
private fun stageRejectionMessage(rejected: StageAdvance.Rejected): Message {
    val from = rejected.from?.keyword ?: "(none)"
    val allowed = rejected.allowed.joinToString(", ") { it.keyword }
    return Message(
        role = Role.SYSTEM,
        text = "[fsm] Your previous reply asked to move $from → ${rejected.proposed.keyword}, which is not " +
            "allowed: stages advance one at a time and cannot be skipped. The stage did NOT change — you " +
            "are still in $from. To move on, end a reply with a [[stage:<next>]] line choosing one of: " +
            "$allowed. Do not ask for ${rejected.proposed.keyword} again from here. If this stage's work " +
            "is not finished yet, finish it this turn before signalling a move.",
    )
}

/**
 * The SYSTEM line shown after the model named the stage it was already in. Immediate and
 * specific, unlike [stallHintMessage]: it quotes the marker back, says why it did nothing,
 * and lists where the stage can actually go — so the mistake is correctable on the very
 * next turn instead of after [STALL_STREAK_LIMIT] wasted ones.
 *
 * `internal` rather than private so the wording is covered directly.
 */
internal fun stageRepeatMessage(repeated: StageAdvance.Repeated): Message {
    val here = repeated.stage.keyword
    val allowed = repeated.allowed.joinToString(", ") { it.keyword }
    return Message(
        role = Role.SYSTEM,
        text = "$FSM_NO_MOVE Your last reply signalled [[stage:$here]], but $here is the stage you are " +
            "already in — the marker names the stage you are moving TO, so it moved nothing. From $here " +
            "the task can go to: $allowed. If this stage's work is done, end your reply with the marker " +
            "for the stage you are moving to; if it is not, finish it this turn.",
    )
}

/**
 * Tags for the SYSTEM lines the FSM sends the model. Distinct per cause, so a model
 * receiving two of them in one turn reads two different instructions rather than one
 * blurred repetition — and so tests can pin the one they are about.
 */
internal const val FSM_NO_MOVE: String = "[fsm] no move:"
internal const val FSM_STALLED: String = "[fsm] stalled:"

/**
 * The SYSTEM line shown after the model has sat in one stage for several turns
 * without moving — it keeps signalling the CURRENT stage (or emits no marker), so
 * the FSM never advances. Names the next stage explicitly, because the observed
 * failure is the model repeating `[[stage:<current>]]` instead of the next.
 *
 * [TaskStage.VALIDATION] gets its own wording. Naming only the forward exit is
 * enough where the stage's work can simply be finished, but a model that judges its
 * own deliverable inadequate has no move it is willing to make — so it rewords the
 * deliverable and re-signals validation, forever. Here both exits are ordinary
 * outcomes (passes → done, fails → back to execution) and the nudge offers both,
 * plus what a verdict actually is, since restating the deliverable is not one.
 *
 * `internal` rather than private so the wording is covered directly — the arming
 * logic around it ([TurnEngine.updateStallHint]) has no offline harness yet.
 */
internal fun stallHintMessage(from: TaskStage): Message {
    val text = when (from) {
        TaskStage.VALIDATION ->
            "You have stayed in ${from.keyword} for several turns without moving on. Validating means " +
                "judging the deliverable you have ALREADY produced — restating or rewording it is not a " +
                "verdict and leaves the task exactly where it is. Decide this turn: if it meets the goal, " +
                "say so in one line and end your reply with [[stage:${TaskStage.DONE.keyword}]]; if it does " +
                "not, say what is wrong and end with [[stage:${TaskStage.EXECUTION.keyword}]] to fix it. " +
                "The marker names the stage you move TO — [[stage:${from.keyword}]] is the stage you are " +
                "already in and changes nothing."

        else -> {
            val next = TaskStateMachine.allowedNext(from).maxByOrNull { it.ordinal }
            "You have stayed in ${from.keyword} for several turns without moving on. If this stage's " +
                "work is done, end your reply with a [[stage:${next?.keyword}]] line — it must name the " +
                "NEXT stage (${next?.keyword}), not ${from.keyword} again. If the work is not finished, " +
                "finish it this turn."
        }
    }
    return Message(role = Role.SYSTEM, text = "$FSM_STALLED $text")
}

/**
 * Add up what the attempts cost. Every one was really billed, and the turn
 * counter only ticks once, so the run reads as "one exchange, the tokens of the
 * calls it took". Kept private rather than put on [Usage]: one caller, one meaning.
 */
private infix fun Usage?.plus(other: Usage?): Usage? {
    if (this == null) return other
    if (other == null) return this
    return Usage(
        promptTokens = promptTokens + other.promptTokens,
        outputTokens = outputTokens + other.outputTokens,
        thoughtsTokens = thoughtsTokens + other.thoughtsTokens,
        totalTokens = totalTokens + other.totalTokens,
    )
}
