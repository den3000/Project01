package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.agent.AgentResponder
import ru.den.writes.code.project01.shared.memory.TaskBinding

/**
 * One agent wired into [SessionLoop], bound to a span of FSM stages. The loop
 * routes a turn to the agent whose [binding] contains the task's current
 * stage.
 *
 * [profileName] is the agent's fixed memory profile (null = the session's live
 * active profile). [modelId] labels the turn for the footer and cost
 * attribution — the actual model+transport live inside [responder].
 */
internal class RoutedAgent(
    val binding: TaskBinding,
    val responder: AgentResponder,
    val profileName: String?,
    val modelId: String,
)
