package ru.den.writes.code.project01.cliJvm.plain

import ru.den.writes.code.project01.cliJvm.AgentRef
import ru.den.writes.code.project01.cliJvm.agentTag

/**
 * The model's reply on stdout, prefixed by the `[[AGENT:]]` tag in a multi-agent
 * session ([agent] non-null).
 */
internal data class AssistantPlainView(val reply: String, val agent: AgentRef?) : PlainView {
    override fun stdout(): List<String> = buildList {
        if (agent != null) add(agentTag(agent.profileName, agent.modelId))
        add(reply)
    }
}
