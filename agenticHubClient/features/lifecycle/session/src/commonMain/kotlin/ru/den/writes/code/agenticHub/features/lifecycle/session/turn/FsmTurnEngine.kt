package ru.den.writes.code.agenticHub.features.lifecycle.session.turn

import ru.den.writes.code.agenticHub.features.agent.AgentConfig
import ru.den.writes.code.agenticHub.features.agent.AgentResponder
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.agent.ToolCallLog
import ru.den.writes.code.agenticHub.features.fsm.AdvanceOutcome
import ru.den.writes.code.agenticHub.features.fsm.RetryOutcome
import ru.den.writes.code.agenticHub.features.fsm.Stage
import ru.den.writes.code.agenticHub.features.fsm.Task
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachineImpl
import ru.den.writes.code.agenticHub.features.fsm.UpdateReason
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
 * a transition table of its own and decides the rest as it goes, this one hands
 * the finished turn to [TaskStateMachine] and carries out what comes back: the
 * task is written down, a restart moves the conversation to a fresh branch, the
 * charged reason is held for the next prompt, and the move is phrased for the
 * view. The verdict rides out in [TurnResult.retryOutcome] as a report of what happened.
 *
 * No rule about the task lives here. Which budget a failure spends, whether a
 * legal move still costs a turn, when a stall escalates into a restart — all of
 * that is `features:fsm`'s, and this engine could not answer any of it without
 * asking.
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
    private val machine: TaskStateMachine = TaskStateMachineImpl(),
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
     * The branch the run started on. Every restart hangs its own branch off this
     * one, so the second restart names itself from the original rather than from
     * the branch the first one opened.
     */
    private val baseBranch: String? = historyStore?.branchId

    /**
     * The retry charged on the previous turn, held so THIS turn can tell the model
     * what to redo. One slot, because there is one channel: whatever the FSM
     * charged is what the model hears about. Ephemeral on purpose — a SYSTEM note
     * in the history would be re-sent every turn and go stale the moment the stage
     * changed.
     */
    private var pendingFeedback: RetryFeedback? = null

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
        val stageFeedback = listOfNotNull(feedbackMessage(task))
        pendingFeedback = null
        val baseContext =
            stageFeedback + ragContext + (historyStore?.let { strategy.planContext(it.messages) } ?: emptyList())
        val memoryLayer = memory?.memoryLayerFor(agent.profileName) ?: emptyList()
        val judge = if (memory != null) stage?.let(::judgeFor) else null

        return when (val outcome = attempts.run(prompt, userTurn, baseContext, memoryLayer, agent, judge, memory, stage)) {
            // The wire is down, not the model: the transport budget pays, and it is
            // the one budget that can end the run without a restart in between.
            is AttemptOutcome.Failed -> TurnResult.Failed(
                reason = outcome.reason,
                retryOutcome = updateTaskAfterTurn(notes, task, UpdateReason.TransportFailed).retryOutcome,
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
                retryOutcome = updateTaskAfterTurn(notes, task, UpdateReason.JudgeBlocked).retryOutcome,
            )

            is AttemptOutcome.Answered -> {
                val decided = updateTaskAfterTurn(notes, task, outcome.proposedStage.toUpdateReason())
                TurnResult.Ok(
                    reply = outcome.reply,
                    modelId = modelId,
                    profileName = agent.profileName,
                    usage = outcome.usage,
                    durationMs = outcome.durationMs,
                    session = historyStore?.stats?.snapshot(),
                    stageAdvance = decided.stageAdvance,
                    judge = when {
                        judge == null -> JudgeOutcome.NotRun
                        outcome.rejected.isNotEmpty() -> JudgeOutcome.Retried(outcome.rejected)
                        else -> JudgeOutcome.Clean
                    },
                    judgeModelId = judge?.modelId,
                    executedToolCalls = outcome.toolCalls,
                    retrieval = retrieval,
                    retryOutcome = decided.retryOutcome,
                )
            }
        }
    }

    /**
     * Hand the turn to the machine and carry out what it decided. Everything here is
     * something the machine cannot do: write the task down, move the conversation,
     * arm the note for the next prompt, phrase the move for the view. What the turn
     * cost and whether the task moved is not decided here at all.
     *
     * The task is persisted whatever the verdict — a spent budget is a fact even when
     * the run is over, and a report that cannot see it cannot explain why the task
     * stopped. A turn with no active task has nothing to decide about.
     *
     * A paused task is not asked about either, and that is a seam rather than a
     * forgotten branch: `paused` is a property of running the task, not of the task
     * automaton, so the machine deliberately does not model it (see
     * `TaskNotesFsmMapping`). The flag has to be honoured somewhere, and the only
     * place that can is the caller — so a turn taken while paused reaches the model
     * and reaches history, but never reaches the FSM: the stage holds and no budget
     * is spent. Parity with `InlineFsmTurnEngine`, which refuses the same move
     * further in.
     */
    private suspend fun updateTaskAfterTurn(notes: TaskNotes?, task: Task?, reason: UpdateReason): TurnUpdate {
        if (notes == null || task == null || notes.paused) return TurnUpdate(StageAdvance.None, null)
        val decision = machine.update(task, reason)
        save(notes, decision.task)
        pendingFeedback = decision.retryReason?.let {
            RetryFeedback(it, decision.advance.refusedStage(), decision.allowedNext.toTaskStages())
        }
        if (decision.retryOutcome is RetryOutcome.Restarted) restart(decision.task)
        return TurnUpdate(decision.advance.toStageAdvance(), decision.retryOutcome)
    }

    /** The move as the view says it; [StageAdvance.None] when there was no move to speak of. */
    private fun AdvanceOutcome?.toStageAdvance(): StageAdvance = when (this) {
        null -> StageAdvance.None
        is AdvanceOutcome.Advanced -> StageAdvance.Advanced(from.toTaskStage(), to.toTaskStage())
        is AdvanceOutcome.Repeated -> StageAdvance.Repeated(stage.toTaskStage(), allowed.toTaskStages())
        is AdvanceOutcome.Rejected ->
            StageAdvance.Rejected(from.toTaskStage(), proposed.toTaskStage(), allowed.toTaskStages())
    }

    /** The stage a refused move asked for — the one thing feedback quotes back. */
    private fun AdvanceOutcome?.refusedStage(): TaskStage? =
        (this as? AdvanceOutcome.Rejected)?.proposed?.toTaskStage()

    /**
     * What the model's answer amounts to for the FSM. No marker is not a missing
     * value to be defaulted away — it is the quiet stall, and it has its own case.
     */
    private fun TaskStage?.toUpdateReason(): UpdateReason =
        this?.let { UpdateReason.StageProposed(it.toFsmStage()) } ?: UpdateReason.NoStageProposed

    /**
     * Make the restart real. The machine has already put the task back to the
     * beginning and [save] has written it, but the task is only one of the things a
     * restart has to forget: the conversation is what the model actually reads, and
     * left alone it would carry the whole failed attempt into the fresh one. A new
     * branch empties the wire while the old turns stay in the database, where a
     * report can still count them.
     *
     * Nothing else in the engine needs resetting, which is why this is one call and
     * not a rebuild. [pendingFeedback] is already null here — a turn clears it on the
     * way in and only a plain retry re-arms it — and everything else about the task is
     * re-read from disk on the next turn. What deliberately survives is [toolCallLog]:
     * a tool that ran has had its effect, and a fresh attempt that cannot see it will
     * be judged for inventing the ticket it actually created.
     *
     * **The branch only holds while this engine is alive.** [baseBranch] is a field and
     * nothing persists which branch a restarted task ended up on, while a session opens
     * `DEFAULT_BRANCH` on startup. Restart a task, quit, come back — and the run resumes
     * on the branch of the attempt that FAILED, with everything the restart did stranded
     * where nobody looks. The same goes for the rolling summary and the sticky facts:
     * both hang off (session, branch), so the fresh attempt starts without them. Fixing
     * it belongs at session start (open the task's latest branch, not `main`), not here.
     */
    private suspend fun restart(task: Task) {
        val base = baseBranch ?: return
        // The number names the attempt about to start, so the first restart opens
        // attempt two: one restart spent means one attempt already behind us.
        historyStore?.switchTo("$base-attempt-${task.taskRetryState.attempt + 1}")
    }

    private fun save(notes: TaskNotes, task: Task) {
        memory?.store?.saveTask(notes.withFsmTask(task))
    }

    /**
     * The `[fsm]` line this turn opens with, if last turn cost the task anything.
     * Built here rather than stored: what to say follows from the charged reason,
     * how firmly — from the budget the stage has burned by now, and both are read
     * at the moment they are needed. Where the task may go came with the charge —
     * that table belongs to the FSM and is never consulted from here.
     */
    private fun feedbackMessage(task: Task?): Message? {
        val feedback = pendingFeedback ?: return null
        if (task == null) return null
        val stage = task.stage.toTaskStage()
        if (stage == TaskStage.DONE) return null
        return retryFeedbackMessage(feedback = feedback, stage = stage, spent = task.stageRetryState.attempt)
    }

    private fun Set<Stage>.toTaskStages(): Set<TaskStage> = mapTo(mutableSetOf()) { it.toTaskStage() }

    /** What one turn decided: what the view shows, and what the caller may have to act on. */
    private data class TurnUpdate(val stageAdvance: StageAdvance, val retryOutcome: RetryOutcome?)
}
