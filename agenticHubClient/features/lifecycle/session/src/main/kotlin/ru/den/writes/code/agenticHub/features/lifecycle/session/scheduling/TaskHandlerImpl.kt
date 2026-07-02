package ru.den.writes.code.agenticHub.features.lifecycle.session.scheduling

import kotlinx.serialization.json.JsonObject
import ru.den.writes.code.agenticHub.scheduling.ScheduledTask
import ru.den.writes.code.agenticHub.scheduling.TaskHandler
import ru.den.writes.code.agenticHub.features.llm.ToolCall
import ru.den.writes.code.agenticHub.features.llm.ToolExecutor

/**
 * The scheduler's per-tick payload for cliJvmApp. Routes by task id through [actions]: a
 * collect task calls an MCP tool directly via [toolExecutor] (token-free) and returns its
 * text for the engine to store; an agent task injects its prompt as a turn via [submitTurn]
 * (into the serialized MVI loop) and returns null. [actions] is filled after the tasks are
 * added, so it is read at tick time.
 */
public class TaskHandlerImpl(
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
