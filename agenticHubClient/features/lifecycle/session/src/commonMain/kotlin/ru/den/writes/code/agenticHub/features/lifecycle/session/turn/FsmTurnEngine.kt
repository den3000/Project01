package ru.den.writes.code.agenticHub.features.lifecycle.session.turn

import ru.den.writes.code.agenticHub.features.agent.AgentConfig
import ru.den.writes.code.agenticHub.features.agent.AgentResponder
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.agent.ToolCallLog
import ru.den.writes.code.agenticHub.features.fsm.AdvanceOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryReason
import ru.den.writes.code.agenticHub.features.fsm.Task
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.lifecycle.session.RagControl
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.llm.ToolDefinition
import ru.den.writes.code.agenticHub.features.llm.ToolExecutor
import ru.den.writes.code.agenticHub.features.llm.ragChunksToContextMessage
import ru.den.writes.code.agenticHub.features.memory.ContextStrategy
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.TaskBinding
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import ru.den.writes.code.agenticHub.features.memory.TurnContext
import ru.den.writes.code.agenticHub.features.memory.db.HistoryStore

/**
 * A turn whose task decisions are made by `features:fsm` instead of inline.
 *
 * Everything about producing an answer is shared with [InlineFsmTurnEngine] (see
 * [TurnAttempts]); what differs is the part after it. Where the inline engine has
 * a transition table of its own and nothing else, this one asks
 * [TaskStateMachine] and gets back both the move and its price: a turn the stage
 * paid for spends a budget, an exhausted budget escalates, and the verdict rides
 * out in [TurnResult.fsm] for the view-model to act on. The engine itself acts on
 * nothing beyond persisting the task — restarting a run is not something a turn
 * can do to itself.
 *
 * Two behaviours are deliberately not the inline engine's:
 * - a task always has a stage (a file without one starts at the beginning), so
 *   routing and judging never see "task with no stage";
 * - the stall nudge is armed off the stage budget rather than a private counter,
 *   so it survives a restart and cannot drift from the budget it warns about.
 */
public class FsmTurnEngine(
    private val cliArgs: StartCommand.SessionInitialState,
    private val llmApi: LlmApi,
    private val historyStore: HistoryStore?,
    private val strategy: ContextStrategy = ContextStrategy.FullHistory,
    private val memory: MemoryProvider? = null,
    private val routedAgents: List<RoutedAgent> = emptyList(),
    private val routedJudges: List<RoutedJudge> = emptyList(),
    private val toolDefs: List<ToolDefinition> = emptyList(),
    private val toolExecutor: ToolExecutor? = null,
    private val ragControl: RagControl? = null,
    private val machine: TaskStateMachine = TaskStateMachine(),
    private val toolCallLog: ToolCallLog = ToolCallLog(),
) : TurnEngine {

    private val fallbackAgent = RoutedAgent(
        binding = TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE),
        responder = AgentResponder(
            AgentConfig(llmApi = llmApi, params = cliArgs.toGenerationParams()),
        ),
        profileName = null,
        modelId = cliArgs.modelProvider.modelId,
    )

    private val attempts = TurnAttempts(historyStore, toolDefs, toolExecutor, toolCallLog)

    /**
     * The move refused (or wasted) on the previous turn, held so THIS turn can say
     * so to the model. Ephemeral on purpose, exactly as in the inline engine: a
     * SYSTEM note in the history would be re-sent every turn and go stale the
     * moment the stage changed.
     */
    private var pendingAdvance: AdvanceOutcome? = null

    /** The agent for [stage]: first routed binding that spans it, else the fallback. */
    private fun agentFor(stage: TaskStage?): RoutedAgent =
        stage?.let { s -> routedAgents.firstOrNull { s in it.binding } } ?: fallbackAgent

    private fun judgeFor(stage: TaskStage): RoutedJudge? =
        routedJudges.firstOrNull { stage in it.binding }

    override suspend fun turn(prompt: String): TurnResult {
        val notes = memory?.activeTaskId()?.let { memory.store.loadTask(it) }
        val task = notes?.toFsmTask()
        val stage = task?.stage?.toTaskStage()
        val agent = agentFor(stage)
        val modelId = agent.modelId
        historyStore?.let { strategy.onTurn(TurnContext(it, llmApi, prompt, cliArgs.modelProvider.modelId)) }
        val userTurn = Message(role = Role.USER, text = prompt)
        val active = ragControl?.active
        val retrieval = active?.retriever?.retrieve(prompt, active.topK).orEmpty()
        val ragContext = if (retrieval.isEmpty()) emptyList() else listOf(ragChunksToContextMessage(retrieval))
        val stageFeedback = stageFeedback(task)
        pendingAdvance = null
        val baseContext =
            stageFeedback + ragContext + (historyStore?.let { strategy.planContext(it.messages) } ?: emptyList())
        val memoryLayer = memory?.memoryLayerFor(agent.profileName) ?: emptyList()
        val judge = if (memory != null) stage?.let(::judgeFor) else null

        return when (val outcome = attempts.run(prompt, userTurn, baseContext, memoryLayer, agent, judge, memory, stage)) {
            // The wire is down, not the model: the transport budget pays, and it is
            // the one budget that can end the run without a restart in between.
            is AttemptOutcome.Failed -> TurnResult.Failed(
                reason = outcome.reason,
                fsm = charge(notes, task, RetryReason.TRANSPORT_FAILED),
            )

            // Every rewrite breached. The turn is spent and the task is no further
            // along, so the stage pays for it exactly like a turn that did not move.
            is AttemptOutcome.Blocked -> TurnResult.Ok(
                reply = outcome.reply,
                modelId = modelId,
                profileName = agent.profileName,
                usage = outcome.usage,
                durationMs = outcome.durationMs,
                session = historyStore?.stats?.snapshot(),
                stageAdvance = StageAdvance.None,
                judge = JudgeOutcome.Blocked(outcome.rejected),
                judgeModelId = judge?.modelId,
                executedToolCalls = outcome.toolCalls,
                retrieval = retrieval,
                fsm = charge(notes, task, RetryReason.JUDGE_BLOCKED),
            )

            is AttemptOutcome.Answered -> {
                val decided = decide(notes, task, outcome.proposedStage)
                TurnResult.Ok(
                    reply = outcome.reply,
                    modelId = modelId,
                    profileName = agent.profileName,
                    usage = outcome.usage,
                    durationMs = outcome.durationMs,
                    session = historyStore?.stats?.snapshot(),
                    stageAdvance = decided.rendered,
                    judge = when {
                        judge == null -> JudgeOutcome.NotRun
                        outcome.rejected.isNotEmpty() -> JudgeOutcome.Retried(outcome.rejected)
                        else -> JudgeOutcome.Clean
                    },
                    judgeModelId = judge?.modelId,
                    executedToolCalls = outcome.toolCalls,
                    retrieval = retrieval,
                    fsm = decided.fsm,
                )
            }
        }
    }

    /**
     * What this turn did to the task: the move if there was one, and the retry it
     * cost if there wasn't. A turn with no active task decides nothing.
     */
    private fun decide(notes: TaskNotes?, task: Task?, proposed: TaskStage?): Decided {
        if (notes == null || task == null) return Decided(StageAdvance.None, null)
        // No marker at all — the quiet stall. It never reaches `advance`: there is
        // nothing to advance to, and the FSM only ever judges a proposal.
        val proposedStage = proposed?.toFsmStage()
            ?: return Decided(StageAdvance.None, charge(notes, task, RetryReason.NO_MARKER))

        return when (val advance = machine.advance(task, proposedStage)) {
            is AdvanceOutcome.Advanced -> {
                save(notes, advance.task)
                Decided(StageAdvance.Advanced(advance.from.toTaskStage(), advance.to.toTaskStage()), null)
            }

            is AdvanceOutcome.Repeated -> {
                pendingAdvance = advance
                Decided(
                    StageAdvance.Repeated(advance.stage.toTaskStage(), advance.allowed.toTaskStages()),
                    charge(notes, task, advance.reason),
                )
            }

            is AdvanceOutcome.Rejected -> {
                pendingAdvance = advance
                Decided(
                    StageAdvance.Rejected(
                        advance.from.toTaskStage(),
                        advance.proposed.toTaskStage(),
                        advance.allowed.toTaskStages(),
                    ),
                    charge(notes, task, advance.reason),
                )
            }
        }
    }

    /**
     * Spend one retry and store what it left. The task is persisted whatever the
     * verdict — a spent budget is a fact even when the run is over, and a report
     * that cannot see it cannot explain why the task stopped.
     */
    private fun charge(notes: TaskNotes?, task: Task?, reason: RetryReason): RetryOutcome? {
        if (notes == null || task == null) return null
        val outcome = machine.retry(task, reason)
        save(notes, outcome.task)
        return outcome
    }

    private fun save(notes: TaskNotes, task: Task) {
        memory?.store?.saveTask(notes.withFsmTask(task))
    }

    /**
     * The `[fsm]` lines this turn opens with: the refused or wasted move from last
     * turn, plus a nudge once the stage has burned [STALL_NUDGE_AFTER] of its
     * budget. The wording is the inline engine's — it was tuned on live runs and
     * has no reason to differ.
     */
    private fun stageFeedback(task: Task?): List<Message> = listOfNotNull(
        (pendingAdvance as? AdvanceOutcome.Rejected)?.let {
            stageRejectionMessage(
                StageAdvance.Rejected(it.from.toTaskStage(), it.proposed.toTaskStage(), it.allowed.toTaskStages()),
            )
        },
        (pendingAdvance as? AdvanceOutcome.Repeated)?.let {
            stageRepeatMessage(StageAdvance.Repeated(it.stage.toTaskStage(), it.allowed.toTaskStages()))
        },
        task?.takeIf { it.stageRetryState.attempt == STALL_NUDGE_AFTER && it.stage.toTaskStage() != TaskStage.DONE }
            ?.let { stallHintMessage(it.stage.toTaskStage()) },
    )

    private fun Set<ru.den.writes.code.agenticHub.features.fsm.Stage>.toTaskStages(): Set<TaskStage> =
        mapTo(mutableSetOf()) { it.toTaskStage() }

    /** What one turn decided: what the view shows, and what the caller may have to act on. */
    private data class Decided(val rendered: StageAdvance, val fsm: RetryOutcome?)
}

/**
 * Turns a stage may burn before the model is nudged about it. Two, like the
 * counter it replaces: a single no-move is normal, a run of them is the
 * degeneration loop the nudge exists to break.
 */
private const val STALL_NUDGE_AFTER = 2
