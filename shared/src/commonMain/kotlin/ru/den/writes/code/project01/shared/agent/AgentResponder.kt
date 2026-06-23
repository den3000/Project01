package ru.den.writes.code.project01.shared.agent

import ru.den.writes.code.project01.shared.llm.LlmResult
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.ToolCall
import ru.den.writes.code.project01.shared.memory.TaskStage
import ru.den.writes.code.project01.shared.memory.TaskStateMachine

/**
 * Outcome of one agent turn: the raw [result] from the model plus the
 * stage the model proposed to move to ([proposedStage]).
 *
 * [proposedStage] is parsed from the reply but NOT validated or applied —
 * the host decides whether to honour it against the transition table. It
 * is null when the reply has no `[[stage:<keyword>]]` marker, the keyword
 * is unknown, or the call failed (no text to parse).
 */
data class TurnOutcome(
    val result: LlmResult,
    val proposedStage: TaskStage?,
    /**
     * Tool calls the responder executed before [result] was produced (empty
     * for a plain turn). The exchange itself is ephemeral — not persisted — so
     * this is what a view/log uses to show that a tool ran.
     */
    val executedToolCalls: List<ExecutedToolCall> = emptyList(),
)

/**
 * One tool call the responder executed during a turn, paired with the textual
 * [output] it fed back to the model.
 */
data class ExecutedToolCall(
    val call: ToolCall,
    val output: String,
)

/**
 * Runs one turn for a single agent: assembles the wire list, asks the
 * model, and reads back the stage signal. Pure of session state — it does
 * not persist, print, time, or validate the transition; those stay with
 * the host loop. This is the portable nucleus a runner (single, sequential
 * or orchestrated) drives.
 *
 * The wire order is `memoryLayer + baseContext + userTurn`: the memory
 * layer sits on top so it stays stable across turns even as the host's
 * context strategy reshapes [baseContext]; the current user turn is always
 * last. Any `Role.SYSTEM` entries inside the layer are lifted into the
 * provider's native system slot by the [AgentConfig.llmApi] — the
 * responder never touches roles.
 */
class AgentResponder(private val config: AgentConfig) {
    suspend fun respond(
        baseContext: List<Message>,
        memoryLayer: List<Message>,
        userTurn: Message,
    ): TurnOutcome {
        val result = config.llmApi.send(
            messages = memoryLayer + baseContext + userTurn,
            params = config.params,
        )
        return TurnOutcome(
            result = result,
            proposedStage = result.text?.let(TaskStateMachine::parseStageSignal),
        )
    }
}
