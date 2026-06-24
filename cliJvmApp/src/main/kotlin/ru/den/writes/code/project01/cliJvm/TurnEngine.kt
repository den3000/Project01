package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.shared.agent.AgentConfig
import ru.den.writes.code.project01.shared.agent.AgentResponder
import ru.den.writes.code.project01.shared.invariant.InvariantVerdict
import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.llm.ToolDefinition
import ru.den.writes.code.project01.shared.llm.ToolExecutor
import ru.den.writes.code.project01.shared.memory.TaskBinding
import ru.den.writes.code.project01.shared.memory.TaskNotes
import ru.den.writes.code.project01.shared.memory.TaskStage
import ru.den.writes.code.project01.shared.memory.TaskStateMachine
import kotlin.time.measureTimedValue

/**
 * The pure engine for one turn: wire assembly → LLM call → persistence →
 * task-stage advance, returning a [TurnResult]. Does NO direct I/O — no
 * `println`, no `System.err`, no feed throttle. A view renders the result;
 * the view-model orchestrates the loop around it.
 *
 * This is [SessionLoop.send] with the printing, the `/reuse` cache hook and
 * the `delay(16s)` removed (the throttle belongs on the feed intent source).
 * Persistence and the FSM write stay here — they aren't stdout/stderr I/O.
 */
internal class TurnEngine(
    private val cliArgs: CliArgs.PromptCommand,
    private val llmApi: LlmApi,
    private val historyStore: HistoryStore?,
    private val strategy: ContextStrategy = ContextStrategy.FullHistory,
    private val memory: MemoryProvider? = null,
    private val routedAgents: List<RoutedAgent> = emptyList(),
    /**
     * Per-stage invariant judges: each owns a [TaskBinding] span and audits the
     * reply of any turn whose active task stage falls in it. Empty (the
     * default) = no judging — byte-identical to before. Needs per-stage agents
     * plus an active task to route on (enforced at parse time, see [CliArgs]).
     */
    private val routedJudges: List<RoutedJudge> = emptyList(),
    /**
     * Tool declarations offered to the default agent (from an MCP server via
     * `-mcpServer`) plus the [ToolExecutor] that runs them. Empty / null
     * (default) = no tools — the agent makes a single LLM call exactly as before.
     */
    private val toolDefs: List<ToolDefinition> = emptyList(),
    private val toolExecutor: ToolExecutor? = null,
) {
    /**
     * The default agent: this engine's model surface + generation knobs, no
     * pinned profile. Answers every turn no [routedAgents] binding covers (and
     * when there's no task / stage) — an empty list reproduces single-agent
     * behaviour exactly.
     */
    private val fallbackAgent = RoutedAgent(
        binding = TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE),
        responder = AgentResponder(
            AgentConfig(
                llmApi = llmApi,
                params = cliArgs.toGenerationParams().copy(tools = toolDefs.ifEmpty { null }),
                toolExecutor = toolExecutor,
            ),
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
        val baseContext = historyStore?.let { strategy.planContext(it.messages) } ?: emptyList()
        // Memory layer (profile / rules / current task) sits ABOVE the history
        // tail so it stays stable across turns. Empty when no MemoryProvider —
        // byte-identical to the no-memory path.
        val memoryLayer = memory?.memoryLayerFor(agent.profileName) ?: emptyList()
        val (outcome, duration) = measureTimedValue {
            agent.responder.respond(baseContext = baseContext, memoryLayer = memoryLayer, userTurn = userTurn)
        }
        val result = outcome.result

        result.error?.let { return TurnResult.Failed(it) }
        val text = result.text ?: return TurnResult.Failed("empty response with no usage")

        // Independent invariant judge (per-stage): resolve the judge spanning
        // the active stage and run it. A breach suppresses the turn — the reply
        // still reaches the view, but it is NOT persisted (so the violation
        // doesn't poison later context) and the task stage is held. No judge /
        // no memory / no stage → CLEAN, identical to before.
        val mem = memory
        val judge = if (mem != null) stage?.let(::judgeFor) else null
        val verdict = if (judge != null && mem != null) {
            judge.checker.check(text, mem.store.listRules(), mem.constraintsForAgent(agent.profileName))
        } else {
            InvariantVerdict.CLEAN
        }
        if (verdict.passed) {
            historyStore?.append(userTurn)
            historyStore?.append(
                Message(role = Role.ASSISTANT, text = text),
                usage = result.usage,
                modelId = modelId,
            )
        }
        // A breach holds the stage by proposing null to the FSM.
        val stageAdvance = advanceTaskStage(if (verdict.passed) outcome.proposedStage else null)
        return TurnResult.Ok(
            reply = text,
            modelId = modelId,
            profileName = agent.profileName,
            usage = result.usage,
            durationMs = duration.inWholeMilliseconds,
            session = historyStore?.stats?.snapshot(),
            stageAdvance = stageAdvance,
            verdict = verdict,
            judgeModelId = judge?.modelId,
            executedToolCalls = outcome.executedToolCalls,
        )
    }

    /**
     * Apply the model's [proposed] stage move to the active task, returning
     * what happened (for a view to render). Honoured only when a task is
     * active, not paused, and the move is legal per [TaskStateMachine].
     * Mirrors `SessionLoop.maybeAdvanceTaskStage`, minus the printing.
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
 * Lift the generation-related flags from the parsed CLI into the neutral
 * [GenerationParams] that crosses the [LlmApi] boundary. `-prompt` (the
 * per-turn payload) and `-model` (configured into the concrete [LlmApi]) are
 * not part of this. Lives on the [CliArgs.PromptCommand] super-type so Chat and
 * OneShot share the same conversion.
 */
internal fun CliArgs.PromptCommand.toGenerationParams(): GenerationParams =
    GenerationParams(
        maxTokens = maxTokens,
        stopSequences = stopSequences,
        endSequence = endSequence,
        temperature = temperature,
    )
