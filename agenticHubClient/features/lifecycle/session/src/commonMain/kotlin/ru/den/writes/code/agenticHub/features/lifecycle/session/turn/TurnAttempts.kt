package ru.den.writes.code.agenticHub.features.lifecycle.session.turn

import ru.den.writes.code.agenticHub.features.agent.ExecutedToolCall
import ru.den.writes.code.agenticHub.features.agent.RoutedAgent
import ru.den.writes.code.agenticHub.features.agent.RoutedJudge
import ru.den.writes.code.agenticHub.features.agent.ToolCallLog
import ru.den.writes.code.agenticHub.features.agent.invariant.InvariantVerdict
import ru.den.writes.code.agenticHub.features.agent.invariant.JudgeInput
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.llm.ToolDefinition
import ru.den.writes.code.agenticHub.features.llm.ToolExecutor
import ru.den.writes.code.agenticHub.features.llm.Usage
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.ProfileSection
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import ru.den.writes.code.agenticHub.features.memory.TaskStateMachine
import ru.den.writes.code.agenticHub.features.memory.db.HistoryStore
import kotlin.time.measureTimedValue

/**
 * Getting one usable answer out of the agent: call it, let the judge look, hand
 * the objections back for a rewrite, and persist the pair that finally stands.
 *
 * Extracted so the part of a turn that has nothing to do with the task FSM is
 * written once. Both engines assemble the context themselves and both interpret
 * the outcome themselves — what happens in between is identical, and copying it
 * would mean every future fix landing twice.
 *
 * Knows nothing about stages beyond passing [TaskStage] to the judge as context.
 */
internal class TurnAttempts(
    private val historyStore: HistoryStore?,
    private val toolDefs: List<ToolDefinition>,
    private val toolExecutor: ToolExecutor?,
    private val toolCallLog: ToolCallLog,
) {

    /**
     * Run the attempt loop for [prompt]. On success the user turn and the reply
     * are appended to history before returning — the answer stands, so it is
     * stored — and everything else is left to the caller.
     */
    suspend fun run(
        prompt: String,
        userTurn: Message,
        baseContext: List<Message>,
        memoryLayer: List<Message>,
        agent: RoutedAgent,
        judge: RoutedJudge?,
        memory: MemoryProvider?,
        stage: TaskStage?,
    ): AttemptOutcome {
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
                    return AttemptOutcome.Failed(result.error ?: "empty response with no usage")
                }
                return AttemptOutcome.Blocked(
                    lastText, totalUsage, totalDuration, allToolCalls.toList(), rejectedVerdicts.toList(),
                )
            }
            lastText = text

            val verdict = judgeVerdict(judge, memory, agent, text, prompt, stage)
            if (verdict.passed) {
                historyStore?.append(userTurn)
                historyStore?.append(
                    Message(role = Role.ASSISTANT, text = text),
                    usage = totalUsage,
                    modelId = agent.modelId,
                )
                return AttemptOutcome.Answered(
                    reply = text,
                    usage = totalUsage,
                    durationMs = totalDuration,
                    toolCalls = allToolCalls.toList(),
                    proposedStage = outcome.proposedStage,
                    rejected = rejectedVerdicts.toList(),
                )
            }

            rejectedVerdicts += verdict
            if (attempt == MAX_JUDGE_ATTEMPTS - 1) {
                return AttemptOutcome.Blocked(
                    text, totalUsage, totalDuration, allToolCalls.toList(), rejectedVerdicts.toList(),
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
}

/**
 * What the attempts produced, before anyone asks what it means for the task.
 *
 * [Answered] and [Blocked] both cost real calls, so both carry the bill; the
 * difference is that only [Answered] was persisted and only it may move a stage.
 */
internal sealed interface AttemptOutcome {

    data class Answered(
        val reply: String,
        val usage: Usage?,
        val durationMs: Long,
        val toolCalls: List<ExecutedToolCall>,
        /** The stage the model asked for, or null when it named none. */
        val proposedStage: TaskStage?,
        /** Objections the agent talked its way past; empty when it passed first time. */
        val rejected: List<InvariantVerdict>,
    ) : AttemptOutcome

    /** Every rewrite breached: the reply is shown but not persisted. */
    data class Blocked(
        val reply: String,
        val usage: Usage?,
        val durationMs: Long,
        val toolCalls: List<ExecutedToolCall>,
        val rejected: List<InvariantVerdict>,
    ) : AttemptOutcome

    /** The wire failed before any answer was worth keeping. */
    data class Failed(val reason: String) : AttemptOutcome
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
 * Add up what the attempts cost. Every one was really billed, and the turn
 * counter only ticks once, so the run reads as "one exchange, the tokens of the
 * calls it took".
 */
internal infix fun Usage?.plus(other: Usage?): Usage? {
    if (this == null) return other
    if (other == null) return this
    return Usage(
        promptTokens = promptTokens + other.promptTokens,
        outputTokens = outputTokens + other.outputTokens,
        thoughtsTokens = thoughtsTokens + other.thoughtsTokens,
        totalTokens = totalTokens + other.totalTokens,
    )
}
