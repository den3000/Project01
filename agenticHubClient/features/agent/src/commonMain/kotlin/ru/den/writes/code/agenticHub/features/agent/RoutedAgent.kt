package ru.den.writes.code.agenticHub.features.agent

import ru.den.writes.code.agenticHub.features.memory.TaskBinding

/**
 * One agent wired into [TurnEngine], bound to a span of FSM stages. The engine
 * routes a turn to the agent whose [binding] contains the task's current
 * stage.
 *
 * [profileName] is the agent's fixed memory profile (null = the session's live
 * active profile). [modelId] labels the turn for the footer and cost
 * attribution — the actual model+transport live inside [responder].
 */
public class RoutedAgent(
    val binding: TaskBinding,
    val responder: AgentResponder,
    val profileName: String?,
    val modelId: String,
)
