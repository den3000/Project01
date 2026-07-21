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
public class TurnEngine(
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
     * Session-wide record of what the tools actually did — the judge's evidence.
     * Injected rather than owned so its eviction rule stays testable on its own;
     * the default is the ordinary per-session log.
     */
    private val toolCallLog: ToolCallLog = ToolCallLog(),
) {
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
     * Run one turn for [prompt]. Builds «memory layer + planned history +
     * user turn», calls the routed agent, persists both sides on success,
     * applies any legal task-stage move, and returns an immutable [TurnResult].
     */
    suspend fun turn(prompt: String): TurnResult {
        // The agent that answers is picked by the active task's stage; with no
        // routed agents that's always the fallback (single-agent parity).
        val stage = memory?.activeTaskId()?.let { memory.store.loadTask(it)?.stage }
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
        val baseContext = ragContext + (historyStore?.let { strategy.planContext(it.messages) } ?: emptyList())
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
        var firstVerdict: InvariantVerdict? = null
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
                val failedFirst = firstVerdict ?: return TurnResult.Failed(
                    result.error ?: "empty response with no usage",
                )
                return blockedTurn(
                    lastText, agent, modelId, judge, failedFirst,
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
                return TurnResult.Ok(
                    reply = text,
                    modelId = modelId,
                    profileName = agent.profileName,
                    usage = totalUsage,
                    durationMs = totalDuration,
                    session = historyStore?.stats?.snapshot(),
                    stageAdvance = advanceTaskStage(outcome.proposedStage),
                    judge = when {
                        judge == null -> JudgeOutcome.NotRun
                        firstVerdict != null -> JudgeOutcome.Retried(firstVerdict)
                        else -> JudgeOutcome.Clean
                    },
                    judgeModelId = judge?.modelId,
                    executedToolCalls = allToolCalls.toList(),
                    retrieval = retrieval,
                )
            }

            if (attempt == MAX_JUDGE_ATTEMPTS - 1) {
                return blockedTurn(
                    text, agent, modelId, judge, verdict,
                    totalUsage, totalDuration, allToolCalls, retrieval,
                )
            }
            // Set up the rewrite. The rejected reply joins the context so the agent can
            // see what it wrote, and the objections become the new user turn: the wire
            // reads as ask → answer → auditor objected → rewrite. Feeding the critique
            // as SYSTEM would tear it away from the reply (providers lift SYSTEM into
            // their own slot), and replacing the user turn outright would delete the
            // question, which lives nowhere else until the turn is persisted.
            firstVerdict = verdict
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
        verdict: InvariantVerdict,
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
        judge = JudgeOutcome.Blocked(verdict),
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
        if (proposed == from) return StageAdvance.None
        if (!TaskStateMachine.canTransition(from, proposed)) {
            val allowed = from?.let(TaskStateMachine::allowedNext).orEmpty()
            return StageAdvance.Rejected(from, proposed, allowed)
        }
        mem.store.saveTask(task.copy(stage = proposed))
        return StageAdvance.Advanced(from, proposed)
    }
}

/**
 * Attempts a turn gets past the judge: the answer, then one rewrite.
 *
 * Two rather than more because a judge that rejected the same work twice is
 * unlikely to be talked round by a third pass, and every attempt is paid for in
 * tokens and latency the user is waiting through.
 */
private const val MAX_JUDGE_ATTEMPTS = 2

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
    append("If an objection is factually wrong — it claims you invented something the tool ")
    append("results actually returned, say — state that in one short sentence and keep the fact.")
}

/**
 * Add up what two attempts cost. Both were really billed, and the turn counter
 * only ticks once, so the pair reads as "one exchange, the tokens of two calls".
 * Kept private rather than put on [Usage]: one caller, one meaning.
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

/**
 * Lift the generation-related flags from the parsed CLI into the neutral
 * [GenerationParams] that crosses the [LlmApi] boundary. `-prompt` (the
 * per-turn payload) and `-model` (configured into the concrete [LlmApi]) are
 * not part of this. Lives on the [StartCommand.SessionInitialState] super-type so RunChat and
 * RunOneShot share the same conversion.
 */
public fun StartCommand.SessionInitialState.toGenerationParams(): GenerationParams =
    GenerationParams(
        maxTokens = maxTokens,
        stopSequences = stopSequences,
        endSequence = endSequence,
        temperature = temperature,
        topP = topP,
        seed = seed,
        contextWindow = contextWindow,
    )
