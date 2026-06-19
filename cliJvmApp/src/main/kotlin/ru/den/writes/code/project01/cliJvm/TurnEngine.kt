package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.shared.agent.AgentConfig
import ru.den.writes.code.project01.shared.agent.AgentResponder
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
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
) {
    /**
     * The default agent: this engine's model surface + generation knobs, no
     * pinned profile. Answers every turn no [routedAgents] binding covers (and
     * when there's no task / stage) — an empty list reproduces single-agent
     * behaviour exactly.
     */
    private val fallbackAgent = RoutedAgent(
        binding = TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE),
        responder = AgentResponder(AgentConfig(llmApi = llmApi, params = cliArgs.toGenerationParams())),
        profileName = null,
        modelId = cliArgs.modelProvider.modelId,
    )

    /** The agent for [stage]: first routed binding that spans it, else the fallback. */
    private fun agentFor(stage: TaskStage?): RoutedAgent =
        stage?.let { s -> routedAgents.firstOrNull { s in it.binding } } ?: fallbackAgent

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

        historyStore?.append(userTurn)
        historyStore?.append(
            Message(role = Role.ASSISTANT, text = text),
            usage = result.usage,
            modelId = modelId,
        )
        val stageAdvance = advanceTaskStage(outcome.proposedStage)
        return TurnResult.Ok(
            reply = text,
            modelId = modelId,
            profileName = agent.profileName,
            usage = result.usage,
            durationMs = duration.inWholeMilliseconds,
            session = historyStore?.stats?.snapshot(),
            stageAdvance = stageAdvance,
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
