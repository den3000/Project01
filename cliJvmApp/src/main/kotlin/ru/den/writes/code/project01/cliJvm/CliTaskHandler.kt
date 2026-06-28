package ru.den.writes.code.project01.cliJvm

import kotlinx.serialization.json.JsonObject
import ru.den.writes.code.project01.scheduling.ScheduledTask
import ru.den.writes.code.project01.scheduling.TaskHandler
import ru.den.writes.code.project01.shared.llm.ToolCall
import ru.den.writes.code.project01.shared.llm.ToolExecutor

/** What a scheduled cliJvmApp task does when it fires. Resolved by task id at tick time. */
internal sealed interface ScheduleAction {
    /** Call an MCP [tool] with [arguments] and store its text (collect). */
    data class Collect(val tool: String, val arguments: JsonObject) : ScheduleAction

    /** Inject [prompt] as a turn (agent). */
    data class Agent(val prompt: String) : ScheduleAction
}

/**
 * The scheduler's per-tick payload for cliJvmApp. Routes by task id through [actions]: a
 * collect task calls an MCP tool directly via [toolExecutor] (token-free) and returns its
 * text for the engine to store; an agent task injects its prompt as a turn via [submitTurn]
 * (into the serialized MVI loop) and returns null. [actions] is filled after the tasks are
 * added, so it is read at tick time.
 */
internal class CliTaskHandler(
    private val actions: Map<String, ScheduleAction>,
    private val toolExecutor: ToolExecutor?,
) : TaskHandler {
    /** Injects a scheduled prompt as a turn. Set after the view-model exists (breaks the
     * vm↔scheduler construction cycle); a no-op until then. */
    var submitTurn: (String) -> Unit = {}

    override suspend fun handle(task: ScheduledTask): String? = when (val action = actions[task.id]) {
        is ScheduleAction.Collect -> toolExecutor?.execute(ToolCall(action.tool, action.arguments))
        is ScheduleAction.Agent -> {
            submitTurn(action.prompt) // inject the prompt as a turn; the engine stores nothing now
            null
        }
        null -> null
    }
}
