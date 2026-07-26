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
 * paid for spends a budget, an exhausted budget escalates, and the escalation is
 * carried out here — the task is written back and the conversation moves to a
 * fresh branch. The verdict still rides out in [TurnResult.fsm], now as a report
 * of what happened rather than an instruction for someone else to execute.
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
    private suspend fun decide(notes: TaskNotes?, task: Task?, proposed: TaskStage?): Decided {
        if (notes == null || task == null) return Decided(StageAdvance.None, null)
        // No marker at all — the quiet stall. It never reaches `advance`: there is
        // nothing to advance to, and the FSM only ever judges a proposal.
        val proposedStage = proposed?.toFsmStage()
            ?: return Decided(StageAdvance.None, charge(notes, task, RetryReason.NO_MARKER))

        return when (val advance = machine.advance(task, proposedStage)) {
            is AdvanceOutcome.Advanced -> {
                val rendered = StageAdvance.Advanced(advance.from.toTaskStage(), advance.to.toTaskStage())
                // A move onto new ground is progress and costs nothing. A move back over
                // covered ground is legal, applied — and charged, or a task can oscillate
                // between two stages for ever without the FSM ever getting a say. The
                // charge is applied to the moved task, so the stage it now sits on is the
                // one paying.
                val moved = notes.withFsmTask(advance.task)
                save(notes, advance.task)
                Decided(rendered, advance.reason?.let { charge(moved, advance.task, it) })
            }

            is AdvanceOutcome.Repeated -> Decided(
                StageAdvance.Repeated(advance.stage.toTaskStage(), advance.allowed.toTaskStages()),
                charge(notes, task, advance.reason),
            )

            is AdvanceOutcome.Rejected -> Decided(
                StageAdvance.Rejected(
                    advance.from.toTaskStage(),
                    advance.proposed.toTaskStage(),
                    advance.allowed.toTaskStages(),
                ),
                charge(notes, task, advance.reason, proposed = advance.proposed.toTaskStage()),
            )
        }
    }

    /**
     * Spend one retry and store what it left. The task is persisted whatever the
     * verdict — a spent budget is a fact even when the run is over, and a report
     * that cannot see it cannot explain why the task stopped.
     */
    private suspend fun charge(
        notes: TaskNotes?,
        task: Task?,
        reason: RetryReason,
        proposed: TaskStage? = null,
    ): RetryOutcome? {
        if (notes == null || task == null) return null
        val outcome = machine.retry(task, reason)
        save(notes, outcome.task)
        // Only a plain retry talks to the model: a restarted task must not learn it
        // was restarted, and a run that gave up has nobody left to tell.
        if (outcome is RetryOutcome.Retried) pendingFeedback = RetryFeedback(reason, proposed)
        if (outcome is RetryOutcome.Restarted) restart(outcome.task)
        return outcome
    }

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
     * at the moment they are needed.
     */
    private fun feedbackMessage(task: Task?): Message? {
        val feedback = pendingFeedback ?: return null
        if (task == null) return null
        val stage = task.stage.toTaskStage()
        if (stage == TaskStage.DONE) return null
        return retryFeedbackMessage(
            feedback = feedback,
            stage = stage,
            spent = task.stageRetryState.attempt,
            allowed = machine.allowedNext(task.stage).toTaskStages(),
        )
    }

    private fun Set<ru.den.writes.code.agenticHub.features.fsm.Stage>.toTaskStages(): Set<TaskStage> =
        mapTo(mutableSetOf()) { it.toTaskStage() }

    /** What one turn decided: what the view shows, and what the caller may have to act on. */
    private data class Decided(val rendered: StageAdvance, val fsm: RetryOutcome?)
}
